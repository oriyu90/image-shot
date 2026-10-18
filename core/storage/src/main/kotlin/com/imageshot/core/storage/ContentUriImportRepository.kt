package com.imageshot.core.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.imageshot.core.database.AssetDao
import com.imageshot.core.model.Asset
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.ImportPolicy
import com.imageshot.core.model.ImportProbe
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ContentResolver / SAF ベースの取り込み実装（§5, §8.1）。
 * Photo Picker は UI 層（feature:library）が選択結果 URI を渡す役割で、
 * このモジュールは URI 検査・staging・指紋・登録のみを担う。
 *
 * - 画像原本の書き込みは一切行わない（ADR-009）。
 * - 写真の内容・パス・GPS を外部へ送信しない（01_製品仕様書.md §12）。
 */
class ContentUriImportRepository(
    context: Context,
    private val contentResolver: ContentResolver,
    private val assetDao: AssetDao,
) : ImportRepository {

    private val appContext = context.applicationContext

    /** staging 作業ディレクトリ（cacheDir 配下）。登録完了後に掃除する。 */
    private val stagingDir: File
        get() = File(appContext.cacheDir, "import-staging").apply { mkdirs() }

    override suspend fun inspect(uri: Uri): ImageOpResult<ImportProbe> = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val declaredSize = querySize(uri)

        runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val head = stream.readNBytes(SNIFF_LIMIT)
                val sniffed = MagicSniffer.sniffHead(head)
                val seekable = stream.markSupported()
                val sizeBytes = declaredSize ?: if (seekable) countRemaining(stream) else null
                ImageOpResult.Success(
                    ImportProbe(
                        uri = uri.toString(),
                        displayName = displayName,
                        mimeGuess = contentResolver.getType(uri),
                        sniffedFormat = sniffed.format,
                        sizeBytes = sizeBytes,
                        seekable = seekable,
                        supported = sniffed.supported,
                        warnings = sniffed.warnings,
                    ),
                )
            } ?: ImageOpResult.Failure(
                ErrorCode.URI_REVOKED,
                "openInputStream returned null (provider revoked or vanished)",
            )
        }.getOrElse { e ->
            ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, e.message)
        }
    }

    override suspend fun register(uri: Uri, policy: ImportPolicy): ImageOpResult<String> =
        withContext(Dispatchers.IO) {
            // SAF（ACTION_OPEN_DOCUMENT）選択時に限り、永続権限を取得する（ADR-009）。
            if (policy.persistUriPermission) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.getOrElse { grantError ->
                    return@withContext ImageOpResult.Failure(
                        ErrorCode.URI_REVOKED,
                        "takePersistableUriPermission failed: ${grantError.message}",
                    )
                }
            }

            when (val probeResult = inspect(uri)) {
                is ImageOpResult.Failure -> probeResult
                is ImageOpResult.Success -> {
                    val probe = probeResult.value
                    if (!probe.supported) {
                        return@withContext ImageOpResult.Failure(
                            ErrorCode.UNSUPPORTED_CODEC,
                            "sniffed format=${probe.sniffedFormat}",
                        )
                    }
                    registerSupported(uri, policy, probe)
                }
                is ImageOpResult.Degraded -> {
                    // 探査は成功しているが能力不足等。登録は続行せず、調整済みの警告を明示で返す。
                    ImageOpResult.Failure(
                        ErrorCode.PRESET_PARTIAL,
                        "probe degraded: ${probeResult.code} ${probeResult.warnings}",
                    )
                }
            }
        }

    private suspend fun registerSupported(
        uri: Uri,
        policy: ImportPolicy,
        probe: ImportProbe,
    ): ImageOpResult<String> {
        val stagingCopy = File(stagingDir, "stage-${UUID.randomUUID()}")
        try {
            val fingerprint = computeFingerprint(uri, probe, policy, stagingCopy)

            val assetId = UUID.randomUUID().toString()
            val bounds = readBounds(uri, probe)
            // RAW/非対応情報は現時点では不明のまま保存（不明を不明として表示する原則）。
            // TODO(ADR確認): EXIF orientation / dateTaken / ICC hash は core:metadata 導入時に追加。
            val entity = com.imageshot.core.database.AssetEntity(
                id = assetId,
                sourceUri = uri.toString(),
                displayName = probe.displayName ?: "(untitled)",
                mimeGuess = probe.mimeGuess ?: "",
                sniffedFormat = probe.sniffedFormat,
                width = bounds.first,
                height = bounds.second,
                orientation = 0,
                dateTaken = null,
                rawCameraId = null,
                colorProfileHash = null,
                imageFingerprint = fingerprint,
                availability = Asset.AVAILABILITY_OK,
                importedAt = System.currentTimeMillis(),
            )
            assetDao.upsert(entity)
            return ImageOpResult.Success(assetId)
        } catch (e: InsufficientStagingException) {
            return ImageOpResult.Failure(ErrorCode.INSUFFICIENT_STORAGE, e.message)
        } catch (e: SecurityException) {
            return ImageOpResult.Failure(ErrorCode.URI_REVOKED, e.message)
        } catch (e: IOException) {
            return ImageOpResult.Failure(ErrorCode.MALFORMED_METADATA, e.message)
        } finally {
            // staging はキャッシュであり原本ではない。完了時には削除する。
            stagingCopy.delete()
        }
    }

    /**
     * 指紋計算。
     * - seekable: 逐次streamで sha-256。
     * - 非seekable: 上限内で staging へ完全コピーしてから、staging に対して sha-256（§8.1）。
     */
    private fun computeFingerprint(
        uri: Uri,
        probe: ImportProbe,
        policy: ImportPolicy,
        stagingCopy: File,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(HASH_BUF)

        if (probe.seekable) {
            contentResolver.openInputStream(uri)!!.use { stream ->
                var read: Int
                while (stream.read(buf).also { read = it } >= 0) {
                    digest.update(buf, 0, read)
                }
            }
            return hex(digest)
        }

        val declared = probe.sizeBytes
        if (declared != null && declared > policy.maxStagingBytes) {
            throw InsufficientStagingException(
                "non-seekable input exceeds staging limit: $declared bytes",
            )
        }
        var written = 0L
        contentResolver.openInputStream(uri)!!.use { source ->
            stagingCopy.outputStream().use { out ->
                var read: Int
                while (source.read(buf).also { read = it } >= 0) {
                    written += read
                    if (written > policy.maxStagingBytes) {
                        throw InsufficientStagingException(
                            "staging copy exceeded limit while hashing: $written bytes",
                        )
                    }
                    digest.update(buf, 0, read)
                    out.write(buf, 0, read)
                }
            }
        }
        return hex(digest)
    }

    /** bounds のみの軽量デコード。失敗時は (0,0)＝不明を表現する。 */
    private fun readBounds(uri: Uri, probe: ImportProbe): Pair<Int, Int> {
        if (!probe.mimeGuess.orEmpty().startsWith("image/")) return 0 to 0
        return runCatching {
            contentResolver.openInputStream(uri)!!.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                (options.outWidth.takeIf { it > 0 } ?: 0) to
                    (options.outHeight.takeIf { it > 0 } ?: 0)
            }
        }.getOrElse { 0 to 0 }
    }

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

    private fun hex(digest: MessageDigest): String =
        digest.digest().joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        const val HASH_BUF = 64 * 1024
        const val SNIFF_LIMIT = 64
    }
}

