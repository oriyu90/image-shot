package com.imageshot.core.render

import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * トーンカーブ式設計の性質テスト（画質の良否を判定する golden テストは未実施 — 明示）。
 * 検証対象: 単調性、端点保持、既定値で恒等、温度利得の方向、非有限の安全化。
 */
class RecipeToneCurveTest {

    private fun defaultTone(
        exposureEv: Double = 0.0,
        contrast: Double = 0.0,
        highlights: Double = 0.0,
        shadows: Double = 0.0,
        whites: Double = 0.0,
        blacks: Double = 0.0,
    ) = EditRecipe.Tone(exposureEv, contrast, highlights, shadows, whites, blacks)

    private fun lut(
        tempKelvin: Double? = null,
        tone: EditRecipe.Tone = defaultTone(),
    ): IntArray = RecipeToneCurve.buildChannelLuts(
        EditRecipe.DevelopParams(whiteBalance = EditRecipe.WhiteBalance(tempKelvin = tempKelvin), tone = tone),
    )

    // ---- sRGB 伝達関数 ----

    @Test
    fun srgbToLinear_endpointsAndMonotonic() {
        assertEquals(0.0, RecipeToneCurve.srgbToLinear(0.0), 1e-12)
        assertEquals(1.0, RecipeToneCurve.srgbToLinear(1.0), 1e-12)
        var prev = -1.0
        for (i in 0..255) {
            val v = RecipeToneCurve.srgbToLinear(i / 255.0)
            assertTrue(v > prev)
            prev = v
        }
    }

    @Test
    fun srgbRoundTrip_withinOneQuantum() {
        for (i in 0..255) {
            val back = (RecipeToneCurve.linearToSrgb(RecipeToneCurve.srgbToLinear(i / 255.0)) * 255.0)
            assertTrue("roundtrip drift at $i", abs(back - i) < 1e-6)
        }
    }

    // ---- 既定値で恒等 ----

    @Test
    fun defaultTone_identityWithinOneQuantum() {
        val lut = lut()
        for (v in 0..255) {
            val r = lut[v]
            val g = lut[256 + v]
            val b = lut[512 + v]
            abs(r - v).let { assertTrue("R drift $it at $v", it <= 1) }
            abs(g - v).let { assertTrue("G drift $it at $v", it <= 1) }
            abs(b - v).let { assertTrue("B drift $it at $v", it <= 1) }
        }
    }

    // ---- 単調性（全チャンネル、極端なスライダー値の組合せ）----

    @Test
    fun luts_monotonicAcrossAllChannelWithExtremeTones() {
        val exposures = listOf(-5.0, -1.0, 0.0, 1.0, 5.0)
        val contrasts = listOf(-1.0, -0.5, 0.0, 0.5, 1.0)
        val regions = listOf(-1.0, 0.0, 1.0)
        for (ex in exposures) {
            for (c in contrasts) {
                for (h in regions) for (s in regions) for (w in regions) for (b in regions) {
                    val luts = lut(
                        tone = defaultTone(
                            exposureEv = ex,
                            contrast = c,
                            highlights = h,
                            shadows = s,
                            whites = w,
                            blacks = b,
                        ),
                    )
                    for (ch in 0..2) {
                        var prev = -1
                        for (v in 0..255) {
                            val out = luts[ch * 256 + v]
                            assertTrue("non-monotonic ch=$ch v=$v", out >= prev)
                            assertTrue("out of range ch=$ch v=$v", out in 0..255)
                            prev = out
                        }
                    }
                }
            }
        }
    }

    // ---- 個別ノードの方向 ----

    @Test
    fun exposurePositive_brightensUpperRange_noneChangesAtPureBlack() {
        val plus = lut(tone = defaultTone(exposureEv = 1.0))
        val base = lut()
        for (v in 0..255) {
            if (v == 0) continue // 線形0は露出で動かない
            assertTrue("exposure+ should brighten at $v", plus[256 + v] >= base[256 + v])
        }
    }

    @Test
    fun temperatureWarm_raisesRedAndLowersBlue() {
        val warm = lut(tempKelvin = AdjustmentId.tempKelvinFromSlider(80.0)) // 暖色寄り
        val cold = lut(tempKelvin = AdjustmentId.tempKelvinFromSlider(-60.0)) // 寒色寄り
        for (v in 64..191) {
            assertTrue(warm[v] >= warm[512 + v]) // R >= B（暖）
            assertTrue(cold[512 + v] >= cold[v]) // B >= R（寒）
        }
    }

