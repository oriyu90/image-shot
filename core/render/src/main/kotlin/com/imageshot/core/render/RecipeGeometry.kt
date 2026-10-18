package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import kotlin.math.roundToInt

/**
 * ジオメトリ変換（90°回転 / 水平・垂直反転 / クロップ）のピクセル平面上の唯一の正本。
 *
 * - 純Kotlin（Android に依存しない）。プレビュー/書き出しは Bitmap を getPixels/setPixels で
 *   IntArray 化して本モジュールに委譲する（develop と同じ共有方式。§3.2 L123 同値契約）。
 * - 変換順（正本・固定）: 回転 → 反転 → クロップ。cropNorm は回転・反転後の
 *   「正準向き」での正規化座標（[EditRecipe.GeometryParams] の KDoc 参照）。
 * - 回転90°を Matrix（filter=true）で行う案は採用しない: ピクセル再索引は無劣化・
 *   予測可能であり、フィルタ設計の差分（プレビュー/書き出し）を原理的に作らない。
 *   （補正: 90°整数回転で filter=true の Matrix 転送は劣化しないが、実装単位を純Kotlinに
 *   統一する方がテスト可能性が高い — この選択をここで明示する。）
 */
object RecipeGeometry {

    /** ピクセル平面（ARGB_8888 パック形式）。pixels は width*height の行優先。 */
    data class PixPlane(val pixels: IntArray, val width: Int, val height: Int)

    /** 回転 quarter = ((angleDeg / 90) mod 4)（正規化済み前提）。0..3 の CW 刻み。 */
    fun quarterTurns(angleDeg: Double): Int {
        if (!EditNodeSpecs.isAllowedAngle(angleDeg)) return 0
        return ((Math.round(angleDeg / 90.0).toInt() % 4) + 4) % 4
    }

    /** 回転 90°CW × [times] 回。times は負可（mod 4）。 */
    fun rotateCw90(src: PixPlane, times: Int): PixPlane {
        var plane = src
        var n = ((times % 4) + 4) % 4
        while (n-- > 0) plane = rotateCw90Once(plane)
        return plane
    }

    /** 1回の 90°CW 回転（dst(x_d,y_d) = src(x=y_d, y=h−1−x_d)、dst 寸法 = (h, w)）。 */
    private fun rotateCw90Once(src: PixPlane): PixPlane {
        val w = src.width
        val h = src.height
        val dstW = h
        val dstH = w
        val out = IntArray(src.pixels.size)
        for (yd in 0 until dstH) {
            val rowBase = yd * dstW
            for (xd in 0 until dstW) {
                out[rowBase + xd] = src.pixels[(h - 1 - xd) * w + yd]
            }
        }
        return PixPlane(out, dstW, dstH)
    }

    /** 水平反転（左右）。 */
    fun flipHorizontal(src: PixPlane): PixPlane {
        val w = src.width
        val h = src.height
        val out = IntArray(src.pixels.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                out[base + x] = src.pixels[base + (w - 1 - x)]
            }
        }
        return PixPlane(out, w, h)
    }

    /** 垂直反転（上下）。 */
    fun flipVertical(src: PixPlane): PixPlane {
        val w = src.width
        val h = src.height
        val out = IntArray(src.pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[y * w + x] = src.pixels[(h - 1 - y) * w + x]
            }
        }
        return PixPlane(out, w, h)
    }

    /**
     * クロップ。cropNorm = (x0,y0,x1,y1) 正規化座標。端は丸め（最外周は画像内にクランプ）、
     * 結果は最小 1×1 を保証する。
     */
    fun crop(src: PixPlane, cropNorm: List<Double>): PixPlane {
        if (cropNorm.size != 4) return src
        val w = src.width
        val h = src.height
        val left = (cropNorm[0] * w).roundToInt().coerceIn(0, w - 1)
        val top = (cropNorm[1] * h).roundToInt().coerceIn(0, h - 1)
        val right = (cropNorm[2] * w).roundToInt().coerceIn(left + 1, w)
        val bottom = (cropNorm[3] * h).roundToInt().coerceIn(top + 1, h)
        val dstW = right - left
        val dstH = bottom - top
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            System.arraycopy(src.pixels, (top + y) * w + left, out, y * dstW, dstW)
        }
        return PixPlane(out, dstW, dstH)
    }

    /**
     * ジオメトリパラメータの全変換を順に適用する（回転 → 反転 → クロップ）。
     * 正規化は [EditNodeSpecs.normalizeGeometry] 済みの入力を期待する（不正は最短で恒等に落とす）。
     */
    fun apply(src: PixPlane, angleDeg: Double, mirrorX: Boolean, mirrorY: Boolean, cropNorm: List<Double>): PixPlane {
        var plane = rotateCw90(src, quarterTurns(angleDeg))
        if (mirrorX) plane = flipHorizontal(plane)
        if (mirrorY) plane = flipVertical(plane)
        plane = crop(plane, cropNorm)
        return plane
    }

    /** ジオメトリ中立なら無変換（同一 plane 参照）で返す短絡。 */
    fun applyIfNeeded(
        src: PixPlane,
        angleDeg: Double,
        mirrorX: Boolean,
        mirrorY: Boolean,
        cropNorm: List<Double>,
    ): PixPlane {
        return if (EditNodeSpecs.isNeutralGeometry(
                com.imageshot.core.model.EditRecipe.GeometryParams(
                    cropNorm = cropNorm,
                    angleDeg = angleDeg,
                    mirrorX = mirrorX,
                    mirrorY = mirrorY,
                ),
            )
        ) {
            src
        } else {
            apply(src, angleDeg, mirrorX, mirrorY, cropNorm)
        }
    }
}
