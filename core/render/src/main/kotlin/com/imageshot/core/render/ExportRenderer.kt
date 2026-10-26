package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * 書き出し用フル解像度再現パス（core:render 内の新規ファイル）。
 * プレビュー（SimpleBitmapPhotoRenderer）と同じノード実装・同じパラメータ（§4 処理パイプライン:
 * 「プレビューと書き出しで同じノード実装とパラメータ、違いは品質設定・タイルスケール・精度のみ」）。
 * develop 適用は RecipeDevelopApplier（純Kotlin共有実装）に委譲する。ジオメトリ
 * （90°回転・反転・プリセット・クロップ）は RecipeGeometry（純Kotlin ピクセル再索引）の
 * 共有実装に委譲する。デコードは SourceDecode（共有パス）を使う。
 *
 * 【明示する制限（未検証を実装済みと報告しない・ADR-014）】
 * - 8bit sRGB 前提の暫定パス。ADR-005 の Linear Rec.2020 float パイプラインではない。
 *   develop 適用は CPU 画素ループ（性能実測は未実施・報告書参照）。
 * - 入力 ICC: SourceDecode（ImageDecoder setTargetColorSpace）で sRGB への出力変換を要求し、
 *   デコード後の実測（bitmap.colorSpace 照合）で検証できた場合のみ「変換済み」の警告文にする
 *   （検証不能では過大記述をしない）。
 * - EXIF向き: SourceDecodePolicy の正本戦略（PLATFORM_APPLIED〔実機確定 2026-10-19:
 *   ImageDecoder が content URI の EXIF orientation を自動適用〕・MANUAL_APPLY は
 *   BitmapFactory フォールバック用の保持実装）に従う。読めない場合は正規化せず警告。
 * - Gainmap (Ultra HDR / ISO 21496-1): ADR-026 stage-1 policy に従う
 *   （[GainmapExportPolicy]。乗算型 tone のみ源 gainmap を未修正で引き継ぎ、
 *   それ以外は platform 側 SDR-only 剝離+ADR-026 確定文言の警告）。
 * - 出力は新規エンコードのみ。
 * - メタデータ: EXIF は whitelist pass-through（[ExifPassThrough]: 撮影情報9タグ・
 *   orientation 常に 1・GPS/location は常に不コピー）— B2。失敗時は書き出しを継続し
 *   警告に落とす（FAILED にしない）。デコード前に源 EXIF を1本の追加ストリームで読む。
 *   ICC sRGB タグ付与：JPEG は MinimalSrgbIcc + JpegIccで APP2 確保（EXIF 書込み後に
 *   再確認・欠落なら挿入〔ExifInterface.saveAttributes() が Skia 埋め込みへ触れるかは
 *   端末実測区分〕。既存/破綻セグメントでは挿入を中止し、その旨を警告する）。
 *   PNG は sRGB チャンクを付与する（EXIF は書かない）。
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

            val decodedResult = SourceDecode.decode(appContext.contentResolver, source, 1)
                ?: return@withContext failure(ErrorCode.UNSUPPORTED_CODEC, "full-res decode failed")
            val decoded = decodedResult.bitmap

            if (shouldCancel()) {
                decoded.recycle()
                return@withContext failure(ErrorCode.EXPORT_INTERRUPTED, "cancelled during decode")
            }
            // PR-23 (ADR-026): 源 gainmap の参照を pixel 再索引処理より先に退避
            // （pixel 転写で bitmap が作り直されても Gainmap オブジェクトは独立して保持される）。
            val sourceGainmap = if (decodedResult.hasGainmap) decoded.gainmap else null
            onStage(Stage.DECODE, 25)

            val processedOutcome = applyDevelop(decoded, recipe, decodedResult.sourceOrientation)
            val processed = processedOutcome.bitmap
            if (processed !== decoded) decoded.recycle()
            onStage(Stage.PROCESS, 45)

            val finalBitmap = if (spec.maxEdgePx != null) resizeToMaxEdge(processed, spec.maxEdgePx) else processed
            if (finalBitmap !== processed) processed.recycle()
            if (shouldCancel()) {
                finalBitmap.recycle()
                return@withContext failure(ErrorCode.EXPORT_INTERRUPTED, "cancelled during resize")
            }
            onStage(Stage.PROCESS, 55)

            // 警告の基礎は recycle() より前に評価する（recycled Bitmap の状態参照を避ける）。
            var warnings = buildWarnings(recipe, finalBitmap, spec, decodedResult, processedOutcome.detailDegraded)

            // PR-23 (ADR-026) policy 分岐: 乗算型のみ pass-through、それ以外は SDR-only。
            // 源 gainmap が無い source（通常 JPEG）には HDR 警告を出さない（無警告=無利得）。
            // decode 結合の正立化変換（platform 自動適用 / 手動正規化）も「画素へ適用された変換」
            // であり、gainmap の同時再索引は存在しないため pass-through 不成立（未実測領域は通さない）。
            if (sourceGainmap != null) {
                val orientationTransformed =
                    decodedResult.sourceOrientation >= 2 || decodedResult.orientationAppliedByUs
                when (GainmapExportPolicy.classify(recipe, spec.format, orientationTransformed)) {
                    GainmapExportPolicy.Mode.PASS_THROUGH -> {
                        // [実測] PR-22 spike: setGainmap(源)+compress はマーカ/諸元を完全保全する。
                        finalBitmap.setGainmap(sourceGainmap)
                        warnings += GainmapExportPolicy.WARNING_PASS_THROUGH
                    }
                    GainmapExportPolicy.Mode.STRIP_TO_SDR -> {
                        // 画素再索引で gainmap は既に剥離済み。
                        // [実測] PR-22 spike: gainmap 無し bitmap の compress は全マーカを消す。
                        warnings += GainmapExportPolicy.WARNING_STRIP_TO_SDR
                        if (orientationTransformed) {
                            warnings +=
                                "HDR: decode 段の orientation 変換（正立化）に gainmap の同時再索引が存在しないため pass-through 不成立（HDR/SDR 整合は未実測・stage-2 課題）"
                        }
                    }
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val digestOut = DigestOutputStream(target, digest)
            val width = finalBitmap.width
            val height = finalBitmap.height

            // EXIF whitelist の元値（decode 済 bitmap とは別の1本のストリームで読む）。
            // 読取不能は null（警告側に落とし、書き出し自体は続行する）。
            val sourceExifValues = ExifPassThrough.readSource(appContext.contentResolver, source)

            val encodeOutcome = encodeAndWrite(finalBitmap, spec, digestOut, onStage, sourceExifValues)
            finalBitmap.recycle()
            val failureEnc = encodeOutcome.failure
            if (failureEnc != null) return@withContext failureEnc
            // エンコード段の追警告（ICC 挿入を中止した等）を成果物の warnings に足す。
            warnings += encodeOutcome.extraWarnings

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

    /** エンコードとターゲットへの書き込み。失敗時は failure を、追加警告は extraWarnings を返す。 */
    private class EncodeOutcome(
        val failure: ImageOpResult.Failure?,
        val extraWarnings: List<String> = emptyList(),
    )

    private fun encodeAndWrite(
        bitmap: Bitmap,
        spec: ExportRenderSpec,
        digestOut: DigestOutputStream,
        onStage: (Stage, Int) -> Unit,
        sourceExifValues: Map<String, String>?,
    ): EncodeOutcome {
        return when (spec.format.uppercase()) {
            FORMAT_JPEG -> {
                // sRGB ICC 埋め込み（W4）のためエンコードをメモリで受け、APP2 挿入後に書き出す
                // （既存の digest/SOS 前提は不変 — digest は最終書出byte列に一致する）。
                onStage(Stage.ENCODE, 70)
                val mem = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, spec.quality, mem)) {
                    return EncodeOutcome(failure(ErrorCode.EXPORT_INTERRUPTED, "JPEG encode failed"))
                }
                var bytes = mem.toByteArray()
                val extra = mutableListOf<String>()

                // ---- B2 EXIF whitelist pass-through（encode 済バイトへ temp 経由で書込）----
                // sourceUnreadable（null）→ 欠落側に倒して続行（警告）。GPS/location は未含。
                if (sourceExifValues == null) {
                    extra += "source EXIF unreadable; EXIF pass-through skipped (orientation not written)"
                } else {
                    val toWrite = ExifPassThrough.attributesToWrite(sourceExifValues)
                    when (val written = ExifPassThrough.applyToJpegBytes(appContext.cacheDir, bytes, toWrite)) {
                        is ExifPassThrough.Outcome.Success -> {
                            bytes = written.bytes
                            val list = toWrite.keys
                            extra += "EXIF whitelist copied: ${list.joinToString(prefix = "[n=${list.size}] ", separator = "/")}" +
                                " (orientation forced to 1; GPS/location not copied)"
                        }
                        is ExifPassThrough.Outcome.Failure -> {
                            bytes = written.fallbackBytes
                            extra += "EXIF write failed after encode; export continued without EXIF copy: ${written.reason}"
                        }
                    }
                }

                // ---- ICC: EXIF 書込み後の最終確認（ExifInterface.saveAttributes() の APP2 影響は端末実測区分）----
                // 欠落なら今ここで挿入する（ICC 存在を出力契約とする・挿入不能では明示警告）。
                if (!JpegIcc.hasApp2IccProfile(bytes)) {
                    val inserted = JpegIcc.insertApp2IccProfile(bytes, MinimalSrgbIcc.profileBytes())
                    if (inserted == null) {
                        extra += "JPEG ICC embedding skipped: no embedded ICC and APP2 insertion not possible (segment malformed)"
                    } else {
                        bytes = inserted
                    }
                }
                digestOut.write(bytes)
                digestOut.flush()
                EncodeOutcome(null, extra)
            }
            FORMAT_PNG -> {
                // sRGB チャンク付与のため、エンコードをメモリで受け、IEND 直前に挿入してから書き出す。
                onStage(Stage.ENCODE, 70)
                val mem = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, mem)) {
                    return EncodeOutcome(failure(ErrorCode.EXPORT_INTERRUPTED, "PNG encode failed"))
                }
                val withSrgb = PngMetadata.insertSrgbChunk(mem.toByteArray())
                digestOut.write(withSrgb)
                digestOut.flush()
                EncodeOutcome(null, listOf("export metadata minimized: no EXIF written (PNG output); GPS/location never copied"))
            }
            else -> EncodeOutcome(failure(ErrorCode.UNSUPPORTED_CODEC, "unsupported export format: ${spec.format}"))
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
     * 正規化 + ジオメトリ + develop（プレビューレンダラーと同一ノード・同一パラメータ、フル解像度で適用）。
     * パイプライン順: decode（SourceDecode・sRGBターゲット）→ EXIF向き正規化
     * （SourceOrientation.normalize: orientation 1..8、不明（0）は恒等に落とす・警告側に通知）→
     * ジオメトリ（RecipeGeometry.applyIfNeeded: 回転→反転→クロップ）→
     * develop（RecipeDevelopApplier.applyToPixels 共有実装）→
     * detail（RecipeDetailApplier.applyToPixels: PR-28c・develop 後 resize 前）→ resize。
     * 【明示】プレビューはサンプリング後の同一変換を適用するため、クロップは正規化座標で
     * 一致するが、画素グリッドの丸め差（±1px 相当）は原理的に残る。
     * 光学補正・局所マスク・chromaNR は未適用。自由角度/ディテール（sharpen+lumaNR）は適用。
     * 既定値でない場合は buildWarnings 経由で必ず警告する（ADR-014）。
     */
    private data class ProcessedOutcome(val bitmap: Bitmap, val detailDegraded: Boolean)

    private fun applyDevelop(source: Bitmap, recipe: EditRecipe, sourceOrientation: Int): ProcessedOutcome {
        val width = source.width
        val height = source.height
        var pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val plane0 = RecipeGeometry.PixPlane(pixels, width, height)
        // EXIF向きの正規化をユーザー自身のジオメトリの「前」に行う（合成順は決定的・正規化→ユーザー）。
        // 【実機確定】PLATFORM_APPLIED（正本）では ImageDecoder が既に適用済みのため
        // 自前転置を行わない（二重回転防止）。MANUAL 戦略（BitmapFactory フォールバック）時のみ適用。
        val normalized = if (SourceDecodePolicy.exifStrategy == ExifStrategy.MANUAL_APPLY) {
            SourceOrientation.normalize(plane0, sourceOrientation)
        } else {
            plane0
        }
        val g = recipe.geometry
        val plane = RecipeGeometry.applyIfNeeded(
            normalized,
            g.angleDeg,
            g.mirrorX,
            g.mirrorY,
            g.cropNorm,
        )
        pixels = plane.pixels
        RecipeDevelopApplier.applyToPixels(pixels, recipe.develop)
        // PR-28c: 空間 stage（develop 後・resize 前・preview と同一関数/同一順序）。
        val detail = RecipeDetailApplier.applyToPixels(pixels, plane.width, plane.height, recipe.develop.detail)
        val out = Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, plane.width, 0, 0, plane.width, plane.height)
        return ProcessedOutcome(out, detail.degraded)
    }

    private fun resizeToMaxEdge(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdgePx) return source
        val scale = maxEdgePx.toFloat() / longest
        val newW = floor(source.width * scale).toInt().coerceAtLeast(1)
        val newH = floor(source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }

    /** 実装済み範囲と未検証の境目を示す警告の正本（過大記述をしない・§6.1/ADR-014）。 */
    private fun buildWarnings(
        recipe: EditRecipe,
        result: Bitmap,
        spec: ExportRenderSpec,
        decode: SourceDecode.Result,
        detailDegraded: Boolean,
    ): List<String> {
        val warnings = mutableListOf(
            // 8bit sRGB CPU パイプライン（プレビューと同一ノード実装）は恒常降格として明示（§6.1/ADR-014）。
            "8bit-sRGB CPU fallback pipeline at export (same node implementation as preview; ADR-005 float pipeline not implemented)",
            // 入力 ICC（W1）: 実測検証できた場合のみ「変換済み」と述べる（それ以外は検証不能と明示）。
            if (decode.colorSpaceConverted) {
                "input ICC converted to working sRGB by platform decoder (ImageDecoder setTargetColorSpace)"
            } else {
                "input ICC sRGB conversion could not be verified; treated as sRGB"
            },
            exifWarning(decode),
            // Gainmap (Ultra HDR) 警告と EXIF pass-through 警告は render()/encodeAndWrite 側で
            // 源画像と policy 分岐に応じて付与する（ADR-026・B2。無利益な源には HDR 警告を出さない）。
        )
        if (decode.usedFallback) warnings += SourceDecode.FALLBACK_BITMAP_FACTORY_WARNING
        // この段階で読まないノードは、既定値でないときのみ警告する（develop 適用ノードを偽警告にしない）。
        warnings += RecipeDevelopApplier.unappliedNodeWarnings(recipe)
        if (detailDegraded) warnings += RecipeDetailApplier.DEGRADED_WARNING
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

    /**
     * EXIF 向きの扱いを戦略別に明示する行。
     * - 読めない（0）: 正規化せず（不明は不明のまま）。
     * - 手動正規化を実行: orientation を添えて明示。
     * - PLATFORM_APPLIED: 実機検証未了であることを付記（検証前の過大記述をしない）。
     * - 手動戦略で 1（normal）: 変換不要を明示。
     */
    private fun exifWarning(decode: SourceDecode.Result): String = when {
        decode.sourceOrientation == 0 -> "source EXIF orientation unreadable: not normalized"
        decode.orientationAppliedByUs ->
            "EXIF orientation normalized (manual pixel transform, orientation=${decode.sourceOrientation})"
        SourceDecodePolicy.exifStrategy == ExifStrategy.PLATFORM_APPLIED ->
            "EXIF orientation delegated to platform decoder (ImageDecoder; device-verified 2026-10-19, TB710FU API36), " +
                "orientation=${decode.sourceOrientation}"
        else -> "EXIF orientation 1 (normal): no orientation transform needed"
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

