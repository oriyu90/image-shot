package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MaskWeight] の固定値・端点・境界（PR-29a・JVM）。
 *
 * 期待値は式（KDoc）から手計算した固定値であり、実装の出力をそのまま記録したものではない。
 * 座標は画素中心 `(x+0.5)/w` の正規化座標。
 */
class MaskWeightTest {

    private fun linearMask(
        x0: Double = 0.2,
        y0: Double = 0.2,
        x1: Double = 0.8,
        y1: Double = 0.8,
        feather: Double = 0.6,
    ) = EditRecipe.MaskNode(
        maskId = "linear-1",
        maskType = "linear",
        parameters = mapOf("x0" to x0, "y0" to y0, "x1" to x1, "y1" to y1, "feather" to feather),
    )

    private fun radialMask(
        cx: Double = 0.5,
        cy: Double = 0.5,
        rx: Double = 0.3,
        ry: Double = 0.2,
        angleDeg: Double = 0.0,
        feather: Double = 0.0,
    ) = EditRecipe.MaskNode(
        maskId = "radial-1",
        maskType = "radial",
        parameters = mapOf(
            "cx" to cx,
            "cy" to cy,
            "rx" to rx,
            "ry" to ry,
            "angleDeg" to angleDeg,
            "feather" to feather,
        ),
    )

    private fun brushMask(
        strokes: List<EditRecipe.BrushStroke>,
        feather: Double = 0.0,
    ) = EditRecipe.MaskNode(
        maskId = "brush-1",
        maskType = "brush",
        parameters = mapOf("feather" to feather),
        brushStrokes = strokes,
    )

    private fun weight(node: EditRecipe.MaskNode, px: Double, py: Double, w: Int = 100, h: Int = 100): Double =
        MaskWeight.weightAtNormalized(node, px, py, w, h).toDouble()

    // ---------------------------------------------------------------- smoothstep

    @Test
    fun smoothstep_endpointsAndMidpoint() {
        assertEquals(0.0, MaskWeight.smoothstep(0.0, 1.0, 0.0), 1e-12)
        assertEquals(1.0, MaskWeight.smoothstep(0.0, 1.0, 1.0), 1e-12)
        assertEquals(0.5, MaskWeight.smoothstep(0.0, 1.0, 0.5), 1e-12)
        // 範囲外はクランプ。
        assertEquals(0.0, MaskWeight.smoothstep(0.2, 0.8, 0.1), 1e-12)
        assertEquals(1.0, MaskWeight.smoothstep(0.2, 0.8, 0.9), 1e-12)
        // 退化（e0==e1）はハード境界。
        assertEquals(0.0, MaskWeight.smoothstep(0.5, 0.5, 0.4999), 1e-12)
        assertEquals(1.0, MaskWeight.smoothstep(0.5, 0.5, 0.5), 1e-12)
    }

    // ---------------------------------------------------------------- linear

    @Test
    fun linear_fixedValuesAlongDiagonal() {
        // A(0.2,0.2) → B(0.8,0.8), feather 0.6 → ランプ域 t ∈ [0.2, 0.8]。
        val mask = linearMask()
        assertEquals(0.0, weight(mask, 0.2, 0.2), 1e-6) // t=0
        assertEquals(1.0, weight(mask, 0.8, 0.8), 1e-6) // t=1
        assertEquals(0.5, weight(mask, 0.5, 0.5), 1e-6) // t=0.5（ランプ中央）
        // t=0.25 → s=(0.25-0.2)/0.6=1/12 → 3s²-2s³ = 0.0196759...
        assertEquals(0.019676, weight(mask, 0.35, 0.35), 1e-6)
        // t=0.75 → s=11/12 → 0.980324...
        assertEquals(0.980324, weight(mask, 0.65, 0.65), 1e-6)
        // 線の外側（A より手前 / B より奥）は 0 / 1 に張り付く。
        assertEquals(0.0, weight(mask, 0.1, 0.1), 1e-6)
        assertEquals(1.0, weight(mask, 0.9, 0.9), 1e-6)
    }

