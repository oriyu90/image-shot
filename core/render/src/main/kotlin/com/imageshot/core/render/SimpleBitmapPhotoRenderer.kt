package com.imageshot.core.render

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Bitmap 基盤の暫定レンダラー（Kotlin 実装。NDK代替は ADR-002 の native モジュールで行う）。
 *
 * M1前半で実装した調整ノード（8bit sRGB 一時パイプライン）:
 *   露出EV / コントラスト / ハイライト / シャドウ / 白レベル / 黒レベル /
 *   彩度 / 自然な彩度 / 温度（as-shot 相対）/ 色合い（tint・RecipeToneCurve.tintGains 近似）。
 *   ノードは RecipeDevelopApplier（純Kotlin共有実装）経由で RecipeToneCurve の式設計に
 *   委譲する（画質未検証・golden 未実施。書き出し ExportBitmapRenderer も同一の共有実装）。
 *
 * 明示する制約（M1雛形であり、以下を「実装済み」とは報告しない）:
 * - 8bit sRGB 前提の暫定パス。ADR-005 の Linear Rec.2020 float パイプラインではない。
 * - 入力 ICC: SourceDecode（ImageDecoder setTargetColorSpace）で sRGB 出力変換を要求。
 *   実測検証で真偽を分けて警告文にする（恒降格ではない）。
 * - EXIF向き: SourceOrientation による手動正規化（SourceDecodePolicy の戦略に従う）。
 *   読めない場合は正規化せず警告する。
 * - ディテール/局所マスクは未適用。無通知に捨てないため、結果 warnings に降格警告を
 *   保つ（ADR-014）。
 * - P4 で追加されたノード: master カーブ / HSL 8系統 / ジオメトリ（90°回転・反転・
 *   プリセット・クロップ）。R/G/B チャンネルカーブ・自由角度・光学補正は未適用。
 * - 巨大Bitmapの多重保持を避ける: 結果は RenderBitmapStore（文書ごと直近3世代）に置き、
 *   入力ソースは即 recycle、並列生成は1に制限（§4.3）。
 * - 処理は常にワーカースレッド（Dispatchers.Default / IO）。UI スレッドで実行しない。
 */
