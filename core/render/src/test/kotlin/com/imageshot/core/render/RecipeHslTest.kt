package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HSL 8系統（RecipeHsl）の性質テスト。画質 golden（色再現の定量評価）は未実施 — 明示。
 * 検証対象: 中立=画素恒等（sampled ramp で厳密）、バンド分類の核（重み 1）、
 * 赤バンドのみの色相シフトが オレンジ/マゼンタ/紫 の核画素を動かさない（境界背離）、
 * 極端パラメータで非有限を作らない、golden 値（文書化した HSV/線形ゲイン公式を別途組む）。
 */
class RecipeHslTest {

    private fun apply(
        params: Map<String, Double>,
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val out = FloatArray(3)
        RecipeHsl.HslMixer(params).apply(r, g, b, out)
        return out
    }

    /** 単純な HSV→RGB（v=1 s=1 の純色）。テスト専用の既約公式。hue は整数丸めしない。 */
    private fun pureHue(hueDeg: Double): FloatArray {
        val h = ((hueDeg % 360) + 360) % 360
        val hd = h / 60.0
        val f = hd - hd.toInt()
        val ff = f.toFloat()
        return when (hd.toInt()) {
            0 -> floatArrayOf(1f, ff, 0f)
            1 -> floatArrayOf(1f - ff, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, ff)
            3 -> floatArrayOf(0f, 1f - ff, 1f)
            4 -> floatArrayOf(ff, 0f, 1f)
            else -> floatArrayOf(1f, 0f, 1f - ff)
        }
    }