    @Test
    fun linear_featherEndpoints_hardBoundaryAndFullRamp() {
        // feather 0: t=0.5 のハード境界（A 側 0 / B 側 1）。
        // ※ 斜め軸では t が浮動小数の丸めで 0.5 をわずかに下回り得るため、境界の厳密値は
        //    軸平行（t = px が厳密）なマスクで検査する。
        val hard = linearMask(feather = 0.0)
        assertEquals(0.0, weight(hard, 0.49, 0.49), 0.0)
        assertEquals(1.0, weight(hard, 0.51, 0.51), 0.0)
        val axisAligned = EditRecipe.MaskNode(
            maskId = "axis",
            maskType = "linear",
            parameters = mapOf("x0" to 0.0, "y0" to 0.5, "x1" to 1.0, "y1" to 0.5, "feather" to 0.0),
        )
        assertEquals(0.0, weight(axisAligned, 0.49, 0.5), 0.0)
        assertEquals(1.0, weight(axisAligned, 0.5, 0.5), 0.0)
        assertEquals(1.0, weight(axisAligned, 0.51, 0.5), 0.0)

        // feather 1: A→B の全長ランプ（t=0.25 → smoothstep(0,1,0.25)=0.15625）。
        val full = linearMask(feather = 1.0)
        assertEquals(0.0, weight(full, 0.2, 0.2), 1e-6)
        assertEquals(1.0, weight(full, 0.8, 0.8), 1e-6)
        assertEquals(0.15625, weight(full, 0.35, 0.35), 1e-6)
    }

    @Test
    fun linear_perpendicularDistanceIsIgnored() {
        // linear は A→B 軸への射影のみ（軸に垂直な方向 = (1,-1) 方向では重みが変わらない）。
        val mask = linearMask()
        assertEquals(weight(mask, 0.5, 0.5), weight(mask, 0.9, 0.1), 1e-6)
        assertEquals(weight(mask, 0.5, 0.5), weight(mask, 0.1, 0.9), 1e-6)
        // 軸方向（1,1）へ動けば変わる。
        assertTrue(abs(weight(mask, 0.7, 0.7) - weight(mask, 0.5, 0.5)) > 0.1)
    }

    @Test
    fun linear_degenerateAxis_returnsZero() {
        // normalize が拒否する入力でも、重み計算は防御的に 0（NaN を出さない）。
        val degenerate = EditRecipe.MaskNode(
            maskId = "bad",
            maskType = "linear",
            parameters = mapOf("x0" to 0.5, "y0" to 0.5, "x1" to 0.5, "y1" to 0.5, "feather" to 0.5),
        )
        assertEquals(0.0, weight(degenerate, 0.5, 0.5), 0.0)
    }

    // ---------------------------------------------------------------- radial

    @Test
    fun radial_fixedValuesWithoutFeather() {
        val mask = radialMask(rx = 0.4, ry = 0.2, feather = 0.0)
        assertEquals(1.0, weight(mask, 0.5, 0.5), 1e-6) // 中心 d=0
        assertEquals(1.0, weight(mask, 0.89, 0.5), 1e-6) // d=0.975（境界の内側）
        assertEquals(1.0, weight(mask, 0.7, 0.5), 1e-6) // d=0.5
        assertEquals(0.0, weight(mask, 0.9001, 0.5), 1e-6) // d>1
        assertEquals(0.0, weight(mask, 0.5, 0.7001), 1e-6) // ry 方向 d>1
        // 境界 d==1 は外側（0）として扱う（KDoc 明示）。
        // ※ d が厳密に 1 になる座標（dyadic な ry=0.25・py=0.75）で検査する。
        assertEquals(0.0, weight(radialMask(rx = 0.4, ry = 0.25, feather = 0.0), 0.5, 0.75), 0.0)
    }

