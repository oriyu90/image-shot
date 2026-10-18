package com.imageshot.core.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.imageshot.core.database.AssetDao
import com.imageshot.core.database.AssetEntity
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ContentResolver / SAF ベースの取り込み実装（§5, §8.1, U01）。
 * Photo Picker / SAF の選択結果 URI は UI 層（feature:library）が渡す役割で、
 * このモジュールは URI 検査・非対応候補の明示・register・指紋遅延計算・可用性probeを担う。
 *
 * - 画像原本の書き込みは一切行わない（ADR-009）。M1 の登録では原本コピーを作らない
 *   （sha-256 は遅延計算。stagingは RAW現像の random access 要件が確定した時点で Staging 経由）。
 * - 写真の内容・パス・GPS を外部へ送信しない（01_製品仕様書.md §12）。INTERNET権限は使わない。
 */
class ContentUriImportRepository(
    context: Context,
    private val contentResolver: ContentResolver,
    private val assetDao: AssetDao,
) : ImportRepository {

    private val appContext = context.applicationContext

    /** RAW現像の random access 要件が確定したとき用の staging 作業ディレクトリ（cacheDir 配下）。 */
    private val stagingDir: java.io.File
        get() = java.io.File(appContext.cacheDir, "import-staging").apply { mkdirs() }

    override suspend fun inspect(uri: Uri): ImageOpResult<ImportProbe> = withContext(Dispatchers.IO) {
        // ローカル取得スキーマの保証（http/https 等の暗黙ネットワーク読み込み禁止）。
        if (!ImportSniffer.isAllowedLocalScheme(uri.scheme?.lowercase())) {
            return@withContext ImageOpResult.Failure(
                ErrorCode.UNSUPPORTED_CODEC,
                "禁止スキーマ scheme=${uri.scheme}（オフライン優先: ネットワークURIは読み込まない）",
            )
        }
        val displayName = queryDisplayName(uri)
        val declaredSize = querySize(uri)

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val head = stream.readNBytes(SNIFF_LIMIT)
                val sniffed = ImportSniffer.sniff(head)
                val seekable = stream.markSupported()
                val sizeBytes = declaredSize ?: if (seekable) countRemaining(stream) else null

                // デコーダー検証の補助（mosaic判定・bounds のみで全量展開しない §6/§4.1）。
                val warnings = sniffed.warnings.toMutableList()
                val bounds = if (sniffed.supported) {
                    readBounds(uri).also { (w, h) ->
                        if (w > 0 && h > 0) warnings.add("decoder bounds check: ${w}x${h}")
                        else warnings.add("decoder bounds check 失敗（デコーダー適合は未検証）。表示時に要注意")
                    }
                } else null

                ImageOpResult.Success(
                    ImportProbe(
                        uri = uri.toString(),
                        displayName = displayName,
                        mimeGuess = contentResolver.getType(uri),
                        sniffedFormat = sniffed.format,
                        sizeBytes = sizeBytes,
                        seekable = seekable,
                        supported = sniffed.supported,
                        warnings = warnings,
                    ),
                )
            } ?: ImageOpResult.Failure(
                ErrorCode.URI_REVOKED,
                "openInputStream returned null (provider revoked or vanished)",
            )
        } catch (e: Throwable) {
            failureFrom(e, "inspect")
        }
    }

    override suspend fun register(uri: Uri, policy: ImportPolicy): ImageOpResult<String> =
        withContext(Dispatchers.IO) {
            if (!ImportSniffer.isAllowedLocalScheme(uri.scheme?.lowercase())) {
                return@withContext ImageOpResult.Failure(
                    ErrorCode.UNSUPPORTED_CODEC,
                    "禁止スキーマ scheme=${uri.scheme}",
                )
            }

            // SAF / Photo Picker の継続再オープン用 grant（ADR-009）。失敗しても取り込み
            // 自体は短期権限で成立するので、Degraded（警告明示）で返す。
            var grantWarnings: List<String> = emptyList()
            if (policy.persistUriPermission) {
                val grant = takePersistableReadGrant(uri)
                if (grant is ImageOpResult.Failure) {
                    grantWarnings = listOf(
                        "persistable URI permission 取得失敗（${grant.code}）: 端末再起動後に再関連付けが必要になる可能性",
                    )
                }
            }

            when (val probeResult = inspect(uri)) {
                is ImageOpResult.Failure -> probeResult
                is ImageOpResult.Degraded ->
                    ImageOpResult.Failure(
                        ErrorCode.PRESET_PARTIAL,
                        "probe degraded: ${probeResult.code} ${probeResult.warnings}",
                    )
                is ImageOpResult.Success -> {
                    val probe = probeResult.value
                    if (!probe.supported) {
                        // 非対応は「候補」として明示する（U01 AC: 非対応は明示）。
                        return@withContext ImageOpResult.Failure(
                            ErrorCode.UNSUPPORTED_CODEC,
                            "非対応候補: format=${probe.sniffedFormat} ${probe.warnings.joinToString(" / ")}",
                        )
                    }
                    registerSupported(uri, probe, grantWarnings)
                }
            }
        }

    private suspend fun registerSupported(
        uri: Uri,
        probe: ImportProbe,
        grantWarnings: List<String>,
    ): ImageOpResult<String> {
        // 同一URIの重複登録を原本複製なしで受ける（ADR-009）。
        assetDao.getBySourceUri(uri.toString())?.let { existing ->
            if (existing.availability != Asset.AVAILABILITY_OK) {
                assetDao.updateAvailability(existing.id, Asset.AVAILABILITY_OK)
            }
            return if (grantWarnings.isEmpty()) {
                ImageOpResult.Success(existing.id)
            } else {
                ImageOpResult.Degraded(existing.id, ErrorCode.URI_REVOKED, grantWarnings)
            }
        }

        val assetId = UUID.randomUUID().toString()
        val bounds = readBounds(uri)
        // 判明するメタデータは MediaStore/DocumentsProvider 列の簡易参照（EXIF全面解析は
        // core:metadata 導入時に追加。不明は不明のまま保存する原則）。
        val metadata = SourceMetadataReader.read(appContext, contentResolver, uri)

        val entity = AssetEntity(
            id = assetId,
            sourceUri = uri.toString(),
            displayName = probe.displayName ?: "(untitled)",
            mimeGuess = probe.mimeGuess ?: "",
            sniffedFormat = probe.sniffedFormat,
            width = bounds.first.takeIf { it > 0 }
                ?: metadata?.width?.takeIf { it > 0 }
                ?: 0,
            height = bounds.second.takeIf { it > 0 }
                ?: metadata?.height?.takeIf { it > 0 }
                ?: 0,
            orientation = metadata?.orientation ?: 0,
            dateTaken = metadata?.dateTakenMs,
            rawCameraId = null,
            colorProfileHash = null,
            // 遅延実行（U01）。直後に ViewModel が ensureFingerprint を予定する。
            imageFingerprint = null,
            availability = Asset.AVAILABILITY_OK,
            importedAt = System.currentTimeMillis(),
        )
        assetDao.upsert(entity)
        return if (grantWarnings.isEmpty()) {
            ImageOpResult.Success(assetId)
        } else {
            ImageOpResult.Degraded(assetId, ErrorCode.URI_REVOKED, grantWarnings)
        }
    }

    override suspend fun ensureFingerprint(assetId: String): ImageOpResult<String> =
        withContext(Dispatchers.IO) {
            val entity = assetDao.get(assetId) ?: return@withContext ImageOpResult.Failure(
                ErrorCode.URI_REVOKED,
                "asset not found: $assetId",
            )
            // 計算済みなら再計算しない（原本の内容が変わった場合の検証は export/再検証の契約で担保）。
            entity.imageFingerprint?.let { return@withContext ImageOpResult.Success(it) }

            when (val computed = computeFingerprint(Uri.parse(entity.sourceUri))) {
                is ImageOpResult.Failure -> computed
                is ImageOpResult.Degraded -> computed
                is ImageOpResult.Success -> {
                    assetDao.updateFingerprint(assetId, computed.value)
                    ImageOpResult.Success(computed.value)
                }
            }
        }

    override suspend fun computeFingerprint(uri: Uri): ImageOpResult<String> =
        withContext(Dispatchers.IO) {
            // 逐次 stream の sha-256。全量メモリ展開・原本コピーは行わない。
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    ImageOpResult.Success(Fingerprinter.sha256Hex(stream))
                } ?: ImageOpResult.Failure(
                    ErrorCode.URI_REVOKED,
                    "openInputStream returned null (provider revoked or vanished)",
                )
            } catch (e: Throwable) {
                failureFrom(e, "computeFingerprint")
            }
        }

    override suspend fun takePersistableReadGrant(uri: Uri): ImageOpResult<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.fold(
                onSuccess = { ImageOpResult.Success(Unit) },
                onFailure = { e ->
                    ImageOpResult.Failure(
                        ErrorCode.URI_REVOKED,
                        "takePersistableUriPermission failed: ${e.message}",
                    )
                },
            )
        }

    override suspend fun listImagesInTree(treeUri: Uri): ImageOpResult<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                val treeDocId = DocumentsContract.getDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
                val found = mutableListOf<String>()
                contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val mime = c.getString(1)
                        if (docId != null && ImportSniffer.isImageMime(mime)) {
                            found.add(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            )
                        }
                    }
                } ?: return@withContext ImageOpResult.Failure(
                    ErrorCode.URI_REVOKED,
                    "tree query returned null（権限失効の可能性）",
                )
                ImageOpResult.Success(found)
            } catch (e: SecurityException) {
                ImageOpResult.Failure(
                    ErrorCode.URI_REVOKED,
                    "tree scan revoked: ${e.message}",
                )
            } catch (e: IOException) {
                ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, e.message)
            } catch (e: Exception) {
                ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, e.message)
            }
        }

    override suspend fun probeAvailability(uri: Uri): UriAccessibility = withContext(Dispatchers.IO) {
        UriAccessibilityProbe.probe(contentResolver, uri)
    }

    /** 例外種別→ErrorCode への一括写像（MALFORMED_METADATA / URI_REVOKED 等 §11）。 */
    private fun failureFrom(e: Throwable, phase: String): ImageOpResult.Failure =
        when (val classified = ProviderErrorClassifier.classify(e)) {
            is ProviderFailure.PermissionRevoked -> ImageOpResult.Failure(
                ErrorCode.URI_REVOKED,
                "$phase: ${classified.explain}",
            )
            is ProviderFailure.ContentGone -> ImageOpResult.Failure(
                ErrorCode.URI_REVOKED,
                "$phase: ${classified.explain}",
            )
            is ProviderFailure.OutOfMemory -> ImageOpResult.Failure(
                ErrorCode.OUT_OF_MEMORY,
                "$phase: ${classified.explain}",
            )
            is ProviderFailure.ProviderIo -> ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "$phase: ${classified.explain}",
            )
            is ProviderFailure.Unexpected -> ImageOpResult.Failure(
                ErrorCode.MALFORMED_METADATA,
                "$phase: ${classified.explain}",
            )
        }

    /** bounds のみの軽量デコード。失敗時は (0,0)＝不明を表現する。全量展開はしない。 */
    private fun readBounds(uri: Uri): Pair<Int, Int> = runCatching {
        contentResolver.openInputStream(uri)!!.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            (options.outWidth.takeIf { it > 0 } ?: 0) to
                (options.outHeight.takeIf { it > 0 } ?: 0)
        }
    }.getOrElse { 0 to 0 }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !c.isNull(idx)) c.getLong(idx) else null
            } else null
        }
    }.getOrNull()

    private fun countRemaining(stream: java.io.InputStream): Long {
        var n = 0L
        val buf = ByteArray(HASH_BUF)
        while (stream.read(buf) >= 0) n += buf.size
        return n
    }

    private companion object {
        const val HASH_BUF = 64 * 1024
        const val SNIFF_LIMIT = 64
    }
}
