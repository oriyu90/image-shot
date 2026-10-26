package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PR-28c ディテール（シャープ / 輝度 NR）の JVM 固定値・性質テスト。
 * 空間 stage は develop 後・resize 前の共有関数 [RecipeDetailApplier] が正本。
 */
class RecipeDetailApplierTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun luma(p: Int): Double {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    @Test
    fun neutralDetail_isNoOpAndReportsNotApplied() {
        val pixels = IntArray(16) { argb(0xFF, it * 10, it * 10, it * 10) }
        val original = pixels.copyOf()
        val outcome = RecipeDetailApplier.applyToPixels(pixels, 4, 4, EditRecipe.Detail())
        assertFalse(outcome.applied)
        assertFalse(outcome.degraded)
        assertTrue(original.contentEquals(pixels))
    }

    @Test
    fun sharpen_increasesEdgeContrastOnBothSides() {
        // 5x5・左半分=64、右半分=192 の垂直エッジ。x=1（暗側）はさらに暗く、x=3（明側）は明るく。
        val w = 5
        val h = 5
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < 2) argb(0xFF, 64, 64, 64) else argb(0xFF, 192, 192, 192)
        }
        val before = pixels.copyOf()
        val outcome = RecipeDetailApplier.applyToPixels(pixels, w, h, EditRecipe.Detail(sharpen = 0.6))
        assertTrue(outcome.applied)
        assertFalse(outcome.degraded)

        val x1 = 1 // 暗側のエッジ隣
        val x2 = 2 // 明側のエッジ隣
        val y = 2
        val darkBefore = (before[y * w + x1] ushr 16) and 0xFF
        val darkAfter = (pixels[y * w + x1] ushr 16) and 0xFF
        val brightBefore = (before[y * w + x2] ushr 16) and 0xFF
        val brightAfter = (pixels[y * w + x2] ushr 16) and 0xFF
        assertTrue("dark side should darken: $darkBefore -> $darkAfter", darkAfter < darkBefore)
        assertTrue("bright side should brighten: $brightBefore -> $brightAfter", brightAfter > brightBefore)
        // 平坦部（x=0, x=4）は近傍が同一なので不変（端のクランプで blur=luma）。
        assertEquals(64, (pixels[y * w] ushr 16) and 0xFF)
        assertEquals(192, (pixels[y * w + 4] ushr 16) and 0xFF)
    }

    @Test
    fun lumaNoise_reducesHighFrequencyVariance() {
        // 市松ノイズ（100/140）を NR で平滑化し、輝度分散が下がることを確認する。
        val w = 32
        val h = 32
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = if ((x + y) % 2 == 0) 100 else 140
            argb(0xFF, v, v, v)
        }
        val beforeVar = variance(pixels)
        val outcome = RecipeDetailApplier.applyToPixels(pixels, w, h, EditRecipe.Detail(lumaNoise = 1.0))
        assertTrue(outcome.applied)
        val afterVar = variance(pixels)
        assertTrue("variance should drop: $beforeVar -> $afterVar", afterVar < beforeVar * 0.8)
    }

    @Test
    fun colourChromaIsPreservedAndAlphaUnchanged() {
        // 彩度のある平坦領域（NR で輝度は不変・色比も不変）と、エッジで RGB が同一率で伸縮することを確認。
        val w = 4
        val h = 4
        val pixels = IntArray(w * h) { i ->
            if (i % w < 2) argb(0x80, 120, 60, 30) else argb(0x80, 240, 120, 60)
        }
        val before = pixels.copyOf()
        RecipeDetailApplier.applyToPixels(pixels, w, h, EditRecipe.Detail(sharpen = 0.5))
        for (i in pixels.indices) {
            // クランプされた画素は比率が保存されない（原理的限界）ため対象外。
            if ((pixels[i] ushr 16 and 0xFF) == 255 || (pixels[i] ushr 8 and 0xFF) == 255 ||
                (pixels[i] and 0xFF) == 255
            ) {
                continue
            }
            val a = (pixels[i] ushr 24) and 0xFF
            assertEquals("alpha must stay unchanged at $i", 0x80, a)
            val r0 = (before[i] ushr 16) and 0xFF
            val g0 = (before[i] ushr 8) and 0xFF
            val b0 = before[i] and 0xFF
            val r1 = (pixels[i] ushr 16) and 0xFF
            val g1 = (pixels[i] ushr 8) and 0xFF
            val b1 = pixels[i] and 0xFF
            // 0 でない成分は同一率でスケール（丸め誤差 ±1）。
            if (r0 > 0 && r1 > 0 && g0 > 0 && g1 > 0) {
                assertTrue(
                    "ratio drift at $i: before($r0,$g0,$b0) after($r1,$g1,$b1)",
                    abs(r1.toDouble() / r0 - g1.toDouble() / g0) < 0.04,
                )
            }
        }
    }

    @Test
    fun resultIsDeterministicForSameInput() {
        val w = 40
        val h = 30
        fun input() = IntArray(w * h) { i ->
            val v = (i * 37) % 256
            argb(0xFF, v, (v * 3) % 256, (v * 7) % 256)
        }
        val a = input()
        val b = input()
        RecipeDetailApplier.applyToPixels(a, w, h, EditRecipe.Detail(sharpen = 0.7, lumaNoise = 0.2))
        RecipeDetailApplier.applyToPixels(b, w, h, EditRecipe.Detail(sharpen = 0.7, lumaNoise = 0.2))
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun stripBoundary_matchesInteriorContrast() {
        // 高さ 600（帯境界 256/512 を跨ぐ）で垂直エッジを処理し、境界行と内部行の応答が一致することを確認。
        val w = 8
        val h = 600
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < 4) argb(0xFF, 60, 60, 60) else argb(0xFF, 200, 200, 200)
        }
        RecipeDetailApplier.applyToPixels(pixels, w, h, EditRecipe.Detail(sharpen = 0.6))
        fun darkAt(y: Int) = (pixels[y * w + 3] ushr 16) and 0xFF
        fun brightAt(y: Int) = (pixels[y * w + 4] ushr 16) and 0xFF
        for (y in listOf(10, 255, 256, 511, 512, 590)) {
            assertEquals("dark row=$y", darkAt(10), darkAt(y))
            assertEquals("bright row=$y", brightAt(10), brightAt(y))
        }
    }

    @Test
    fun lightPathIsSelectedByPixelBudgetNotTiming() {
        assertFalse(RecipeDetailApplier.usesLightPath(2048, 1365)) // 2.79MP
        assertFalse(RecipeDetailApplier.usesLightPath(4000, 3000)) // 12.0MP（境界は通常経路）
        assertTrue(RecipeDetailApplier.usesLightPath(4001, 3000)) // 12.003MP
        assertTrue(RecipeDetailApplier.usesLightPath(8000, 6000)) // 48MP
    }

    @Test
    fun lightPath_processesAboveBudgetAndReportsDegraded() {
        // 12MP 超（4001×3000）で軽量経路が実際に走り、例外なく完了して degraded を返すこと。
        // 48MP 実画像は fixture 待ちだが、閾値超過側のコード経路はここで固定する。
        val w = 4001
        val h = 3000
        val pixels = IntArray(w * h) { i -> argb(0xFF, i % 256, (i / 7) % 256, 128) }
        val outcome = RecipeDetailApplier.applyToPixels(
            pixels,
            w,
            h,
            EditRecipe.Detail(sharpen = 0.5),
        )
        assertTrue(outcome.applied)
        assertTrue(outcome.degraded)
    }

    @Test
    fun sharpenAndNoiseCancel_outIsNoOp() {
        // sharpen=0.4 (0.8) と lumaNoise=1.0 (0.8) は同一差分項で相殺され、画素は不変。
        val pixels = IntArray(64) { i -> argb(0xFF, i * 3 % 256, 20, 40) }
        val original = pixels.copyOf()
        RecipeDetailApplier.applyToPixels(
            pixels,
            8,
            8,
            EditRecipe.Detail(sharpen = 0.4, lumaNoise = 1.0),
        )
        assertTrue(original.contentEquals(pixels))
    }

    @Test
    fun nonFiniteParametersAreTreatedAsNeutral() {
        val pixels = IntArray(16) { argb(0xFF, 1, 2, 3) }
        val original = pixels.copyOf()
        val outcome = RecipeDetailApplier.applyToPixels(
            pixels,
            4,
            4,
            EditRecipe.Detail(sharpen = Double.NaN, lumaNoise = Double.POSITIVE_INFINITY),
        )
        assertFalse(outcome.applied)
        assertTrue(original.contentEquals(pixels))
    }

    private fun variance(pixels: IntArray): Double {
        val values = pixels.map { luma(it) }
        val mean = values.average()
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
}