    @Test
    fun radial_featherRamp_isCenteredAtOuterEdge() {
        // feather 0.5: d=0.5 まで 1、d=1 で 0、d=0.75 で 0.5。
        val mask = radialMask(rx = 0.4, ry = 0.2, feather = 0.5)
        assertEquals(1.0, weight(mask, 0.5, 0.5), 1e-6)
        assertEquals(1.0, weight(mask, 0.7, 0.5), 1e-6) // d=0.5
        assertEquals(0.5, weight(mask, 0.8, 0.5), 1e-6) // d=0.75
        assertEquals(0.0, weight(mask, 0.9, 0.5), 0.0) // d=1（外側境界）
        // 内側 50% は完全に不透明（feather 端点）。
        assertEquals(1.0, weight(radialMask(rx = 0.4, ry = 0.2, feather = 1.0), 0.5, 0.5), 1e-6)
    }

    @Test
    fun radial_angleRotatesEllipseClockwiseInImageCoordinates() {
        // 楕円 (rx=0.4, ry=0.1) を時計回りに 90° 回す → 長軸が縦（画像 y 方向）を向く。
        val mask = radialMask(rx = 0.4, ry = 0.1, angleDeg = 90.0, feather = 0.0)
        // 縦方向は長軸: 中心から 0.3 下は内側（d=0.3/0.4=0.75）。
        assertEquals(1.0, weight(mask, 0.5, 0.8), 1e-6)
        // 横方向は短軸: 中心から 0.3 右は外側（d=0.3/0.1=3）。
        assertEquals(0.0, weight(mask, 0.8, 0.5), 1e-6)
        // 比較: 回転なしでは逆（横が長軸）。
        val unrotated = radialMask(rx = 0.4, ry = 0.1, angleDeg = 0.0, feather = 0.0)
        assertEquals(1.0, weight(unrotated, 0.8, 0.5), 1e-6)
        assertEquals(0.0, weight(unrotated, 0.5, 0.8), 1e-6)
        // -90°/270° は同じ回転（正準化前でも等価）。
        assertEquals(
            weight(mask, 0.5, 0.8),
            weight(radialMask(rx = 0.4, ry = 0.1, angleDeg = 270.0, feather = 0.0), 0.5, 0.8),
            1e-9,
        )
    }

    @Test
    fun radial_invalidRadii_returnsZero() {
        val mask = radialMask(rx = 0.0, ry = 0.2, feather = 0.0)
        assertEquals(0.0, weight(mask, 0.5, 0.5), 0.0)
    }

    // ---------------------------------------------------------------- brush

    @Test
    fun brush_fixedValuesAndEmptyStrokes() {
        val mask = brushMask(listOf(EditRecipe.BrushStroke(x = 0.5, y = 0.5, radius = 0.1, opacity = 1.0)))
        // 画像 100x100・feather 0: 半径 0.1 * min(100,100) = 10px。
        assertEquals(1.0, weight(mask, 0.5, 0.5, 100, 100), 1e-6) // 中心
        assertEquals(1.0, weight(mask, 0.58, 0.5, 100, 100), 1e-6) // 8px 内側
        assertEquals(0.0, weight(mask, 0.62, 0.5, 100, 100), 1e-6) // 12px 外側
        // ストローク無しは 0。
        assertEquals(0.0, weight(brushMask(emptyList()), 0.5, 0.5, 100, 100), 0.0)
    }

    @Test
    fun brush_opacityCapsWeightAndMaxAcrossStrokes() {
        val single = brushMask(listOf(EditRecipe.BrushStroke(0.5, 0.5, radius = 0.2, opacity = 0.4)))
        assertEquals(0.4, weight(single, 0.5, 0.5, 100, 100), 1e-6)
        // 重なりは「最大値」（加算しない）。
        val overlapping = brushMask(
            listOf(
                EditRecipe.BrushStroke(0.5, 0.5, radius = 0.2, opacity = 0.4),
                EditRecipe.BrushStroke(0.5, 0.5, radius = 0.2, opacity = 0.7),
            ),
        )
        assertEquals(0.7, weight(overlapping, 0.5, 0.5, 100, 100), 1e-6)
    }

