package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * 書き出し用フル解像度再現パス（core:render 内の新規ファイル）。
 * プレビュー（SimpleBitmapPhotoRenderer）と同じノード実装・同じパラメータ（§4 処理パイプライン:
 * 「プレビューと書き出しで同じノード実装とパラメータ、違いは品質設定・タイルスケール・精度のみ」）。
 *
 * 【明示する制限（未検証を実装済みと報告しない・ADR-014）】
 * - 8bit sRGB 前提の暫定パス。ADR-005 の Linear Rec.2020 float パイプラインではない。
 * - EXIF向き・入力ICC・Gainmap は未解決（プレビュー実装と同じ）→ 必ず warnings に明示する。
 * - 出力は新規エンコードのみ。
 * - メタデータ: EXIF/GPS は書き込まない（最小化・GPS除去、§8）。EXIF pass-through は未実装。
 *   ICC sRGB タグ付与：JPEG は未実装（本PR対象外、報告書参照）。PNG は sRGB チャンクを付与する。
 */
class ExportBitmapRenderer(context: Context) {

    private val appContext = context.applicationContext

    /** 巨大Bitmapの多重保持禁止（§4.3）: 同時レンダリングは1に固定し、中間Bitmapを逐次解放する。 */
    private val gate = Semaphore(1)