    @Test
    fun highlightsNegative_recoversUpperHalf_leavesLowerHalfIdentity() {
        val recovered = lut(tone = defaultTone(highlights = -1.0))
        val base = lut()
        // 作用区間外（下半分）は恒等（補償で全体を動かさない設計）。
        for (v in 0..127) {
            assertWithin("lower half should stay at $v", recovered[256 + v], base[256 + v], 1)
        }
        // 上半分は暗側へ。端点はバンプが 0 のため保持される。
        for (v in 200..254) {
            assertTrue("highlights- should not brighten at $v", recovered[256 + v] <= base[256 + v])
        }
        assertTrue("highlights- should recover mid-upper region", recovered[256 + 224] < base[256 + 224])
    }

    @Test
    fun shadowsPositive_liftsLowerHalf_leavesUpperHalfIdentity() {
        val lifted = lut(tone = defaultTone(shadows = 1.0))
        val base = lut()
        for (v in 32..127) {
            assertTrue("shadows+ should brighten at $v", lifted[256 + v] >= base[256 + v])
        }
        assertTrue("shadows+ should lift mid-lower region", lifted[256 + 64] > base[256 + 64])
        for (v in 200..254) {
            assertWithin("upper half should stay at $v", lifted[256 + v], base[256 + v], 1)
        }
    }

    @Test
    fun whitesPositive_pushToUpper_range_begativeLowersTop() {
        val plus = lut(tone = defaultTone(whites = 1.0))
        val minus = lut(tone = defaultTone(whites = -1.0))
        for (v in 200..255) {
            assertTrue(plus[256 + v] >= minus[256 + v])
        }
    }

    @Test
    fun blacksNegative_crushesLower() {
        val crushed = lut(tone = defaultTone(blacks = -1.0))
        val base = lut()
        for (v in 0..96) {
            assertTrue("blacks- should darken at $v", crushed[256 + v] <= base[256 + v])
        }
    }

    @Test
    fun contrast_preservesEndpoints() {
        val strong = lut(tone = defaultTone(contrast = 1.0))
        assertEquals(0, strong[256])
        assertEquals(255, strong[256 + 255])
    }

    // ---- 非有限の安全化（§7.1）----

    @Test
    fun nonFiniteValues_degradeToNeutral_noCrash() {
        val lut = lut(
            tone = EditRecipe.Tone(
                exposureEv = Double.NaN,
                contrast = Double.NEGATIVE_INFINITY,
                highlights = Double.POSITIVE_INFINITY,
                shadows = Double.NaN,
                whites = Double.NaN,
                blacks = Double.NaN,
            ),
        )
        val base = lut()
        for (v in 0..255) {
            assertWithin("non-finite tone ≈ identity at $v", lut[256 + v], base[256 + v], 1)
        }
    }

    @Test
    fun absurdTemperatureGain_degradesToNeutral() {
        val lut = lut(tempKelvin = -1.0)
        val base = lut()
        for (v in 0..255) {
            assertWithin("invalid K ≈ as-shot at $v", lut[256 + v], base[256 + v], 1)
        }
    }

    // ---- 彩度 / 自然な彩度 ----

    @Test
    fun saturationMinusOne_makesPixelNeutral() {
        val mixer = RecipeToneCurve.ColorMixer(vibrance = 0.0, saturation = -1.0)
        val out = FloatArray(3)
        mixer.mix(1.0f, 0.2f, 0.3f, out)
        val lum = 0.2126f * 1.0f + 0.7152f * 0.2f + 0.0722f * 0.3f
        assertEquals(lum, out[0], 1e-4f)
        assertEquals(lum, out[1], 1e-4f)
        assertEquals(lum, out[2], 1e-4f)
    }

    @Test
    fun vibranceLowSatPixel_awakenMoreThanSaturated() {
        // 低彩度画素（グレー寄り）と高彩度画素に同じ +vibrance を与え、
        // 低彩度側の彩度増分が大きいこと。
        val mixer = RecipeToneCurve.ColorMixer(vibrance = 1.0, saturation = 0.0)
        val gray = FloatArray(3)
        mixer.mix(0.5f, 0.5f, 0.5f, gray)
        val vivid = FloatArray(3)
        mixer.mix(1.0f, 0.0f, 0.0f, vivid)
        fun sat(r: Float, g: Float, b: Float): Float {
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return (mx - mn) / mx
        }
        val grayGain = sat(gray[0], gray[1], gray[2]) - 0.0f
        val vividGain = sat(vivid[0], vivid[1], vivid[2]) - 1.0f
        assertTrue(grayGain >= vividGain)
    }

    private fun assertWithin(message: String, actual: Int, expected: Int, tolerance: Int) {
        assertTrue("$message (actual=$actual expected=$expected)", abs(actual - expected) <= tolerance)
    }
}
