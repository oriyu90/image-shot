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
 *   彩度 / 自然な彩度 / 温度（as-shot 相対）/ 色合い（近似）。
 *   ノードは RecipeDevelopApplier（純Kotlin共有実装）経由で RecipeToneCurve の式設計に
 *   委譲する（画質未検証・golden 未実施。書き出し ExportBitmapRenderer も同一の共有実装）。
 *
 * 明示する制約（M1雛形であり、以下を「実装済み」とは報告しない）:
 * - 8bit sRGB 前提の暫定パス。ADR-005 の Linear Rec.2020 float パイプラインではない。
 * - 入力ICC/EXIF向き/ディテール/局所マスクは未適用。無通知に捨てないため、
 *   結果 warnings に常に降格警告を含める（ADR-014）。
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

            val source = decodePreviewSource(uri, request)
                ?: return@withContext ImageOpResult.Failure(
                    ErrorCode.UNSUPPORTED_CODEC,
                    "preview decode failed",
                )

            val output = applyDevelop(source, recipe)
            source.recycle()

            val bitmapKey = RenderBitmapStore.keyFor(request.documentId, request.generationId)
            bitmaps.put(bitmapKey, output)

            val result = RenderResult(
                bitmapHandle = bitmapKey,
                // 暫定: 入力ICCを無視した扱い。実力を偽らず、警告として必ず明示する（§6.1）。
                actualSpace = "srgb-d65",
                transfer = "iec61966-2-1",
                gamut = "srgb",
                peakNits = null, // HDR: 実表示に成功したときだけ有効にする（ADR-006）
                histogram = emptyList(),
                revision = request.revision,
                generationId = request.generationId,
                warnings = RENDER_WARNINGS.toList(),
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

    /** 簡易ピラミッド: requestedPx を下限に合わせ inSampleSize で読む（§4.3 全体プレビューの簡易版）。 */
    private suspend fun decodePreviewSource(uri: Uri, request: RenderRequest): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)!!.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = maxSampleFor(bounds.outWidth, request.requestedPx)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                appContext.contentResolver.openInputStream(uri)!!.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()
        }

    private fun maxSampleFor(width: Int, requestedPx: Int): Int {
        if (width <= 0 || requestedPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= requestedPx) sample *= 2
        return sample
    }

    /**
     * ジオメトリ + develop 適用（8bit sRGB 暫定）。
     * 書き出し（ExportBitmapRenderer）と同一ノード実装へ委譲する:
     *   RecipeGeometry.apply（回転→反転→クロップ、純Kotlin ピクセル再索引）
     *   → RecipeDevelopApplier.applyToPixels（RecipeToneCurve/RecipeCurve/RecipeHsl 正本）。
     * 未実装ノードの有効値が入り込んでも無通知適用はしない（ADR-014、warnings 由来）。
     * ディテール/光学補正/局所マスクはこの段階では読まない。
     */
    private fun applyDevelop(source: Bitmap, recipe: EditRecipe): Bitmap {
        val width = source.width
        val height = source.height
        var pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val g = recipe.geometry
        val plane = RecipeGeometry.applyIfNeeded(
            RecipeGeometry.PixPlane(pixels, width, height),
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
        /** 機能縮退の一覧（ADR-014: 無通知縮退の禁止）。常時提示する降格警告。 */
        val RENDER_WARNINGS = arrayOf(
            "8bit-sRGB CPU fallback pipeline (ADR-005 float/tile pipeline not implemented)",
            "input ICC / EXIF orientation not yet applied",
            "unimplemented nodes ignored: detail, vignette, local masks (curve/HSL/geometry are applied)",
            "tone/curve/HSL node formulas are design-stage; visual quality unverified (no golden tests)",
        )
    }
}
