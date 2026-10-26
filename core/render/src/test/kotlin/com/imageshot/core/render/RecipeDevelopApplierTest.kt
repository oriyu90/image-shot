package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecipeDevelopApplier（プレビュー/書き出し共有の develop 適用・純Kotlin）の性質テスト。
 * 画質の良否を判定する golden テストは未実施（明示）。
 */
class RecipeDevelopApplierTest {

    private fun defaultDevelop() = EditRecipe.DevelopParams()

    private fun defaultRecipe(
        develop: EditRecipe.DevelopParams = defaultDevelop(),
    ) = EditRecipe(
        documentId = "doc",
        source = EditRecipe.RecipeSource(assetId = "asset", fingerprint = "fp"),
        develop = develop,
    )

    private fun pixel(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // ---- 既定 develop（no-op トーン）での近似恒等 ----

    @Test
    fun applyToPixels_defaultDevelop_isNearIdentityOnSrgbRamp() {
        val n = 256
        val pixels = IntArray(n) { i -> pixel(0x80, i, i, i) }
        RecipeDevelopApplier.applyToPixels(pixels, defaultDevelop())
        for (i in 0 until n) {
            val p = pixels[i]
            abs(((p ushr 16) and 0xFF) - i).let { assertTrue("R drift $it at $i", it <= 1) }
            abs(((p ushr 8) and 0xFF) - i).let { assertTrue("G drift $it at $i", it <= 1) }
            abs((p and 0xFF) - i).let { assertTrue("B drift $it at $i", it <= 1) }
        }
    }

    @Test
    fun applyToPixels_preservesAlpha() {
        val alphas = listOf(0x00, 0x2A, 0x80, 0xFF)
        val pixels = alphas.map { a -> pixel(a, 200, 100, 50) }.toIntArray()
        RecipeDevelopApplier.applyToPixels(pixels, defaultDevelop())
        alphas.forEachIndexed { idx, a ->
            assertEquals(a, (pixels[idx] ushr 24) and 0xFF)
        }
    }

    // ---- 方向の検証（設計段階の式・golden ではない）----

    @Test
    fun applyToPixels_exposurePositive_brightensMidtones() {
        val n = 256
        val pixels = IntArray(n) { i -> pixel(0xFF, i, i, i) }
        RecipeDevelopApplier.applyToPixels(
            pixels,
            defaultDevelop().copy(tone = EditRecipe.Tone(exposureEv = 1.0)),
        )
        for (i in 32..223) {
            val out = (pixels[i] ushr 16) and 0xFF
            assertTrue("exposure+ should brighten gray $i", out >= i)
        }
    }

    @Test
    fun applyToPixels_saturationMinusOne_makesSaturatedPixelNeutral() {
        val pixels = intArrayOf(pixel(0xFF, 255, 20, 20))
        RecipeDevelopApplier.applyToPixels(
            pixels,
            defaultDevelop().copy(color = EditRecipe.ColorParams(saturation = -1.0)),
        )
        val p = pixels[0]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("sat -1 should neutralize: r=$r g=$g b=$b", abs(r - g) <= 2 && abs(g - b) <= 2)
    }

    // ---- 既定値でない未実装ノードの警告（ADR-014: 無通知縮退の禁止）----

    @Test
    fun unappliedNodeWarnings_defaultRecipe_isEmpty() {
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(defaultRecipe()).isEmpty())
    }

    @Test
    fun unappliedNodeWarnings_appliedDevelopNodes_only_noWarnings() {
        // tone / WB / vibrance / saturation は適用済みノードのため、非既定でも偽警告にしない。
        val develop = EditRecipe.DevelopParams(
            whiteBalance = EditRecipe.WhiteBalance(tempKelvin = 5200.0, tint = 0.1),
            tone = EditRecipe.Tone(
                exposureEv = 0.3,
                contrast = 0.1,
                highlights = -0.2,
                shadows = 0.4,
                whites = 0.5,
                blacks = -0.3,
            ),
            color = EditRecipe.ColorParams(vibrance = 0.2, saturation = -0.1),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(defaultRecipe(develop)).isEmpty())
    }

