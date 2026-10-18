package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * master トーンカーブ（RecipeCurve + RecipeToneCurve 内の適用位置）の性質テスト。
 * 画質の良否 golden は未実施（色再現の定量評価なし — 明示）。
 * 検証対象: 正規化（ソート/クランプ/重複除去/点数）、単調性、端点保持、中立=恒等、
 * 在来LUTへの上書き合成（置換ではない）、golden 値（手計算可能な区分エルミートの値）。
 */
class RecipeCurveTest {

    // ---- 正規化（EditNodeSpecs.normalizeCurve の委譲）----

    @Test
    fun normalize_sortsClampsAndDedupes() {
        val out = RecipeCurve.normalized(
            listOf(
                listOf(1.1, 2.0), // x,y クランプ 0..1
                listOf(0.0, -0.3),
                listOf(0.25, 0.25),
                listOf(0.25, 0.25), // 重複 x は先着優先で除去
            ),
        )
        assertEquals(
            listOf(
                listOf(0.0, 0.0),
                listOf(0.25, 0.25),
                listOf(1.0, 1.0),
            ),
            out,
        )
    }

    @Test
    fun normalize_rejectsInvalidInputs() {
        assertNull(RecipeCurve.normalized(listOf(listOf(0.0, 0.0)))) // 1点
        assertNull(RecipeCurve.normalized(List(17) { listOf(it.toDouble(), 0.5) })) // 17点
        assertNull(RecipeCurve.normalized(listOf(listOf(0.0, 0.0), listOf(Double.NaN, 1.0))))
        assertNull(RecipeCurve.normalized(listOf(listOf(0.0), listOf(1.0, 1.0)))) // 形状不一致
        assertNull(RecipeCurve.normalized(listOf(listOf(0.5, 0.5), listOf(0.5, 0.5)))) // 全重複で 2点未満
    }

    // ---- 中立 = 恒等（LUT に対する短絡を含む）----

    @Test
    fun neutralCurve_lutIdentityWithinOneQuantum() {
        val develop = EditRecipe.DevelopParams(
            curve = EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.0), listOf(1.0, 1.0))),
        )
        val lut = RecipeToneCurve.buildChannelLuts(develop)
        for (v in 0..255) {
            abs(lut[v] - v).let { assertTrue("R drift $it at $v", it <= 1) }
            abs(lut[256 + v] - v).let { assertTrue("G drift $it at $v", it <= 1) }
            abs(lut[512 + v] - v).let { assertTrue("B drift $it at $v", it <= 1) }
        }
    }

    // ---- golden 値（手計算可能な単調3次エルミート）----
    // 点列 (0,0) (0.5,0.4) (1,1): Δ0=0.8, Δ1=1.2, 内部接線 m1=(0.8+1.2)/2=1.0（クランプ不要）。
    // x=0.25: y = h00·0 + h10·0.5·0.8 + h01·0.4 + h11·0.5·1.0 = 0 + 0.0625·... = 0.1875
    // x=0.75: y = 0.4·0.5 + 0.125·0.5·1.0 + 0.5·1 − 0.125·0.5·1.2 = 0.6875

    @Test
    fun evaluate_goldenValues_handComputed() {
        val pts = listOf(listOf(0.0, 0.0), listOf(0.5, 0.4), listOf(1.0, 1.0))
        assertEquals(0.0, RecipeCurve.evaluate(pts, 0.0), 1e-12)
        assertEquals(0.1875, RecipeCurve.evaluate(pts, 0.25), 1e-9)
        assertEquals(0.4, RecipeCurve.evaluate(pts, 0.5), 1e-12)
        assertEquals(0.6875, RecipeCurve.evaluate(pts, 0.75), 1e-9)
        assertEquals(1.0, RecipeCurve.evaluate(pts, 1.0), 1e-12)
    }

    @Test
    fun evaluate_clampsOutsideRange() {
        val pts = listOf(listOf(0.2, 0.3), listOf(0.8, 0.7))
        assertEquals(0.3, RecipeCurve.evaluate(pts, -0.4), 1e-12)
        assertEquals(0.7, RecipeCurve.evaluate(pts, 1.1), 1e-12)
    }

    // ---- 単調性（全域・極端な点列）----

    @Test
    fun evaluate_monotonicAcrossDenseSampling() {
        val cases = listOf(
            listOf(listOf(0.0, 0.0), listOf(0.05, 0.9), listOf(1.0, 1.0)), // 急峻な立上がり
            listOf(listOf(0.0, 0.0), listOf(0.95, 0.1), listOf(1.0, 1.0)), // 急峻な最終区間
            listOf( // 16点ぎっしり（極端な S 字）
                listOf(0.0, 0.0), listOf(0.1, 0.02), listOf(0.2, 0.05), listOf(0.3, 0.1),
                listOf(0.4, 0.25), listOf(0.5, 0.5), listOf(0.6, 0.75), listOf(0.7, 0.9),
                listOf(0.8, 0.95), listOf(0.9, 0.98), listOf(1.0, 1.0),
            ),
        )
        for (pts in cases) {
            val norm = RecipeCurve.normalized(pts)!!
            var prev = -1.0
            val steps = 1024
            for (i in 0..steps) {
                val y = RecipeCurve.evaluate(norm, i / steps.toDouble())
                assertTrue("non-monotonic at $i: $y < $prev", y >= prev - 1.0e-12)
                assertTrue(y in 0.0..1.0)
                prev = y
            }
        }
    }

    // ---- LUT 内での合成（スライダー LUT への上書き・置換ではなく恒等写像を曲げる）----

    @Test
    fun curveAppliedOnTopOfExposureLut_darkensWhileKeepingEndpointsAndMonotonic() {
        val lutWithCurve = RecipeToneCurve.buildChannelLuts(
            EditRecipe.DevelopParams(
                tone = EditRecipe.Tone(exposureEv = 1.0),
                curve = EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.0), listOf(0.5, 0.4), listOf(1.0, 1.0))),
            ),
        )
        val lutNoCurve = RecipeToneCurve.buildChannelLuts(
            EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 1.0)),
        )
        // 中庸域は曲線（下押し）により既定の露出 LUT より暗側へ、端点は (0,0)/(1,1) 保持。
        assertTrue(lutWithCurve[256 + 128] < lutNoCurve[256 + 128])
        assertEquals(0, lutWithCurve[256])
        assertEquals(255, lutWithCurve[256 + 255])
        // R チャンネル単調性（curve 合成後も非減少）。
        var prev = -1
        for (v in 0..255) {
            assertTrue("non-monotonic at $v", lutWithCurve[256 + v] >= prev)
            prev = lutWithCurve[256 + v]
        }
        // 全チャンネル同値（master カーブは R/G/B 等量適用）。
        for (v in 0..255) {
            assertEquals(lutWithCurve[v], lutWithCurve[256 + v])
            assertEquals(lutWithCurve[256 + v], lutWithCurve[512 + v])
        }
    }
}
