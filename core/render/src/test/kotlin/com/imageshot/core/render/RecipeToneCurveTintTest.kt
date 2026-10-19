package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tint 利得（RecipeToneCurve.tintGains・設計段近似・未検証）の性質テスト。
 * 方向（緑/マゼンタ）、クランプ、中立化（null/非有限）、LUT 合成（buildChannelLuts 内で
 * 温度利得と要素積）の単調性を検証する。画質の良否判定（golden）は本テストの対象外。
 */
class RecipeToneCurveTintTest {

    /** WB に tint だけを与えた develop（温度無効・トーン無効）。 */
    private fun developWb(tempKelvin: Double? = null, tint: Double): EditRecipe.DevelopParams =
        EditRecipe.DevelopParams(whiteBalance = EditRecipe.WhiteBalance(tempKelvin = tempKelvin, tint = tint))

    // ---- 純関数 tintGains ----

    @Test
    fun tintGains_nullAndNonFinite_degradeToNeutral() {
        for (t in listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val g = RecipeToneCurve.tintGains(t)
            assertTrue("gains for $t", g.contentEquals(doubleArrayOf(1.0, 1.0, 1.0)))
        }
    }

    @Test
    fun tintGains_positiveTint_isGreenWithGivenModel() {
        // モデル: R *= (1 − 0.15t), G *= (1 + 0.30t), B *= (1 − 0.15t)（KDoc の設計段近似）。
        val g = RecipeToneCurve.tintGains(0.5)
        assertEquals(1.0 - 0.15 * 0.5, g[0], 1e-12)
        assertEquals(1.0 + 0.30 * 0.5, g[1], 1e-12)
        assertEquals(1.0 - 0.15 * 0.5, g[2], 1e-12)
    }

    @Test
    fun tintGains_extremesClampedAndFinite() {
        val plus = RecipeToneCurve.tintGains(5.0) // ±1 にクランプ（= t1 と同値）
        val minus = RecipeToneCurve.tintGains(-7.0)
        assertTrue(plus.all { it.isFinite() } && minus.all { it.isFinite() })
        assertEquals(1.0 - 0.15, plus[0], 1e-12)
        assertEquals(1.0 + 0.30, plus[1], 1e-12)
        assertEquals(1.0 - 0.15, plus[2], 1e-12)
        assertEquals(1.0 + 0.15, minus[0], 1e-12)
        assertEquals(1.0 - 0.30, minus[1], 1e-12)
        // 最大逸脱 ≤ 1.3（NaN 無し・範囲外での逃げ出し無し）。
        for (t in listOf(-10.0, -3.3, 3.3, 10.0)) {
            val g = RecipeToneCurve.tintGains(t)
            assertTrue(g.all { it in 0.69..1.31 })
        }
    }

    @Test
    fun tintGains_zeroIsExactlyNeutral() {
        val g = RecipeToneCurve.tintGains(0.0)
        assertEquals(1.0, g[0], 0.0)
        assertEquals(1.0, g[1], 0.0)
        assertEquals(1.0, g[2], 0.0)
    }

    // ---- LUT 合成（温度無効・tintのみ有効）----

    @Test
    fun lutTintOnly_positiveRaisesGAboveRAndB_inMidRange() {
        val lut = RecipeToneCurve.buildChannelLuts(developWb(tint = 1.0))
        for (v in 64..191) {
            val r = lut[v]
            val g = lut[256 + v]
            val b = lut[512 + v]
            assertTrue("G >= R at $v", g >= r)
            assertTrue("G >= B at $v", g >= b)
        }
        // 中域の少なくとも一つで厳格な優越（G > R かつ G > B）。
        val v = 128
        assertTrue(lut[256 + v] > lut[v])
        assertTrue(lut[256 + v] > lut[512 + v])
    }

    @Test
    fun lutTintOnly_negativeRaisesRAndBAboveG_inMidRange() {
        val lut = RecipeToneCurve.buildChannelLuts(developWb(tint = -1.0))
        for (v in 64..191) {
            val r = lut[v]
            val g = lut[256 + v]
            val b = lut[512 + v]
            assertTrue("R >= G at $v", r >= g)
            assertTrue("B >= G at $v", b >= g)
        }
        val v = 129
        assertTrue(lut[v] > lut[256 + v])
        assertTrue(lut[512 + v] > lut[256 + v])
    }

    @Test
    fun lutTintZero_matchesDefaultIdentity() {
        // tint=0 は temperature/tint 両無効（tintGains が [1,1,1]）と等価 → 既定恒等と同値。
        val lut = RecipeToneCurve.buildChannelLuts(developWb(tint = 0.0))
        val base = RecipeToneCurve.buildChannelLuts(
            EditRecipe.DevelopParams(),
        )
        for (v in 0..255) {
            for (ch in 0..2) {
                assertTrue(
                    "ch=$ch v=$v",
                    abs(lut[ch * 256 + v] - base[ch * 256 + v]) <= 1,
                )
            }
        }
    }

    @Test
    fun lutOnlyTint_monotonicPerChannel() {
        // リニア利得（正の定数倍）は非負値の単調写像を保つ → 各チャンネル LUT は単調増加。
        for (t in listOf(-1.0, -0.5, 0.3, 1.0)) {
            val lut = RecipeToneCurve.buildChannelLuts(developWb(tint = t))
            for (ch in 0..2) {
                var prev = -1
                for (v in 0..255) {
                    val out = lut[ch * 256 + v]
                    assertTrue("t=$t ch=$ch v=$v not monotonic", out >= prev)
                    assertTrue("t=$t ch=$ch v=$v out of range", out in 0..255)
                    prev = out
                }
            }
        }
    }

    @Test
    fun lutTintWithTemp_composedElementwise_directionKeptInBothAxes() {
        // 温度（暖）×tint（緑）の組合せ: 利得の要素積は R(1+0.4)×(1−0.15)、G×(1+0.3)、
        // B(1−0.4)×(1−0.15) に相当し、R > B、G > R、G > B の順序がトーン単調写像で維持される
        // （mid 域・tint の符号距離が十分な点での優越で限定的に確認する）。
        val lut = RecipeToneCurve.buildChannelLuts(
            EditRecipe.DevelopParams(whiteBalance = EditRecipe.WhiteBalance(tempKelvin = 2800.0, tint = 1.0)),
        )
        val v = 160
        assertTrue(lut[v] > lut[512 + v]) // R > B（暖）
        assertTrue(lut[256 + v] > lut[v]) // G > R（tint 緑）
    }
}