    @Test
    fun unappliedNodeWarnings_masterAndChannelCurvesApplied_noWarnings() {
        // master カーブも R/G/B チャンネルカーブも適用済みノードのため偽警告にしない
        // （PR-23 までの「channel 警告」は PR-28a の画素適用開始で撤去。仕様どおり）。
        val master = defaultRecipe().copy(
            develop = defaultDevelop().copy(
                curve = EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.0), listOf(0.5, 0.6), listOf(1.0, 1.0))),
            ),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(master).isEmpty())
        val channels = defaultRecipe().copy(
            develop = defaultDevelop().copy(
                curve = EditRecipe.Curve(
                    rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                    r = listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)),
                    b = listOf(listOf(0.0, 0.0), listOf(1.0, 0.85)),
                ),
            ),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(channels).isEmpty())
    }

    @Test
    fun applyToPixels_channelCurve_liftsOnlyTargetChannelWithinQuantization() {
        // R チャンネルカーブ（中間点リフト）: R は明るく、G/B は量子化差の範囲で不変。
        val pixels = IntArray(256) { i -> pixel(0xFF, i, i, i) }
        RecipeDevelopApplier.applyToPixels(
            pixels,
            defaultDevelop().copy(
                curve = EditRecipe.Curve(
                    rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                    r = listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)),
                ),
            ),
        )
        for (i in 48..208) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            assertTrue("R should lift at $i", r >= i)
            assertTrue("R should strictly lift at $i", r > i)
            abs(g - i).let { assertTrue("G drift $it at $i", it <= 1) }
            abs(b - i).let { assertTrue("B drift $it at $i", it <= 1) }
        }
    }

    @Test
    fun unappliedNodeWarnings_geometryAppliedNodes_only_noWarnings() {
        // 90°回転/反転/規定内クロップは適用済みノード（P4）→ 偽警告にしない。
        val recipe = defaultRecipe().copy(
            geometry = EditRecipe.GeometryParams(
                cropNorm = listOf(0.1, 0.2, 0.9, 1.0),
                angleDeg = 270.0,
                mirrorX = true,
                mirrorY = true,
            ),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(recipe).isEmpty())
    }

    @Test
    fun unappliedNodeWarnings_freeAngleApplied_masksOnlyWarns() {
        // PR-28b: 自由角度は適用済み（警告対象外）。マスクのみ未適用として警告される。
        val recipe = defaultRecipe().copy(
            geometry = EditRecipe.GeometryParams(angleDeg = 3.5),
            masks = listOf(EditRecipe.MaskNode(maskId = "m1", maskType = "linear")),
        )
        val warnings = RecipeDevelopApplier.unappliedNodeWarnings(recipe)
        assertFalse(warnings.any { it.contains("geometry") })
        assertTrue(warnings.any { it.contains("mask") })
        assertEquals(1, warnings.size)
    }

    @Test
    fun unappliedNodeWarnings_hslKnownKeyApplied_unknownKeyOrNullWarns() {
        // 正規化済み key（例 red.hue）は適用済み → 警告しない。
        val applied = defaultRecipe().copy(
            develop = defaultDevelop().copy(
                color = EditRecipe.ColorParams(hsl = mapOf("red.hue" to 0.1)),
            ),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(applied).isEmpty())
        // 未知 key は DB 側 PRESERVED_ONLY（値を保持・適用しない）→ 警告する。
        val foreign = defaultRecipe().copy(
            develop = defaultDevelop().copy(
                color = EditRecipe.ColorParams(hsl = mapOf("teal.sat" to 0.2)),
            ),
        )
        assertTrue(RecipeDevelopApplier.unappliedNodeWarnings(foreign).any { it.contains("HSL") })

        // PR-28c: sharpen/lumaNoise は適用済み → 警告しない。chromaNR のみ未適用として警告。
        val detailApplied = defaultDevelop().copy(detail = EditRecipe.Detail(sharpen = 0.5, lumaNoise = 0.3))
        assertTrue(
            RecipeDevelopApplier.unappliedNodeWarnings(
                defaultRecipe().copy(develop = detailApplied),
            ).isEmpty(),
        )
        val chromaOnly = defaultDevelop().copy(detail = EditRecipe.Detail(chromaNoise = 0.4))
        val warnings = RecipeDevelopApplier.unappliedNodeWarnings(
            defaultRecipe().copy(develop = chromaOnly),
        )
        assertTrue(warnings.any { it.contains("chroma") })
    }
}
