package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 局所マスクの重み計算（PR-29a・純 Kotlin / JVM テスト対象）。
 *
 * 座標系:
 * - 画素 (x, y) の中心 = `((x + 0.5) / w, (y + 0.5) / h)` の正規化座標 [0,1]²。
 *   crop 相対ではなく「geometry 適用後の画像全体」基準（マスクは develop 後の画素へ作用する）。
 * - 画素座標系は y 下向き（Android Bitmap の行順）。
 *
 * 重み式（正本。変更時は golden ΔE と preview/export の両方を同時に更新する）:
 * - linear: `t = dot(p−A, B−A) / |B−A|²`、`w = smoothstep(0.5−f/2, 0.5+f/2, t)`。
 *   f=0 は t=0.5 のハード境界（A 側 0 / B 側 1）、f=1 は A→B の全長ランプ。
 *   A==B は [MaskSpecs.normalize] が拒否する（本クラスは防御的に 0 を返す）。
 * - radial: `p−c` を角度分**逆回転**した (u,v) で `d = sqrt((u/rx)² + (v/ry)²)`、
 *   `w = 1 − smoothstep(1−f, 1, d)`。f=0 は d=1 のハード境界（境界 d=1 は外側=0）。
 *   回転方向は「マスク楕円を画面上で時計回りに angleDeg 回した」順写像の逆写像
 *   （y 下向き座標では順写像 = (u cosθ − v sinθ, u sinθ + v cosθ)）。
 * - brush: 各 stroke について **画素距離** `dist` を `min(w,h)` で正規化し `d = dist / (radius * min(w,h))`
 *   相当（[radius] は正規化半径）、`w_s = opacity * (1 − smoothstep(1−feather, 1, d))`。
 *   全 stroke の最大値。stroke が空なら 0。
 *
 * すべての重みは [0,1]。feather は [MaskSpecs] の正規化済み値を前提とする（範囲外・非有限は
 * 0..1 にクランプして評価し、呼出し側の数値破綻を防ぐ）。
 */
object MaskWeight {

    /** smoothstep の縮退判定（e1−e0 がこれ以下ならハード境界）。 */
    private const val STEP_EPS = 1.0e-12

    /**
     * 画素 (x, y) の重み（0..1）。
     * [width]/[height] は対象画像の画素寸法（brush の等方距離と正規化座標に使う）。
     */
    fun weightAt(node: EditRecipe.MaskNode, x: Int, y: Int, width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val px = (x + 0.5) / width.toDouble()
        val py = (y + 0.5) / height.toDouble()
        return weightAtNormalized(node, px, py, width, height)
    }

    /**
     * 画素列の重みを行単位で書き出す（帯処理・キャッシュ構築用）。
     * [out] の [offset] から `width` 要素へ書く（帯バッファの部分書込みに対応）。
     */
    fun weightRow(
        node: EditRecipe.MaskNode,
        y: Int,
        width: Int,
        height: Int,
        out: FloatArray,
        offset: Int = 0,
    ) {
        if (width <= 0 || height <= 0 || offset < 0 || offset >= out.size) {
            out.fill(0f)
            return
        }
        if (node.maskType == MaskSpecs.TYPE_BRUSH) {
            // ブラシはストローク数×全画素の O(N·P) を避ける。ストロークごとに
            // 影響する x 範囲（半径のバウンディングボックス）だけを走査して max を取る。
            // 値は [weightAtNormalized] と同一（テスト brushRow_matchesWeightAt で固定）。
            brushRow(node, y, width, height, out, offset)
            return
        }
        val py = (y + 0.5) / height.toDouble()
        val n = min(width, out.size - offset)
        for (x in 0 until n) {
            val px = (x + 0.5) / width.toDouble()
            out[offset + x] = weightAtNormalized(node, px, py, width, height)
        }
    }

    /**
     * brush の 1 行分（[offset] から width 要素）。影響範囲外は 0。
     * 距離は core と同じ「短辺基準の正規化半径 × min(w,h)」の画素距離で評価する。
     */
    private fun brushRow(
        node: EditRecipe.MaskNode,
        y: Int,
        width: Int,
        height: Int,
        out: FloatArray,
        offset: Int,
    ) {
        val n = min(width, out.size - offset)
        java.util.Arrays.fill(out, offset, offset + n, 0f)
        val strokes = node.brushStrokes
        if (strokes.isEmpty()) return
        val feather = featherOf(node.parameters, MaskSpecs.DEFAULT_BRUSH_FEATHER)
        val shortEdge = min(width, height).toDouble()
        if (shortEdge <= 0.0) return
        val yPx = y + 0.5
        for (stroke in strokes) {
            val radiusNorm = stroke.radius
            if (!radiusNorm.isFinite() || radiusNorm <= 0.0) continue
            val opacity = if (stroke.opacity.isFinite()) stroke.opacity.coerceIn(0.0, 1.0) else 0.0
            if (opacity <= 0.0) continue
            val radiusPx = radiusNorm * shortEdge
            val cy = stroke.y * height
            val dy = yPx - cy
            if (kotlin.math.abs(dy) >= radiusPx) continue
            val halfX = sqrt(radiusPx * radiusPx - dy * dy)
            val cx = stroke.x * width
            val first = kotlin.math.ceil(cx - halfX - 0.5).toInt().coerceAtLeast(0)
            val last = kotlin.math.floor(cx + halfX - 0.5).toInt().coerceAtMost(n - 1)
            if (first > last) continue
            for (x in first..last) {
                val dx = (x + 0.5) - cx
                val d = sqrt(dx * dx + dy * dy) / radiusPx
                val w = opacity * (1.0 - smoothstep(1.0 - feather, 1.0, d))
                if (w > 0.0) {
                    val index = offset + x
                    val fw = w.toFloat()
                    if (fw > out[index]) out[index] = fw
                }
            }
        }
    }

