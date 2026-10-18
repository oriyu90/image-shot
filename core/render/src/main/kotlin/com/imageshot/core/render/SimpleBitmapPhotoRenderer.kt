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
import com.imageshot.core.model.RenderRequest
import com.imageshot.core.model.RenderResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Bitmap 基盤の暫定レンダラー（Kotlin 実装。NDK代替は ADR-002 の native モジュールで行う）。
 *
 * 明示する制約（M1雛形であり、以下を「実装済み」とは報告しない）:
 * - 8bit sRGB 前提の暫定パス。ADR-005 の Linear Rec.2020 float パイプラインではない。
 *   TODO(ADR確認): float/タイル/RAW現像は native/image-core に置く。本実装はUI配線と同値契約の土台。
 * - 入力ICC/EXIF向きは未解決: 無通知に捨てないため、結果 warnings に常に降格警告を含める。
 * - 巨大Bitmapの多重保持を避ける: 文書ごとに最新1結果だけ保持し、並列生成を1に制限（§4.3）。
 */
class SimpleBitmapPhotoRenderer(
    context: Context,
    /** documentId に関連する原本URI を解決する関数（AppContainer が AssetDao から供給する）。 */
    private val sourceUriResolver: suspend (documentId: String) -> Uri?,
) : PhotoRenderer {

    private val appContext = context.applicationContext

    /** docId → 最新結果。古い generationId は投げ捨て（ADR-011）。 */
    private val latest = ConcurrentHashMap<String, RenderResult>()

    /** 無効化済みの最後の generationId。 */
    private val invalidatedFrom = ConcurrentHashMap<String, Long>()

    private val renderGate = Semaphore(1)

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

            val uri = sourceUriResolver.invoke(recipe.documentId) ?: recipe.source.assetId.let {
                sourceUriResolver.invoke(it)
            }
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

            // 中間Bitmapは保持せず、out を生成したら入力は即 recycle（巨大ビットマップ多重保持禁止）。
            val output = applyTone(source, recipe)
            source.recycle()

            val result = RenderResult(
                bitmapHandle = request.generationId.toString(),
                // 暫定: 入力ICCを無視した扱い。実力を偽らず、警告として必ず明示する（§6.1）。
                actualSpace = "srgb-d65",
                transfer = "iec61966-2-1",
                gamut = "srgb",
                peakNits = null, // HDR: 実表示に成功したときだけ有効にする（ADR-006）
                histogram = emptyList(),
                revision = request.revision,
                generationId = request.generationId,
                warnings = listOf(
                    "8bit-sRGB CPU fallback pipeline; input ICC/EXIF orientation not yet applied",
                ),
            )
            latest[request.documentId] = result
            ImageOpResult.Success(result)
        } catch (oom: OutOfMemoryError) {
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

    override suspend fun close(docId: String) {
        latest.remove(docId)
        invalidatedFrom.remove(docId)
    }

    /** 簡易ピラミッド: requestedPx を下限に合わせ inSampleSize で読む（§4.3 全体プレビューの簡易版）。 */
    private suspend fun decodePreviewSource(uri: Uri, request: RenderRequest): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val sample = maxSampleFor(bounds.outWidth, request.requestedPx)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                appContext.contentResolver.openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, opts)
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
     * トーン/色調整の暫定適用（露出=2^EV乗算、コントラスト、彩度）。
     * 【明示】カーブ・HSL・局所マスクは未適用。スライダー値は適用済みとして偽らない（ADR-014）。
     */
    private fun applyTone(source: Bitmap, recipe: EditRecipe): Bitmap {
        val tone = recipe.develop.tone
        // TODO(ADR確認): スライダーの正規化範囲（±5EV等）の正本は仕様書§5.1スキーマで固定のこと。
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
}