    private fun hueOf(rgb: FloatArray): Double {
        val (r, g, b) = rgb.toList()
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val delta = mx - mn
        if (delta <= 0f) return 0.0
        val h = when (mx) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * ((b - r) / delta + 2.0)
            else -> 60.0 * ((r - g) / delta + 4.0)
        }
        return ((h % 360) + 360) % 360
    }

    // ---- 中立 = 画素恒等（sampled ramp で厳密）----

    @Test
    fun neutralHsl_isExactIdentityOnSampledRamp() {
        val ramp = FloatArray(64) { it / 63f * 0.8f + 0.1f }
        val neutral = mapOf<String, Double>(
            "red.hue" to 0.0, "magenta.lum" to 0.0,
        )
        for (r in ramp) for (g in ramp) for (b in ramp.take(8)) {
            val out = apply(neutral, r, g, b)
            assertEquals(r, out[0], 1.0e-6f)
            assertEquals(g, out[1], 1.0e-6f)
            assertEquals(b, out[2], 1.0e-6f)
        }
        // 空マップ → 展開側で丸ごとスキップ（isNeutral）。
        assertTrue(RecipeHsl.isNeutral(emptyMap()))
        assertTrue(RecipeHsl.isNeutral(mapOf("blue.sat" to 0.0)))
        assertTrue(!RecipeHsl.isNeutral(mapOf("blue.sat" to 0.1)))
    }

    // ---- バンド分類（核の重み = 1、全域での総和 = 1）----

    @Test
    fun bandWeights_sumToOne_andCoreWeightsExact() {
        val out = DoubleArray(8)
        for (h in 0..359) {
            RecipeHsl.bandWeights(h.toDouble(), out)
            abs(out.sum() - 1.0).let { assertTrue("sum $it at $h", it < 1.0e-12) }
        }
        // 核中心（境界から ±15° 離れた点）で対応バンドの重み 1。
        val centers = listOf(
            0 to "red", 30 to "orange", 60 to "yellow", 110 to "green",
            180 to "aqua", 240 to "blue", 290 to "purple", 330 to "magenta",
        )
        for ((hue, bandId) in centers) {
            RecipeHsl.bandWeights(hue.toDouble(), out)
            abs(out[EditNodeSpecs.bandIndex(bandId)!!] - 1.0)
                .let { assertTrue("band $bandId weight ${out.toList()} at hue $hue", it < 1.0e-9) }
        }
    }

    // ---- 赤+30°シフト: 隣接バンドの核画素は不変（境界背離）----

    @Test
    fun hueShiftRed_leavesNeighborBandCorePixelsUnchanged() {
        val params = mapOf("red.hue" to 1.0) // +30°
        // オレンジ核 30°/紫核 290°/マゼンタ核 330°: いずれも境界から >7.5° 離れた赤バンド外の核。
        for (hue in listOf(30.0, 290.0, 330.0)) {
            val src = pureHue(hue)
            val out = apply(params, src[0], src[1], src[2])
            abs(hueOf(src) - hueOf(out)).let { d ->
                assertTrue("hue $hue shifted by $d", d < 0.5)
            }
            assertEquals(src[0], out[0], 1.0e-4f)
            assertEquals(src[1], out[1], 1.0e-4f)
            assertEquals(src[2], out[2], 1.0e-4f)
        }
    }

    @Test
    fun hueShiftRed_rotatesRedCoreToOrange() {
        val params = mapOf("red.hue" to 1.0) // +30°
        val src = pureHue(0.0) // 純赤
        val out = apply(params, src[0], src[1], src[2])
        // 赤核(0°)→+30°。彩度は HSV 内で保存されるため純色は純色のまま回る。
        abs(hueOf(out) - 30.0).let { assertTrue("red core should move 30°, moved $it", abs(it) < 1.0) }
    }

    // ---- 境界連続性（ソフトゾーン内で出力 hue が不連続跳躍を作らない）----

    /** 色相の循環差（0..180）。 */
    private fun hueDist(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    @Test
    fun hueBoundary_isContinuousAcrossSoftZone() {
        val params = mapOf("red.hue" to -0.5) // −15°
        // 境界 15° ± 7.5°（ソフトゾーン 7.5...22.5°）を 0.25° 刻みで走査。
        var prevHue: Double? = null
        for (h in generateSequence(7.0) { it + 0.25 }.takeWhile { it <= 23.0 }) {
            val src = pureHue(h)
            val out = apply(params, src[0], src[1], src[2])
            val outHue = hueOf(out)
            val prev = prevHue
            if (prev != null) {
                val jump = hueDist(outHue, prev)
                // 0.25° の入力差に対する出力の循環ジャンプは小さいことを要求する。
                assertTrue("hue jump $jump at $h", jump < 4.0)
            }
            prevHue = outHue
        }
    }

    // ---- 極端（±1 全体系）で非有限を作らない ----

    @Test
    fun extremeParams_noNonFinite() {
        val params = mutableMapOf<String, Double>()
        EditNodeSpecs.HSL_BANDS.forEachIndexed { i, (band, _) ->
            when (i % 3) {
                0 -> params["${band}.hue"] = 1.0
                1 -> params["${band}.sat"] = -1.0
                2 -> params["${band}.lum"] = -1.0
            }
        }
        for (r in listOf(0.0f, 0.5f, 1.0f)) for (g in listOf(0.0f, 0.25f, 1.0f)) for (b in listOf(0.0f, 0.75f, 1.0f)) {
            val out = apply(params, r, g, b)
            out.forEach { v ->
                assertTrue("non-finite at ($r,$g,$b)", v.isFinite() && v in 0.0f..1.0f)
            }
        }
    }

    // ---- golden（文書化した公式を独立に組む: グリーン核の 明るさ +50 = 線形 ×2^0.5）----

    @Test
    fun golden_greenCorePixel_lumGainInLinearLight() {
        // グリーン核 hue=130° の画素（バンド重み=緑 1）に lum −50（= −0.5 EV 相当）を与える。
        val src = pureHue(130.0) // s=1, v=1 の純色
        val out = apply(mapOf("green.lum" to -0.5), src[0], src[1], src[2])
        // 文書化公式: v' = linearToSrgb(srgbToLinear(v)·2^(−0.5))、hue/s は不変 →
        // 全チャンネルが v2 倍（同一 hue・s の HSV 構造を保つ）。
        val v2 = RecipeToneCurve.linearToSrgb(RecipeToneCurve.srgbToLinear(1.0) * RecipeHsl.lumGainOf(-0.5))
        assertEquals(src[0] * v2.toFloat(), out[0], 1.0e-5f)
        assertEquals(src[1] * v2.toFloat(), out[1], 1.0e-5f)
        assertEquals(src[2] * v2.toFloat(), out[2], 1.0e-5f)
    }

    // ---- develop 適用経路を通した統合（HSL スライダーがプレビュー/書き出し共通の画素を動かす）----

    @Test
    fun applyToPixels_blueHueShift_changesPixelBeyondQuantization() {
        val base = 0xFF37_44B0.toInt() // 濃い青
        val neutral = intArrayOf(base)
        RecipeDevelopApplier.applyToPixels(neutral, EditRecipe.DevelopParams())
        val shifted = intArrayOf(base)
        RecipeDevelopApplier.applyToPixels(
            shifted,
            EditRecipe.DevelopParams(color = EditRecipe.ColorParams(hsl = mapOf("blue.hue" to -0.5))), // −15°
        )
        val dr = abs(((neutral[0] ushr 16) and 0xFF) - ((shifted[0] ushr 16) and 0xFF))
        val dg = abs(((neutral[0] ushr 8) and 0xFF) - ((shifted[0] ushr 8) and 0xFF))
        val db = abs((neutral[0] and 0xFF) - (shifted[0] and 0xFF))
        assertTrue("hue shift −15° should alter blue pixel (dr=$dr dg=$dg db=$db)", maxOf(dr, dg, db) >= 10)
    }
}