    suspend fun render(
        source: Uri,
        recipe: EditRecipe,
        spec: ExportRenderSpec,
        target: OutputStream,
        shouldCancel: () -> Boolean = { false },
        onStage: (stage: Stage, percent: Int) -> Unit = { _, _ -> },
    ): ImageOpResult<ExportRenderOutput> = withContext(Dispatchers.IO) {
        gate.acquire()
        try {
            // フル解像度での安全なデコード（無条件展開禁止: 事前bounds・事前上限検査）
            onStage(Stage.DECODE, 2)
            val bounds = readBounds(source)
            if (bounds.width <= 0 || bounds.height <= 0) {
                return@withContext failure(ErrorCode.UNSUPPORTED_CODEC, "source bounds unavailable (malformed/unsupported)")
            }
            val pixels = bounds.width.toLong() * bounds.height.toLong()
            if (pixels > MAX_DECODE_PIXELS) {
                return@withContext failure(
                    ErrorCode.OUT_OF_MEMORY,
                    "full-res decode would exceed fixed local limit: $pixels px > $MAX_DECODE_PIXELS px",
                )
            }

            val decoded = BitmapFactory.decodeStream(
                appContext.contentResolver.openInputStream(source),
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                },
            ) ?: return@withContext failure(ErrorCode.UNSUPPORTED_CODEC, "full-res decode failed")

            if (shouldCancel()) {
                decoded.recycle()
                return@withContext failure(ErrorCode.EXPORT_INTERRUPTED, "cancelled during decode")
            }
            onStage(Stage.DECODE, 25)

            val processed = applyTone(decoded, recipe)
            if (processed !== decoded) decoded.recycle()
            onStage(Stage.PROCESS, 45)

            val finalBitmap = if (spec.maxEdgePx != null) resizeToMaxEdge(processed, spec.maxEdgePx) else processed
            if (finalBitmap !== processed) processed.recycle()
            if (shouldCancel()) {
                finalBitmap.recycle()
                return@withContext failure(ErrorCode.EXPORT_INTERRUPTED, "cancelled during resize")
            }
            onStage(Stage.PROCESS, 55)

            // 警告の評価は recycle() より前に実施する（recycled Bitmap の状態参照を避ける）。
            val warnings = buildWarnings(recipe, finalBitmap, spec)

            val digest = MessageDigest.getInstance("SHA-256")
            val digestOut = DigestOutputStream(target, digest)
            val width = finalBitmap.width
            val height = finalBitmap.height

            val failure = encodeAndWrite(finalBitmap, spec, digestOut, onStage)
            finalBitmap.recycle()
            if (failure != null) return@withContext failure

            onStage(Stage.ENCODE, 95)
            ImageOpResult.Success(
                ExportRenderOutput(
                    widthPx = width,
                    heightPx = height,
                    bytes = -1L, // 実byte数は VERIFYING 段で staged ファイルから確定（不明を不明のまま）
                    sha256Hex = digest.digest().joinToString(separator = "") { "%02x".format(it) },
                    warnings = warnings,
                ),
            )
        } catch (oom: OutOfMemoryError) {
            ImageOpResult.Failure(ErrorCode.OUT_OF_MEMORY, oom.message)
        } catch (e: SecurityException) {
            ImageOpResult.Failure(ErrorCode.URI_REVOKED, e.message)
        } catch (e: java.io.IOException) {
            ImageOpResult.Failure(ErrorCode.EXPORT_INTERRUPTED, "I/O failure while staging: ${e.message}")
        } finally {
            gate.release()
        }
    }

    /** エンコードとターゲットへの書き込み。成功時 null、失敗時は分類済み Failure を返す。 */
    private fun encodeAndWrite(
        bitmap: Bitmap,
        spec: ExportRenderSpec,
        digestOut: DigestOutputStream,
        onStage: (Stage, Int) -> Unit,
    ): ImageOpResult.Failure? {
        return when (spec.format.uppercase()) {
            FORMAT_JPEG -> {
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, spec.quality, digestOut)
                digestOut.flush()
                if (!ok) failure(ErrorCode.EXPORT_INTERRUPTED, "JPEG encode failed") else null
            }
            FORMAT_PNG -> {
                // sRGB チャンク付与のため、エンコードをメモリで受け、IEND 直前に挿入してから書き出す。
                onStage(Stage.ENCODE, 70)
                val mem = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, mem)) {
                    return failure(ErrorCode.EXPORT_INTERRUPTED, "PNG encode failed")
                }
                val withSrgb = PngMetadata.insertSrgbChunk(mem.toByteArray())
                digestOut.write(withSrgb)
                digestOut.flush()
                null
            }
            else -> failure(ErrorCode.UNSUPPORTED_CODEC, "unsupported export format: ${spec.format}")
        }
    }

    private fun failure(code: ErrorCode, detail: String?): ImageOpResult.Failure =
        ImageOpResult.Failure(code, detail)

    /** boundsの事前読み取り（フルデコードの可否判断、フル展開は行わない）。 */
    private fun readBounds(uri: Uri): Bounds = runCatching {
        appContext.contentResolver.openInputStream(uri)!!.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            Bounds(width = options.outWidth.takeIf { it > 0 } ?: 0, height = options.outHeight.takeIf { it > 0 } ?: 0)
        }
    }.getOrDefault(Bounds(0, 0))

    /**
     * トーン/色調整（プレビューレンダラーと同一ノード・同一パラメータ、フル解像度で適用）。
     * 【明示】カーブ・HSL・マスク・ジオメトリ・レンズ補正・ディテールは未適用。
     * 非既定値が存在する場合は対応警告を必ず出す（ADR-014）。
     */
    private fun applyTone(source: Bitmap, recipe: EditRecipe): Bitmap {
        val tone = recipe.develop.tone
        val hasEdits =
            tone.exposureEv != 0.0 || tone.contrast != 0.0 || recipe.develop.color.saturation != 0.0
        if (!hasEdits) return source

        val exposureScale = 2.0.pow(tone.exposureEv).toFloat().coerceIn(0.05f, 8f)
        val contrastValue = 1f + tone.contrast.toFloat().coerceIn(-1f, 1f)
        val saturationValue = (1f + recipe.develop.color.saturation.toFloat()).coerceIn(0f, 2f)

        val matrix = ColorMatrix().apply {
            setScale(exposureScale, exposureScale, exposureScale, 1f)
            postConcat(contrastMatrix(contrastValue))
            postConcat(ColorMatrix().apply { setSaturation(saturationValue) })
        }
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun contrastMatrix(c: Float): ColorMatrix {
        val translate = (0.5f - 0.5f * c) * 255f
        return ColorMatrix().apply {
            set(
                floatArrayOf(
                    c, 0f, 0f, 0f, translate,
                    0f, c, 0f, 0f, translate,
                    0f, 0f, c, 0f, translate,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }
    }

    private fun resizeToMaxEdge(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / longest
        val newW = floor(source.width * scale).toInt().coerceAtLeast(1)
        val newH = floor(source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    private fun buildWarnings(recipe: EditRecipe, result: Bitmap, spec: ExportRenderSpec): List<String> {
        val warnings = mutableListOf(
            // 入力未解析警告は必ず付与する（§6.1、実力を偽らない）。
            "8bit-sRGB CPU fallback pipeline at export; input ICC/EXIF orientation/gainmap not applied",
            "export metadata minimized: no EXIF/GPS written; EXIF pass-through not implemented",
        )
        if (spec.format.equals(FORMAT_JPEG, ignoreCase = true)) {
            warnings += "ICC sRGB tag for JPEG not implemented (output assumed sRGB, untagged)"
        }
        val d = recipe.develop
        if (d.curve.rgb != listOf(listOf(0.0, 0.0), listOf(1.0, 1.0)) || d.curve.r.isNotEmpty() ||
            d.curve.g.isNotEmpty() || d.curve.b.isNotEmpty()
        ) {
            warnings += "tone curve not applied at export (not implemented)"
        }
        if (d.color.vibrance != 0.0 || d.color.hsl.isNotEmpty() ||
            d.detail.sharpen != 0.0 || d.detail.lumaNoise != 0.0 || d.detail.chromaNoise != 0.0
        ) {
            warnings += "vibrance/HSL/detail nodes not applied at export (not implemented)"
        }
        val g = recipe.geometry
        if (g.cropNorm != listOf(0.0, 0.0, 1.0, 1.0) || g.angleDeg != 0.0 || g.mirrorX ||
            g.lensProfileId != null || g.perspective != null
        ) {
            warnings += "geometry/lens corrections not applied at export"
        }
        if (recipe.masks.isNotEmpty()) {
            warnings += "local masks not applied at export"
        }
        if (recipe.displayIntent != "auto-sdr-hdr") {
            warnings += "displayIntent handling not implemented at export"
        }
        if (result.hasAlpha()) {
            warnings += "alpha channel present; JPEG output flattens to opaque"
        }
        if (recipe.unknownExtensions.keys.isNotEmpty()) {
            warnings += "unknown recipe extensions preserved in recipe storage only"
        }
        return warnings
    }

    private companion object {
        const val FORMAT_JPEG = "JPEG"
        const val FORMAT_PNG = "PNG"

        /** 安全側ローカル固定値（リモート設定不可・§9.1）。 */
        const val MAX_DECODE_PIXELS = 100_000_000L
    }
}

/** bounds 読み取りの結果（0,0 は取得不可）。 */
data class Bounds(val width: Int, val height: Int)

/** 書き出し入力指定。format は "JPEG" または "PNG"（拡張子ではなく正本 ENUM名）。 */
data class ExportRenderSpec(
    val format: String,
    /** 1..100（PNGでは無視）。 */
    val quality: Int,
    /** 最長辺の上限px。null は原寸。 */
    val maxEdgePx: Int?,
)

enum class Stage { DECODE, PROCESS, ENCODE }

data class ExportRenderOutput(
    val widthPx: Int,
    val heightPx: Int,
    /** 最終byte数。エンコード段では不明なため -1。VERIFYING で staged ファイルから確定する。 */
    val bytes: Long,
    val sha256Hex: String,
    val warnings: List<String>,
)

/**
 * PNG への sRGB チャンク付与（W3C PNG sRGB chunk: rendering intent=Perceptual(0)）。
 * 純Kotlin関数のため JVM 単体テスト対象（feature/export/src/test から public API として検証）。
 *
 * 【明示】ICC正確性の定量検査は未実施（未測定を達成と報告しない）。
 * PNG仕様上 sRGB チャンクは1個まで。既存の sRGB/iCCP/ICCP が有る場合は挿入しない（重複付与禁止）。
 */
object PngMetadata {

    const val PNG_SIGNATURE: Int = 0x89504E47.toInt() // \x89PNG の先頭4バイト
    const val SRGB_INTENT_PERCEPTUAL: Byte = 0

    /**
     * IEND の直前に sRGB チャンク（rendering intent = perceptual）を挿入した日付付きPNGを返す。
     * 入力が有効なPNG（署名・IEND終端・既存 sRGB/iCCPなし）でない場合は null。
     */
    fun insertSrgbChunk(png: ByteArray): ByteArray? {
        if (png.size < 12) return null
        val isPng =
            (png[0].toInt() and 0xFF) == 0x89 &&
                png[1] == 0x50.toByte() && png[2] == 0x4E.toByte() && png[3] == 0x47.toByte() &&
                (png[4] == 0x0D.toByte()) && (png[5] == 0x0A.toByte()) &&
                (png[6] == 0x1A.toByte()) && (png[7] == 0x0A.toByte())
        if (!isPng) return null

        // 既存の sRGB / iCCP / ICCP（Apple準拠の旧abort）がある場合は重複付与しない。
        if (containsChunk(png, "sRGB") || containsChunk(png, "iCCP") || containsChunk(png, "ICCP")) {
            return null
        }

        // IEND 走査、必ず終端に存在すること（末尾12バイト: length(0) type(IEND) CRC）
        val iendAt = findIend(png) ?: return null
        val chunk = srgbChunk()
        val size = png.size + chunk.size
        val out = ByteArray(size)
        // 先頭〜IEND lengthフィールド直前
        System.arraycopy(png, 0, out, 0, iendAt)
        System.arraycopy(chunk, 0, out, iendAt, chunk.size)
        System.arraycopy(png, iendAt, out, iendAt + chunk.size, png.size - iendAt)
        return out
    }

    /** 標準付与する chunk バイト列（Interoperability: sRGB perceptual、CRC付き）。 */
    fun srgbChunk(): ByteArray {
        val data = byteArrayOf(SRGB_INTENT_PERCEPTUAL)
        val chunk = ByteArray(12 + data.size)
        writeInt(chunk, 0, data.size)
        val type = "sRGB".toByteArray(Charsets.US_ASCII)
        System.arraycopy(type, 0, chunk, 4, 4)
        System.arraycopy(data, 0, chunk, 8, data.size)
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        writeInt(chunk, 8 + data.size, crc.value.toInt())
        return chunk
    }

    private fun containsChunk(png: ByteArray, type: String): Boolean {
        val t0 = type.toByteArray(Charsets.US_ASCII)
        var off = 8 // signature
        while (off + 8 <= png.size) {
            if (png[off + 4] == t0[0] && png[off + 5] == t0[1] &&
                png[off + 6] == t0[2] && png[off + 7] == t0[3]
            ) {
                return true
            }
            val len = ((png[off].toInt() and 0xFF) shl 24) or
                ((png[off + 1].toInt() and 0xFF) shl 16) or
                ((png[off + 2].toInt() and 0xFF) shl 8) or
                (png[off + 3].toInt() and 0xFF)
            if (len < 0 || off + 12 + len.toLong() > png.size) return false // 破損（安全側判定）
            off += 12 + len
        }
        return false
    }

    private fun findIend(png: ByteArray): Int? {
        var off = 8
        while (off + 8 <= png.size) {
            val isIend =
                png[off + 4] == 0x49.toByte() && png[off + 5] == 0x45.toByte() &&
                    png[off + 6] == 0x4E.toByte() && png[off + 7] == 0x44.toByte()
            if (isIend) return off
            val len = ((png[off].toInt() and 0xFF) shl 24) or
                ((png[off + 1].toInt() and 0xFF) shl 16) or
                ((png[off + 2].toInt() and 0xFF) shl 8) or
                (png[off + 3].toInt() and 0xFF)
            if (len < 0 || off + 12 + len.toLong() > png.size) return null
            off += 12 + len
        }
        return null
    }

    private fun writeInt(dest: ByteArray, off: Int, v: Int) {
        dest[off] = (v ushr 24).toByte()
        dest[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dest[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dest[off + 3] = (v and 0xFF).toByte()
    }
}

