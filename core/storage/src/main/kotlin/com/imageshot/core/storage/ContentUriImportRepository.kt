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
 * - 表示名（PR-19）は取り込み時点で [AssetDisplayNameResolver] の解決連鎖を1回だけ通し、
 *   その結果を既存の displayName 列（[AssetEntity.displayName]）へ保存する
 *   （DB schema 変更なし・解決連鎖が全滅しても取り込み自体は失敗にしない）。
 *   fullyResolved=false の UI 警告は library 側の警告メカニズムと後続 PR で調整する。
 */
class ContentUriImportRepository(
    context: Context,
    private val contentResolver: ContentResolver,
    private val assetDao: AssetDao,
) : ImportRepository {

    private val appContext = context.applicationContext

    /** 表示名の解決連鎖（PR-19）。取り込み経路から1回だけ通される（DI は既定実装に委ねる）。 */
    private val displayNameResolver = AssetDisplayNameResolver { uri ->
        AndroidDisplayNameSource(contentResolver, context.applicationContext, uri)
    }

    /** RAW現像の random access 要件が確定したとき用の staging 作業ディレクトリ（cacheDir 配下）。 */
    private val stagingDir: java.io.File
        get() = java.io.File(appContext.cacheDir, "import-staging").apply { mkdirs() }

    override suspend fun inspect(uri: Uri): ImageOpResult<ImportProbe> = withContext(Dispatchers.IO) {
        // ローカル取得スキーマの保証（http/https 等の暗黙ネットワーク読み込み禁止）。
        if (!ImportSniffer.isAllowedLocalScheme(uri.scheme?.lowercase())) {
            return@withContext ImageOpResult.Failure(
                ErrorCode.UNSUPPORTED_CODEC,
                "Blocked URI scheme: ${uri.scheme} (offline-first: network URIs are never read)",
            )
        }
        val declaredSize = querySize(uri)

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val head = stream.readNBytes(SNIFF_LIMIT)
                val sniffed = ImportSniffer.sniff(head)
                val seekable = stream.markSupported()
                val sizeBytes = declaredSize ?: if (seekable) countRemaining(stream) else null

                // mime（provider 明示値）は resolver への拡張子推定材料として1回だけ取得する
                // （旧: probe 構築時点の getType。分類挙動は同じ try の内側に維持）。
                val mimeGuess = contentResolver.getType(uri)
                // 表示名の解決連鎖（PR-19）: 取り込み時に1回だけ resolver を通す。決まった name は
                // ImportProbe.displayName → registerSupported 経由で AssetEntity.displayName（既存列）
                // にそのまま保存される（schema 変更なし）。連鎖は例外を吸収し、全滅してもここが
                // 失敗になることはない（取り込み失敗に連動させない・失敗時契約は resolver 側）。
                val resolvedName = displayNameResolver.resolve(uri, mimeGuess, sniffed.format)

                // デコーダー検証の補助（mosaic判定・bounds のみで全量展開しない §6/§4.1）。
                val warnings = sniffed.warnings.toMutableList()
                val bounds = if (sniffed.supported) {
                    readBounds(uri).also { (w, h) ->
                        if (w > 0 && h > 0) warnings.add("decoder bounds check: ${w}x${h}")
                        else warnings.add("Decoder bounds check failed; decoder compatibility unverified")
                    }
                } else null

                ImageOpResult.Success(
                    ImportProbe(
                        uri = uri.toString(),
                        displayName = resolvedName.name,
                        mimeGuess = mimeGuess,
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
                    "Blocked URI scheme: ${uri.scheme}",
                )
            }

            // SAF / Photo Picker の継続再オープン用 grant（ADR-009）。失敗しても取り込み
            // 自体は短期権限で成立するので、Degraded（警告明示）で返す。
            var grantWarnings: List<String> = emptyList()
            if (policy.persistUriPermission) {
                val grant = takePersistableReadGrant(uri)
                if (grant is ImageOpResult.Failure) {
                    grantWarnings = listOf(
                        "Failed to obtain persistable URI permission (${grant.code}); relink may be required after reboot",
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
                            unsupportedImportDetail(probe),
                        )
                    }
                    registerSupported(uri, probe, grantWarnings)
                }
            }
        }

    /**
     * 表示名は resolver 連鎖（PR-19）で name が空になることはない（全滅時は hash プレースホルダ）。
     * null になった場合は想定外の状態であり、旧来のフォールバックを防御として維持する。
     */
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
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                val found = mutableListOf<String>()
                contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val mime = c.getString(1)
                        val name = c.getString(2)
                        if (docId != null && ImportSniffer.isImportCandidate(mime, name)) {
                            found.add(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                            )
                        }
                    }
                } ?: return@withContext ImageOpResult.Failure(
                    ErrorCode.URI_REVOKED,
                    "Tree query returned null (permission may be revoked)",
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

/**
 * 非対応取り込みの説明文（U01）。RAW は拡張子から機種名を補い、magic 判定の
 * `TIFF_FAMILY` だけでは伝わらない「何のファイルを開こうとしたか」を明示する。
 * 表示名は補助情報であり、対応可否の判定は [ImportSniffer.sniff] の結果のみを使う。
 */
internal fun unsupportedImportDetail(probe: ImportProbe): String {
    val rawLabel = ImportSniffer.rawDisplayName(probe.displayName)
    val prefix = if (rawLabel != null) {
        "Unsupported candidate: $rawLabel (RAW development is not supported)"
    } else {
        "Unsupported candidate"
    }
    val fileName = probe.displayName?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return "$prefix:$fileName format=${probe.sniffedFormat} ${probe.warnings.joinToString(" / ")}"
}
