package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ジオメトリ変換（任意角回転 / 90°回転 / 水平・垂直反転 / クロップ）のピクセル平面上の唯一の正本。
 *
 * - 純Kotlin（Android に依存しない）。プレビュー/書き出しは Bitmap を getPixels/setPixels で
 *   IntArray 化して本モジュールに委譲する（develop と同じ共有方式。§3.2 L123 同値契約）。
 * - 変換順（正本・固定）: 回転 → 反転 → クロップ。cropNorm は回転・反転後の
 *   「正準向き」での正規化座標（[EditRecipe.GeometryParams] の KDoc 参照）。
 * - 回転は 2 経路（PR-28b）:
 *   1) 90°刻み（[EditNodeSpecs.isQuarterTurnAngle]）: ピクセル再索引（無劣化・寸法入替）。
 *   2) 任意角: 中心回転+バイリニア逆写像。出力キャンバスは回転後矩形の外接矩形
 *      （[outputSize]）で、被覆しない画素は [EDGE_FILL_ARGB] の黒で塗る
 *      （ユーザー確定仕様 2026-10-26・プレビュー/書き出しで同一関数）。
 * - 回転90°を Matrix（filter=true）で行う案は採用しない: ピクセル再索引は無劣化・
 *   予測可能であり、フィルタ設計の差分（プレビュー/書き出し）を原理的に作らない。
 *   任意角も Matrix ではなく本モジュールの純関数で行い、同じ理由で決定論を優先する。
 */
object RecipeGeometry {

    /** ピクセル平面（ARGB_8888 パック形式）。pixels は width*height の行優先。 */
    data class PixPlane(val pixels: IntArray, val width: Int, val height: Int)

    /** 任意角回転で画像の被覆外になる画素の塗り（不透明黒・確定仕様）。 */
    const val EDGE_FILL_ARGB: Int = 0xFF000000.toInt()

    /** 回転 quarter = ((angleDeg / 90) mod 4)（正規化済み前提）。0..3 の CW 刻み。 */
    fun quarterTurns(angleDeg: Double): Int {
        if (!EditNodeSpecs.isQuarterTurnAngle(angleDeg)) return 0
        val canonical = EditNodeSpecs.canonicalAngleDeg(angleDeg) ?: return 0
        return ((Math.round(canonical / 90.0).toInt() % 4) + 4) % 4
    }

    /**
     * ジオメトリ回転のみを適用した後のキャンバス寸法（回転後矩形の外接矩形）。
     * 90°刻みは寸法入替（無劣化経路と一致）、任意角は |w·cosθ|+|h·sinθ| の切り上げ。
     */
    fun outputSize(width: Int, height: Int, angleDeg: Double): Pair<Int, Int> {
        val canonical = EditNodeSpecs.canonicalAngleDeg(angleDeg)
            ?: return width to height
        if (EditNodeSpecs.isQuarterTurnAngle(canonical)) {
            return if (quarterTurns(canonical) % 2 == 0) width to height else height to width
        }
        val rad = Math.toRadians(canonical)
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        val outW = ceil(width * c + height * s - 1.0e-9).toInt().coerceAtLeast(1)
        val outH = ceil(width * s + height * c - 1.0e-9).toInt().coerceAtLeast(1)
        return outW to outH
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

    /**
     * 任意角の中心回転（時計回り・度）。出力は [outputSize] の外接矩形で、
     * 逆写像（dst→src）のバイリニア標本化を行う。被覆外は [EDGE_FILL_ARGB]。
     *
     * 座標系: 画面座標（x 右・y 下）。CW 回転の順写像は
     *   d = R(θ)·s, R(θ)=[[cos,−sin],[sin,cos]]
     * なので逆写像は s = R(−θ)·d = [[cos,sin],[−sin,cos]]·d。
     * 画素中心は +0.5、標本位置は画素中心基準（−0.5）で扱う。
     */
    fun rotateArbitrary(src: PixPlane, angleDeg: Double): PixPlane {
        val canonical = EditNodeSpecs.canonicalAngleDeg(angleDeg) ?: return src
        if (EditNodeSpecs.isQuarterTurnAngle(canonical)) {
            return rotateCw90(src, quarterTurns(canonical))
        }
        val rad = Math.toRadians(canonical)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val (dstW, dstH) = outputSize(src.width, src.height, canonical)
        val out = IntArray(dstW * dstH) { EDGE_FILL_ARGB }
        val w = src.width
        val h = src.height
        val srcCx = w / 2.0
        val srcCy = h / 2.0
        val dstCx = dstW / 2.0
        val dstCy = dstH / 2.0
        val maxX = w - 1.0
        val maxY = h - 1.0
        for (yd in 0 until dstH) {
            val dy = yd + 0.5 - dstCy
            val rowBase = yd * dstW
            for (xd in 0 until dstW) {
                val dx = xd + 0.5 - dstCx
                val sx = cosA * dx + sinA * dy + srcCx - 0.5
                val sy = -sinA * dx + cosA * dy + srcCy - 0.5
                if (sx < 0.0 || sy < 0.0 || sx > maxX || sy > maxY) continue
                out[rowBase + xd] = sampleBilinear(src.pixels, w, h, sx, sy)
            }
        }
        return PixPlane(out, dstW, dstH)
    }

    /** バイリニア標本化（座標は画素中心基準・範囲内前提。端のタップはクランプ）。 */
    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, x: Double, y: Double): Int {
        val x0 = floor(x).toInt().coerceIn(0, w - 1)
        val y0 = floor(y).toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = (x - x0).coerceIn(0.0, 1.0).toFloat()
        val fy = (y - y0).coerceIn(0.0, 1.0).toFloat()
        val p00 = pixels[y0 * w + x0]
        val p10 = pixels[y0 * w + x1]
        val p01 = pixels[y1 * w + x0]
        val p11 = pixels[y1 * w + x1]
        var result = 0
        var shift = 24
        while (shift >= 0) {
            val c00 = (p00 ushr shift) and 0xFF
            val c10 = (p10 ushr shift) and 0xFF
            val c01 = (p01 ushr shift) and 0xFF
            val c11 = (p11 ushr shift) and 0xFF
            val top = c00 + (c10 - c00) * fx
            val bottom = c01 + (c11 - c01) * fx
            val v = (top + (bottom - top) * fy + 0.5f).toInt().coerceIn(0, 255)
            result = result or (v shl shift)
            shift -= 8
        }
        return result
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
     * 結果は最小 1×1 を保証する。プレビュー/書き出しで同一関数のため、寸法差に由来する
     * 丸め差（±1px 相当）のみが残る（ExportRenderer KDoc の既存明示と同じ）。
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
     * 正規化は [EditNodeSpecs.normalizeGeometry] 済みの入力を期待する
     * （不正・非有限の角度は最短で回転なしに落とす。反転/クロップは値のまま）。
     */
    fun apply(src: PixPlane, angleDeg: Double, mirrorX: Boolean, mirrorY: Boolean, cropNorm: List<Double>): PixPlane {
        val canonical = EditNodeSpecs.canonicalAngleDeg(angleDeg)
        var plane = when {
            canonical == null -> src
            EditNodeSpecs.isQuarterTurnAngle(canonical) -> rotateCw90(src, quarterTurns(canonical))
            else -> rotateArbitrary(src, canonical)
        }
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