    /**
     * 正規化座標 [px]/[py] の重み（テスト・帯処理の共通入口）。
     * [width]/[height] は brush の等方距離にのみ使う（linear/radial は無関係）。
     */
    fun weightAtNormalized(
        node: EditRecipe.MaskNode,
        px: Double,
        py: Double,
        width: Int,
        height: Int,
    ): Float = when (node.maskType) {
        MaskSpecs.TYPE_LINEAR -> linearWeight(node.parameters, px, py)
        MaskSpecs.TYPE_RADIAL -> radialWeight(node.parameters, px, py)
        MaskSpecs.TYPE_BRUSH -> brushWeight(node, px, py, width, height)
        else -> 0f
    }

    // ---------------------------------------------------------------- linear

    private fun linearWeight(params: Map<String, Double>, px: Double, py: Double): Float {
        val x0 = params[MaskSpecs.KEY_X0] ?: return 0f
        val y0 = params[MaskSpecs.KEY_Y0] ?: return 0f
        val x1 = params[MaskSpecs.KEY_X1] ?: return 0f
        val y1 = params[MaskSpecs.KEY_Y1] ?: return 0f
        val feather = featherOf(params, MaskSpecs.DEFAULT_LINEAR_FEATHER)
        val dx = x1 - x0
        val dy = y1 - y0
        val lenSq = dx * dx + dy * dy
        if (!lenSq.isFinite() || lenSq < MaskSpecs.MIN_LINEAR_LENGTH_SQ) return 0f
        val t = ((px - x0) * dx + (py - y0) * dy) / lenSq
        if (!t.isFinite()) return 0f
        val half = feather / 2.0
        val w = smoothstep(0.5 - half, 0.5 + half, t)
        return w.toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- radial

    private fun radialWeight(params: Map<String, Double>, px: Double, py: Double): Float {
        val cx = params[MaskSpecs.KEY_CX] ?: return 0f
        val cy = params[MaskSpecs.KEY_CY] ?: return 0f
        val rx = params[MaskSpecs.KEY_RX] ?: return 0f
        val ry = params[MaskSpecs.KEY_RY] ?: return 0f
        if (!rx.isFinite() || !ry.isFinite() || rx <= MaskSpecs.MIN_RADIAL_RADIUS ||
            ry <= MaskSpecs.MIN_RADIAL_RADIUS
        ) {
            return 0f
        }
        val angleDeg = params[MaskSpecs.KEY_ANGLE_DEG] ?: 0.0
        val feather = featherOf(params, MaskSpecs.DEFAULT_RADIAL_FEATHER)
        val theta = Math.toRadians(if (angleDeg.isFinite()) angleDeg else 0.0)
        val c = cos(theta)
        val s = sin(theta)
        val ux = px - cx
        val uy = py - cy
        // 順写像（CW）= (u c − v s, u s + v c) の逆回転。
        val u = ux * c + uy * s
        val v = -ux * s + uy * c
        val au = u / rx
        val av = v / ry
        val d = sqrt(au * au + av * av)
        if (!d.isFinite()) return 0f
        val outer = 1.0 - feather
        val w = 1.0 - smoothstep(outer, 1.0, d)
        return w.toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- brush

    private fun brushWeight(
        node: EditRecipe.MaskNode,
        px: Double,
        py: Double,
        width: Int,
        height: Int,
    ): Float {
        val strokes = node.brushStrokes
        if (strokes.isEmpty() || width <= 0 || height <= 0) return 0f
        val feather = featherOf(node.parameters, MaskSpecs.DEFAULT_BRUSH_FEATHER)
        val shortEdge = min(width, height).toDouble()
        if (shortEdge <= 0.0) return 0f
        // 画素座標（中心）へ戻して等方距離を測る（正規化座標のままでは縦横比で歪む）。
        val xPx = px * width
        val yPx = py * height
        var best = 0.0
        for (stroke in strokes) {
            val radius = stroke.radius
            if (!radius.isFinite() || radius <= 0.0) continue
            val dx = xPx - stroke.x * width
            val dy = yPx - stroke.y * height
            val distPx = sqrt(dx * dx + dy * dy)
            if (!distPx.isFinite()) continue
            val d = (distPx / shortEdge) / radius
            val opacity = if (stroke.opacity.isFinite()) stroke.opacity.coerceIn(0.0, 1.0) else 0.0
            if (opacity <= 0.0) continue
            val w = opacity * (1.0 - smoothstep(1.0 - feather, 1.0, d))
            if (w > best) best = w
        }
        return best.toFloat().coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- 共通

    private fun featherOf(params: Map<String, Double>, default: Double): Double {
        val raw = params[MaskSpecs.KEY_FEATHER] ?: default
        return if (raw.isFinite()) raw.coerceIn(MaskSpecs.MIN_FEATHER, MaskSpecs.MAX_FEATHER) else default
    }

    /**
     * 標準 smoothstep。`e1 − e0 <= STEP_EPS` はハード境界として `x < e0 → 0 / x >= e0 → 1`。
     * 返り値は [0,1]。
     */
    internal fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        if (!e0.isFinite() || !e1.isFinite() || !x.isFinite()) return 0.0
        if (e1 - e0 <= STEP_EPS) return if (x < e0) 0.0 else 1.0
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