class SimpleBitmapPhotoRenderer(
    context: Context,
    /** assetId に関連する原本URI を解決する関数（AppContainer が AssetDao から供給する）。 */
    private val sourceUriResolver: suspend (assetId: String) -> Uri?,
    /** 生成結果の所有者。UI へは bitmapHandle 経由で参照させる。 */
    private val bitmaps: RenderBitmapStore = RenderBitmapStore(),
) : PhotoRenderer {

    private val appContext = context.applicationContext

    /** docId → 最新 RenderResult。古い generationId は投げ捨て（ADR-011）。 */
    private val latest = ConcurrentHashMap<String, RenderResult>()

    /** 無効化済みの最後の generationId。 */
    private val invalidatedFrom = ConcurrentHashMap<String, Long>()

    private val renderGate = Semaphore(1)

    /** UI が結果を参照するためのストア（明示dispose契約は所有者側で）。 */
    val renderedBitmaps: RenderBitmapStore get() = bitmaps

    override suspend fun render(
        request: RenderRequest,
        recipe: EditRecipe,
    ): ImageOpResult<RenderResult> = withContext(Dispatchers.Default) {
        val invalidated = invalidatedFrom[request.documentId]
        if (invalidated != null && request.generationId <= invalidated) {
            return@withContext staleGenerationFailure(request)
        }

        renderGate.acquire()
        try {
            // ゲート待ちの間に無効化された場合も取り直す（二重チェック）。
            val invalidated2 = invalidatedFrom[request.documentId]
            if (invalidated2 != null && request.generationId <= invalidated2) {
                return@withContext staleGenerationFailure(request)
            }

            // URI 解決はレシピの source.assetId を正本とする（documentId は後方互換のフォールバック）。
            val uri = sourceUriResolver.invoke(recipe.source.assetId)
                ?: sourceUriResolver.invoke(recipe.documentId)
            if (uri == null) {
                return@withContext ImageOpResult.Failure(
                    ErrorCode.URI_REVOKED,
                    "no source uri for document ${recipe.documentId}",
                )
            }

            val decoded = decodePreviewSource(uri, request)
                ?: return@withContext ImageOpResult.Failure(
                    ErrorCode.UNSUPPORTED_CODEC,
                    "preview decode failed",
                )
            val source = decoded.bitmap

            val output = applyDevelop(source, recipe, decoded.sourceOrientation)
            source.recycle()

            val bitmapKey = RenderBitmapStore.keyFor(request.documentId, request.generationId)
            bitmaps.put(bitmapKey, output)

            val result = RenderResult(
                bitmapHandle = bitmapKey,
                // 作業上の色空間表記（8bit sRGB）。入力 ICC の解像は SourceDecode の sRGB ターゲット
                // 変換に依存し、実測検証の結果を warnings で明示する（§6.1・実力を偽らない）。
                actualSpace = "srgb-d65",
                transfer = "iec61966-2-1",
                gamut = "srgb",
                peakNits = null, // HDR: 実表示に成功したときだけ有効にする（ADR-006）
                histogram = emptyList(),
                revision = request.revision,
                generationId = request.generationId,
                warnings = renderWarnings(decoded),
            )
            latest[request.documentId] = result
            ImageOpResult.Success(result)
        } catch (oom: OutOfMemoryError) {
            // §4.3: メモリ超過時は低品質プレビューへの降格は上位VMが判定する。ここでは失敗を明示。
            ImageOpResult.Failure(ErrorCode.OUT_OF_MEMORY, oom.message)
        } finally {
            renderGate.release()
        }
    }

    private fun staleGenerationFailure(request: RenderRequest): ImageOpResult.Failure =
        ImageOpResult.Failure(
            ErrorCode.GPU_CONTEXT_LOST,
            "stale generationId=${request.generationId} dropped by renderer",
        )

    override fun invalidate(docId: String, fromRevision: Long) {
        invalidatedFrom[docId] = fromRevision
        latest.remove(docId)
    }

    /** 文書の全結果バッファを解放（所有権契約の明示呼出し点）。 */
    override suspend fun close(docId: String) {
        latest.remove(docId)
        invalidatedFrom.remove(docId)
        bitmaps.close(docId)
    }

    /**
     * 共有デコードパス（SourceDecode）経由の簡易ピラミッド:
     * requestedPx を下限に合わせ sampleSize で読む（§4.3 全体プレビューの簡易版）。
     * 色空間（sRGB ターゲット）と EXIF orientation の読み取りも SourceDecode に集約する。
     */
    private suspend fun decodePreviewSource(
        uri: Uri,
        request: RenderRequest,
    ): SourceDecode.Result? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)!!.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            val sample = maxSampleFor(bounds.outWidth, request.requestedPx)
            SourceDecode.decode(appContext.contentResolver, uri, sample)
        }.getOrNull()
    }

    private fun maxSampleFor(width: Int, requestedPx: Int): Int {
        if (width <= 0 || requestedPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= requestedPx) sample *= 2
        return sample
    }

    /**
     * 正規化 + ジオメトリ + develop 適用（8bit sRGB 暫定）。
     * 書き出し（ExportBitmapRenderer）と同一ノード実装へ委譲する:
     *   SourceOrientation.normalize（EXIF 1..8 正規化・ユーザージオメトリの前に適用）
     *   → RecipeGeometry.apply（回転→反転→クロップ、純Kotlin ピクセル再索引）
     *   → RecipeDevelopApplier.applyToPixels（RecipeToneCurve/RecipeCurve/RecipeHsl 正本）。
     * 未実装ノードの有効値が入り込んでも無通知適用はしない（ADR-014、warnings 由来）。
     * ディテール/光学補正/局所マスクはこの段階では読まない。
     */
    private fun applyDevelop(source: Bitmap, recipe: EditRecipe, sourceOrientation: Int): Bitmap {
        val width = source.width
        val height = source.height
        var pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // EXIF向きの正規化をユーザー自身のジオメトリの「前」に行う（合成順は決定的）。
        // 【実機確定】PLATFORM_APPLIED（正本）では ImageDecoder が既に適用済みのため
        // 自前転置を行わない（二重回転防止）。MANUAL 戦略（BitmapFactory フォールバック）時のみ適用。
        val normalized = if (SourceDecodePolicy.exifStrategy == ExifStrategy.MANUAL_APPLY) {
            SourceOrientation.normalize(
                RecipeGeometry.PixPlane(pixels, width, height),
                sourceOrientation,
            )
        } else {
            RecipeGeometry.PixPlane(pixels, width, height)
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

        val outW = plane.width
        val outH = plane.height
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    companion object {
        /**
         * 恒常降格の正本一覧（ADR-014: 無通知縮降の禁止）。
         * 入力解像（ICC sRGB 変換・EXIF orientation）は実測により真偽が変わるため、
         * 条件付き文言は [renderWarnings] で組み立てる（本配列は常に真の恒常降格のみ）。
         * 縮減語が弱まっていないこと（8bit パイプライン・未実装ノード等を残す）。
         */
        val RENDER_WARNINGS = arrayOf(
            "8bit-sRGB CPU fallback pipeline (ADR-005 float/tile pipeline not implemented)",
            "unimplemented nodes ignored: detail, vignette, local masks (curve/HSL/geometry are applied)",
            "tone/curve/HSL node formulas are design-stage; visual quality unverified (no golden tests)",
        )

        /**
         * デコード実測を反映した警告リスト（恒常降格 + 入力解像の条件文言）。
         * - ICC: sRGB 変換を検証できた場合のみ「変換済み」。検証不能では想定を明示する。
         * - EXIF orientation: 戦略別の明示（読めない場合の未正規化も明示）。
         * - BitmapFactory へのフォールバックが発生した場合の追加警告。
         */
        fun renderWarnings(decode: SourceDecode.Result?): List<String> {
            val warnings = mutableListOf<String>()
            warnings += RENDER_WARNINGS
            if (decode != null && decode.colorSpaceConverted) {
                warnings += "input ICC converted to working sRGB by platform decoder (ImageDecoder setTargetColorSpace)"
            } else {
                warnings += "input ICC sRGB conversion could not be verified; treated as sRGB"
            }
            warnings +=
                when {
                    decode == null || decode.sourceOrientation == 0 ->
                        "source EXIF orientation unreadable: not normalized"
                    decode.orientationAppliedByUs ->
                        "EXIF orientation normalized (manual pixel transform, orientation=${decode.sourceOrientation})"
                    SourceDecodePolicy.exifStrategy == ExifStrategy.PLATFORM_APPLIED ->
                        "EXIF orientation delegated to platform decoder (ImageDecoder; device-verified 2026-10-19, TB710FU API36), " +
                            "orientation=${decode.sourceOrientation}"
                    else -> "EXIF orientation 1 (normal): no orientation transform needed"
                }
            if (decode?.usedFallback == true) warnings += SourceDecode.FALLBACK_BITMAP_FACTORY_WARNING
            return warnings
        }
    }
}
