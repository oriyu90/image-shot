package com.imageshot.core.render

import com.imageshot.core.model.EditRecipe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecipeMaskApplier] / [MaskWeightCache] の JVM 検証（PR-29b）。
 *
 * 契約:
 * - masks 空は完全 no-op（既存レシピの出力が1bitも変わらない）。
 * - 逐次合成（前のマスクの出力が次の base）・`w<=0` は不変・`w>=1` は adj・アルファ不変。
 * - 正規化できないマスクはスキップし件数で明示（無通知に捨てない）。
 * - 重みキャッシュは重みに影響する内容のみで無効化され、帯処理経路と同一結果を返す。
 */
class RecipeMaskApplierTest {

    // ---------------------------------------------------------------- 素材

    /** 決定的なグラデーション画素（アルファも画素位置で変化させる）。 */
    private fun ramp(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = (x * 255) / maxOf(width - 1, 1)
        val g = (y * 255) / maxOf(height - 1, 1)
        val b = ((x + y) * 255) / maxOf(width + height - 2, 1)
        val a = if ((x + y) % 2 == 0) 0xFF else 0x80
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 全画素 w=1 になるマスク（正規化座標の最大距離 sqrt(0.5) < rx=1）。 */
    private fun fullRadial(
        adjustments: Map<String, Double>,
        id: String = "full",
        feather: Double = 0.0,
    ) = EditRecipe.MaskNode(
        maskId = id,
        maskType = "radial",
        parameters = mapOf(
            "cx" to 0.5,
            "cy" to 0.5,
            "rx" to 1.0,
            "ry" to 1.0,
            "angleDeg" to 0.0,
            "feather" to feather,
        ),
        adjustments = adjustments,
    )

    /** 中央 1 点だけ w=1・他は 0 のマスク。 */
    private fun tinyRadial(adjustments: Map<String, Double>, id: String = "tiny") = EditRecipe.MaskNode(
        maskId = id,
        maskType = "radial",
        parameters = mapOf(
            "cx" to 0.5,
            "cy" to 0.5,
            "rx" to 0.05,
            "ry" to 0.05,
            "angleDeg" to 0.0,
            "feather" to 0.0,
        ),
        adjustments = adjustments,
    )

    private fun gray(pixel: Int): Boolean {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return kotlin.math.abs(r - g) <= 1 && kotlin.math.abs(g - b) <= 1 && kotlin.math.abs(r - b) <= 1
    }

    // ---------------------------------------------------------------- no-op / スキップ

    @Test
    fun emptyMasks_isExactNoOp() {
        val pixels = ramp(16, 12)
        val before = pixels.copyOf()
        val outcome = RecipeMaskApplier.applyToPixels(pixels, 16, 12, emptyList())
        assertEquals(0, outcome.maskCount)
        assertEquals(0, outcome.applied)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun maskWithoutAdjustments_isIdentity() {
        val pixels = ramp(16, 12)
        val before = pixels.copyOf()
        // adjustments 空 = 恒等（重みを計算しても out == base）。
        val mask = fullRadial(adjustments = emptyMap())
        val outcome = RecipeMaskApplier.applyToPixels(pixels, 16, 12, listOf(mask))
        assertEquals(1, outcome.applied)
        assertTrue(outcome.isFullyApplied)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun invalidMasks_areSkippedAndCounted() {
        val pixels = ramp(16, 12)
        val before = pixels.copyOf()
        val invalid = listOf(
            EditRecipe.MaskNode(maskId = "u", maskType = "vignette", adjustments = mapOf("develop.tone.exposureEv" to 1.0)),
            // 範囲外 adjustment
            tinyRadial(adjustments = mapOf("develop.tone.contrast" to 2.0), id = "oob"),
        )
        val outcome = RecipeMaskApplier.applyToPixels(pixels, 16, 12, invalid)
        assertEquals(2, outcome.maskCount)
        assertEquals(0, outcome.applied)
        assertEquals(2, outcome.unapplied)
        assertFalse(outcome.isFullyApplied)
        assertArrayEquals(before, pixels)
    }

    @Test
    fun mixedValidAndInvalid_appliesNoneToAvoidPartialState() {
        val pixels = ramp(16, 12)
        val before = pixels.copyOf()
        val mixed = listOf(
            tinyRadial(
                adjustments = mapOf("develop.tone.exposureEv" to 1.0),
                id = "valid",
            ),
            EditRecipe.MaskNode(maskId = "bad", maskType = "vignette"),
        )
        val outcome = RecipeMaskApplier.applyToPixels(pixels, 16, 12, mixed)
        assertEquals(2, outcome.maskCount)
        assertEquals(0, outcome.applied)
        assertArrayEquals(before, pixels)
    }

    // ---------------------------------------------------------------- 適用（w=0 / w=1）

    @Test
    fun weightOne_appliesMaskAdjustmentsExactlyLikeDevelop() {
        val width = 24
        val height = 16
        val base = ramp(width, height)
        val adjustments = mapOf(
            "develop.tone.exposureEv" to 0.7,
            "develop.tone.contrast" to 0.2,
            "develop.color.saturation" to -0.5,
        )
        // w=1 の全面マスクは develop 適用と同一結果（blend は adj をそのまま採る）。
        val masked = base.copyOf()
        RecipeMaskApplier.applyToPixels(masked, width, height, listOf(fullRadial(adjustments)))
        val reference = base.copyOf()
        RecipeDevelopApplier.applyToPixels(reference, RecipeMaskApplier.developOf(adjustments))
        assertArrayEquals(reference, masked)
    }

    @Test
    fun weightZero_leavesPixelsByteIdentical() {
        val width = 24
        val height = 16
        val pixels = ramp(width, height)
        val before = pixels.copyOf()
        // 中央だけを覆うマスク（外周は w=0）。露出 +5 でも外周は完全に不変。
        RecipeMaskApplier.applyToPixels(
            pixels,
            width,
            height,
            listOf(tinyRadial(adjustments = mapOf("develop.tone.exposureEv" to 5.0))),
        )
        // 外周（0,0）は w=0 → 不変。
        assertEquals(before[0], pixels[0])
        // 中央は変化する（rx=0.05 の中）。
        val center = (height / 2) * width + (width / 2)
        assertNotEquals(before[center], pixels[center])
    }

    @Test
    fun alphaIsPreservedForPartialAndFullWeights() {
        val width = 20
        val height = 10
        val pixels = ramp(width, height)
        val alphas = IntArray(pixels.size) { (pixels[it] ushr 24) and 0xFF }
        RecipeMaskApplier.applyToPixels(
            pixels,
            width,
            height,
            listOf(
                fullRadial(mapOf("develop.tone.exposureEv" to -1.0), id = "a"),
                tinyRadial(mapOf("develop.color.saturation" to -1.0), id = "b"),
            ),
        )
        for (i in pixels.indices) {
            assertEquals("alpha at $i", alphas[i], (pixels[i] ushr 24) and 0xFF)
        }
    }

    @Test
    fun colorAdjustment_desaturatesCoveredRegionOnly() {
        val width = 20
        val height = 10
        val pixels = ramp(width, height)
        val before = pixels.copyOf()
        RecipeMaskApplier.applyToPixels(
            pixels,
            width,
            height,
            listOf(fullRadial(mapOf("develop.color.saturation" to -1.0))),
        )
        // 全面マスク → 全画素が無彩色（±1 の量子化差のみ）。
        assertTrue(pixels.all { gray(it) })
        // 参考: 元画像は無彩色ではない。
        assertTrue(before.any { !gray(it) })
        // アルファは不変。
        for (i in pixels.indices) {
            assertEquals((before[i] ushr 24) and 0xFF, (pixels[i] ushr 24) and 0xFF)
        }
    }

    // ---------------------------------------------------------------- 合成順

    @Test
    fun compositionIsSequential_prefixEqualsStepwise() {
        val width = 18
        val height = 12
        val m1 = fullRadial(mapOf("develop.color.saturation" to -0.8), id = "m1")
        val m2 = fullRadial(mapOf("develop.tone.contrast" to 0.6), id = "m2")

        val both = ramp(width, height)
        RecipeMaskApplier.applyToPixels(both, width, height, listOf(m1, m2))

        val stepwise = ramp(width, height)
        RecipeMaskApplier.applyToPixels(stepwise, width, height, listOf(m1))
        RecipeMaskApplier.applyToPixels(stepwise, width, height, listOf(m2))

        assertArrayEquals(stepwise, both)
    }

    @Test
    fun compositionOrderMatters() {
        val width = 18
        val height = 12
        val m1 = fullRadial(mapOf("develop.color.saturation" to -1.0), id = "m1")
        val m2 = fullRadial(mapOf("develop.tone.contrast" to 1.0), id = "m2")

        val forward = ramp(width, height)
        RecipeMaskApplier.applyToPixels(forward, width, height, listOf(m1, m2))
        val reverse = ramp(width, height)
        RecipeMaskApplier.applyToPixels(reverse, width, height, listOf(m2, m1))

        assertFalse(
            "合成順が結果に影響しないなら順序契約が壊れている",
            forward.contentEquals(reverse),
        )
    }

    @Test
    fun determinism_sameInputSameOutput() {
        val masks = listOf(
            fullRadial(mapOf("develop.tone.exposureEv" to 0.4), id = "a"),
            EditRecipe.MaskNode(
                maskId = "b",
                maskType = "linear",
                parameters = mapOf(
                    "x0" to 0.1, "y0" to 0.1, "x1" to 0.9, "y1" to 0.9, "feather" to 0.6,
                ),
                adjustments = mapOf("develop.whiteBalance.tempKelvin" to 7200.0, "develop.color.vibrance" to 0.3),
            ),
        )
        val first = ramp(21, 13)
        val second = ramp(21, 13)
        val outcomeA = RecipeMaskApplier.applyToPixels(first, 21, 13, masks)
        val outcomeB = RecipeMaskApplier.applyToPixels(second, 21, 13, masks)
        assertArrayEquals(first, second)
        assertEquals(outcomeA, outcomeB)
    }

    // ---------------------------------------------------------------- キャッシュ・帯処理

    @Test
    fun weightCache_reusesEntryAndInvalidatesOnParameterChange() {
        MaskWeightCache.clear()
        val mask = fullRadial(mapOf("develop.tone.exposureEv" to 0.3), id = "cache", feather = 0.5)
        val pixels = ramp(8, 8)
        RecipeMaskApplier.applyToPixels(pixels, 8, 8, listOf(mask))
        assertEquals(1, MaskWeightCache.entryCount())
        val bytes = MaskWeightCache.sizeBytes()
        assertEquals(8L * 8L * 4L, bytes)

        // 同一内容はキャッシュヒット（エントリ増なし）。
        RecipeMaskApplier.applyToPixels(pixels, 8, 8, listOf(mask))
        assertEquals(1, MaskWeightCache.entryCount())
        assertEquals(bytes, MaskWeightCache.sizeBytes())

        // 調整値だけの変更は重みに影響しない → 新規エントリを作らない。
        val adjustmentsOnly = mask.copy(adjustments = mapOf("develop.tone.exposureEv" to -1.0))
        RecipeMaskApplier.applyToPixels(pixels, 8, 8, listOf(adjustmentsOnly))
        assertEquals(1, MaskWeightCache.entryCount())

        // パラメータ変更（feather）は別キー → 新規エントリ。
        val featherChanged = mask.copy(parameters = mask.parameters + ("feather" to 0.9))
        assertNotEquals(
            MaskWeightCache.keyOf(mask, 8, 8),
            MaskWeightCache.keyOf(featherChanged, 8, 8),
        )
        RecipeMaskApplier.applyToPixels(pixels, 8, 8, listOf(featherChanged))
        assertEquals(2, MaskWeightCache.entryCount())

        // 画像寸法もキーに含まれる。
        RecipeMaskApplier.applyToPixels(ramp(9, 8), 9, 8, listOf(mask))
        assertEquals(3, MaskWeightCache.entryCount())

        MaskWeightCache.clear()
        assertEquals(0, MaskWeightCache.entryCount())
        assertEquals(0L, MaskWeightCache.sizeBytes())
    }

    @Test
    fun weightCache_evictsOldestWhenBudgetExceeded() {
        MaskWeightCache.clear()
        // 1エントリ 4MB（1000x1000 は 4,000,000 bytes = ちょうど上限内）。
        // 7 件（28MB）投入して 24MB 以下に収まることを確認する。
        repeat(7) { index ->
            val mask = fullRadial(mapOf("develop.tone.exposureEv" to 0.1), id = "big$index")
            MaskWeightCache.weights(mask, 1000, 1000)
        }
        assertTrue(
            "cache bytes=${MaskWeightCache.sizeBytes()}",
            MaskWeightCache.sizeBytes() <= MaskWeightCache.MAX_BYTES,
        )
        assertTrue(MaskWeightCache.entryCount() in 1..6)
        MaskWeightCache.clear()
    }

    @Test
    fun bandedPath_matchesCachedPathAndIsBudgetFree() {
        MaskWeightCache.clear()
        val width = 17
        val height = 11
        val masks = listOf(
            fullRadial(mapOf("develop.tone.exposureEv" to 0.6), id = "band-a", feather = 0.4),
            EditRecipe.MaskNode(
                maskId = "band-b",
                maskType = "brush",
                parameters = mapOf("feather" to 0.5),
                adjustments = mapOf("develop.color.saturation" to -0.6),
                brushStrokes = listOf(
                    EditRecipe.BrushStroke(0.3, 0.4, 0.3, 0.9),
                    EditRecipe.BrushStroke(0.7, 0.8, 0.2, 0.5),
                ),
            ),
        )
        val cached = ramp(width, height)
        RecipeMaskApplier.applyToPixels(cached, width, height, masks)
        assertTrue(MaskWeightCache.entryCount() > 0)
        MaskWeightCache.clear()

        val banded = ramp(width, height)
        // 帯行数を 3 に縮めて帯処理経路を強制（>4MP の実経路と同一コード）。
        val outcome = RecipeMaskApplier.applyToPixelsInternal(
            pixels = banded,
            width = width,
            height = height,
            masks = masks,
            forceBanded = true,
            bandRows = 3,
        )
        assertEquals(2, outcome.applied)
        assertArrayEquals(cached, banded)
        // 帯処理はキャッシュを汚さない。
        assertEquals(0, MaskWeightCache.entryCount())
    }

    // ---------------------------------------------------------------- adjustments → develop

    @Test
    fun developOf_mapsRecipeDomainValuesAndKeepsUnsetNeutral() {
        val neutral = RecipeMaskApplier.developOf(emptyMap())
        assertEquals(EditRecipe.DevelopParams(), neutral)

        val tuned = RecipeMaskApplier.developOf(
            mapOf(
                "develop.tone.exposureEv" to 0.7,
                "develop.tone.contrast" to 0.2,
                "develop.tone.highlights" to -0.3,
                "develop.tone.shadows" to 0.4,
                "develop.tone.whites" to 0.1,
                "develop.tone.blacks" to -0.1,
                "develop.whiteBalance.tempKelvin" to 6500.0,
                "develop.whiteBalance.tint" to -0.2,
                "develop.color.vibrance" to 0.3,
                "develop.color.saturation" to -0.5,
            ),
        )
        assertEquals(0.7, tuned.tone.exposureEv, 0.0)
        assertEquals(0.2, tuned.tone.contrast, 0.0)
        assertEquals(-0.3, tuned.tone.highlights, 0.0)
        assertEquals(0.4, tuned.tone.shadows, 0.0)
        assertEquals("custom", tuned.whiteBalance.mode)
        assertEquals(6500.0, tuned.whiteBalance.tempKelvin!!, 0.0)
        assertEquals(-0.2, tuned.whiteBalance.tint, 0.0)
        assertEquals(0.3, tuned.color.vibrance, 0.0)
        assertEquals(-0.5, tuned.color.saturation, 0.0)
        // マスクは curve/detail を触らない（レシピ側の値は常に中立）。
        assertEquals(EditRecipe.Curve(), tuned.curve)
        assertEquals(EditRecipe.Detail(), tuned.detail)

        // 温度未指定は as-shot（null）。
        assertEquals("as-shot", RecipeMaskApplier.developOf(mapOf("develop.tone.contrast" to 0.1)).whiteBalance.mode)
        // 非有限は中立へ落とす（防御）。
        assertEquals(
            0.0,
            RecipeMaskApplier.developOf(mapOf("develop.tone.exposureEv" to Double.NaN)).tone.exposureEv,
            0.0,
        )
    }
}