    @Test
    fun brush_distanceIsIsotropicInPixels_notInNormalizedSpace() {
        // 200x100 の画像: 画素距離を min(w,h)=100 で正規化するため、横 20px と縦 20px は等価。
        // 正規化座標そのままで測ると横 0.1 / 縦 0.2 となり非等価になる（その誤りを検出する）。
        val mask = brushMask(listOf(EditRecipe.BrushStroke(x = 0.5, y = 0.5, radius = 0.25)), feather = 1.0)
        val horizontal = weight(mask, 0.6, 0.5, 200, 100) // 20px 右
        val vertical = weight(mask, 0.5, 0.7, 200, 100) // 20px 下（高さ 100px の 0.2）
        assertEquals(horizontal, vertical, 1e-9)
        // d = 20/100/0.25 = 0.8 → w = 1 - smoothstep(0,1,0.8) = 0.104
        assertEquals(0.104, horizontal, 1e-6)
    }

    @Test
    fun brush_featherEndpoints() {
        val hard = brushMask(listOf(EditRecipe.BrushStroke(0.5, 0.5, radius = 0.2)), feather = 0.0)
        // 半径 20px（100x100）: 境界の内側 1 / 外側 0。
        assertEquals(1.0, weight(hard, 0.6, 0.5), 1e-6)
        assertEquals(0.0, weight(hard, 0.75, 0.5), 1e-6)
        // feather 1: 中心から半径までフルランプ（中心直近はほぼ 1・半分で 0.5）。
        val full = brushMask(listOf(EditRecipe.BrushStroke(0.5, 0.5, radius = 0.2)), feather = 1.0)
        assertEquals(1.0, weight(full, 0.5, 0.5), 1e-6)
        assertEquals(0.5, weight(full, 0.6, 0.5), 1e-6) // d=0.5
    }

    // ---------------------------------------------------------------- 画素座標・行

    @Test
    fun weightAt_usesPixelCenterCoordinates() {
        // 4x4 画像の画素 (1,1) の中心 = (1.5/4, 1.5/4) = (0.375, 0.375)。
        val mask = linearMask(feather = 0.0)
        val viaPixel = MaskWeight.weightAt(mask, 1, 1, 4, 4)
        val viaNormalized = MaskWeight.weightAtNormalized(mask, 0.375, 0.375, 4, 4)
        assertEquals(viaNormalized.toDouble(), viaPixel.toDouble(), 1e-9)
    }

    @Test
    fun weightRow_matchesWeightAtForEveryColumn() {
        val mask = radialMask(rx = 0.35, ry = 0.25, feather = 0.4)
        val width = 37
        val height = 23
        val row = FloatArray(width)
        for (y in listOf(0, 11, height - 1)) {
            MaskWeight.weightRow(mask, y, width, height, row)
            for (x in 0 until width) {
                assertEquals(
                    MaskWeight.weightAt(mask, x, y, width, height).toDouble(),
                    row[x].toDouble(),
                    1e-7,
                )
            }
        }
    }

    @Test
    fun weightRow_offsetWritesOnlyRequestedSlice() {
        val mask = linearMask()
        val out = FloatArray(10) { -1f }
        MaskWeight.weightRow(mask, 0, 4, 2, out, offset = 3)
        for (i in 0 until 3) assertEquals(-1.0, out[i].toDouble(), 0.0)
        assertTrue(out[3] >= 0f && out[6] >= 0f)
        assertTrue(out.drop(7).all { it == -1f })
    }

    @Test
    fun allWeights_stayWithinUnitRange() {
        val masks = listOf(
            linearMask(),
            radialMask(feather = 0.7),
            brushMask(listOf(EditRecipe.BrushStroke(0.3, 0.7, 0.25, 0.8)), feather = 0.5),
        )
        for (mask in masks) {
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val w = MaskWeight.weightAt(mask, x, y, 8, 8)
                    assertTrue("mask=${mask.maskType} w=$w", w >= 0f && w <= 1f)
                    assertTrue("mask=${mask.maskType} w=$w", !w.isNaN())
                }
            }
        }
    }
}