private data class SniffResult(val format: String, val supported: Boolean, val warnings: List<String>)

/** staging 上限超過など、容量由来の失敗を分類するための内部例外。 */
private class InsufficientStagingException(message: String) : IOException(message)

/**
 * magic bytes を拡張子に代わる判定基準として使う（§7.1: 拡張子だけを信用しない）。
 * 対応判定の正本は decoder+検証結果であり、本sniffはヘッダー段階の第1フィルタである。
 */
private object MagicSniffer {
    fun sniffHead(head: ByteArray): SniffResult {
        var warnings: List<String> = emptyList()
        val format = when {
            head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "JPEG"
            head.size >= 8 && head[0] == 0x89.toByte() &&
                head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() ->
                "PNG"
            head.size >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WEBP" ->
                "WEBP"
            head.size >= 12 && String(head, 4, 4) == "ftyp" -> {
                val brand = String(head, 8, 4)
                when {
                    brand.startsWith("hei") || brand.startsWith("mif1") || brand.startsWith("avic") ->
                        "HEIF"
                    else -> {
                        warnings = listOf("ISOBMFF but unknown brand: $brand")
                        brand
                    }
                }
            }
            head.size >= 4 && (String(head, 0, 2) == "II" || String(head, 0, 2) == "MM") -> {
                // RAW/DNG/TIFF の確定はデコーダー検証後に判定する（§4.1）。
                warnings = listOf("TIFF family sniffed; DNG/RAW discrimination pending")
                "TIFF_FAMILY"
            }
            head.isEmpty() -> {
                warnings = listOf("empty stream")
                "UNKNOWN"
            }
            else -> "UNKNOWN"
        }
        val supported = format in SUPPORTED
        return SniffResult(format, supported, warnings)
    }

    /** 他形式は 01_製品仕様書.md §6 の区分（B/C）または検証ゲート未達のまま非対応扱い。 */
    private val SUPPORTED = setOf("JPEG", "PNG", "WEBP", "HEIF", "TIFF_FAMILY")
}
