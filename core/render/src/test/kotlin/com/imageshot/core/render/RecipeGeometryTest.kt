package com.imageshot.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ジオメトリ（RecipeGeometry・純Kotlin ピクセル再索引）の座標テスト。
 * 変換順（回転 → 反転 → クロップ）と各写像の逆写像を、個別値をもつ画素パターンで検証する。
 */
class RecipeGeometryTest {

    /** 2×3 平面（w=2, h=3）。個別値 A..F を保持（検証の取り違え防止）。 */
    private fun plane23(): RecipeGeometry.PixPlane {
        val pixels = intArrayOf(
            1, 2,
            3, 4,
            5, 6,
        )
        return RecipeGeometry.PixPlane(pixels, 2, 3)
    }

    // ---- 回転（90°CW）----

    @Test
    fun rotate90cw_mapsDistinctPixelsAndSwapsDimensions() {
        val out = RecipeGeometry.rotateCw90(plane23(), 1)
        assertEquals(3, out.width)
        assertEquals(2, out.height)
        // 視覚（90°CW）:
        //   1 2          5 3 1
        //   3 4   →      6 4 2
        //   5 6
        assertEquals(listOf(5, 3, 1, 6, 4, 2).toIntArray().toList(), out.pixels.toList())
    }

    @Test
    fun rotate90cw_twiceAndThrice_matches180AndCcw() {
        val twice = RecipeGeometry.rotateCw90(plane23(), 2)
        // 180°:
        //   1 2          6 5
        //   3 4   →      4 3
        //   5 6          2 1
        assertEquals(listOf(6, 5, 4, 3, 2, 1).toIntArray().toList(), twice.pixels.toList())
        assertEquals(2, twice.width)
        assertEquals(3, twice.height)

        val ccw = RecipeGeometry.rotateCw90(plane23(), -1)
        // 90°CCW:
        //   1 2          2 4 6
        //   3 4   →      1 3 5
        //   5 6
        assertEquals(listOf(2, 4, 6, 1, 3, 5).toIntArray().toList(), ccw.pixels.toList())

        // 4回で元に戻る（恒等写像の往復）。
        val full = RecipeGeometry.rotateCw90(plane23(), 4)
        assertEquals(plane23().pixels.toList(), full.pixels.toList())
        assertEquals(2, full.width)
        assertEquals(3, full.height)
    }

    // ---- 反転 ----

    @Test
    fun flipHorizontalAndVertical_mapDistinctPixels() {
        val fh = RecipeGeometry.flipHorizontal(plane23())
        // 左右: 1 2 / 3 4 / 5 6 → 2 1 / 4 3 / 6 5
        assertEquals(listOf(2, 1, 4, 3, 6, 5).toIntArray().toList(), fh.pixels.toList())
        val fv = RecipeGeometry.flipVertical(plane23())
        // 上下: → 5 6 / 3 4 / 1 2
        assertEquals(listOf(5, 6, 3, 4, 1, 2).toIntArray().toList(), fv.pixels.toList())
    }

    // ---- クロップ ----

    @Test
    fun crop_extractsRectAndMinGuaranteesOnePixel() {
        val cropped = RecipeGeometry.crop(
            plane23(),
            listOf(0.5, 0.0, 1.0, 0.666),
        )
        // px 座標（w=2,h=3）: left=round(1.0)=1, right=2, top=0, bottom=round(1.998)=2 → 1×2（右列上2行）。
        assertEquals(1, cropped.width)
        assertEquals(2, cropped.height)
        assertEquals(listOf(2, 4).toIntArray().toList(), cropped.pixels.toList())

        val tiny = RecipeGeometry.crop(plane23(), listOf(0.99, 0.99, 1.0, 1.0))
        assertEquals(1, tiny.width)
        assertEquals(1, tiny.height)
        assertEquals(6, tiny.pixels[0])
    }

    // ---- 適用合成（回転 → 反転 → クロップの順序固定）----

    @Test
    fun apply_rotationThenFlipThenCrop_inDocumentedOrder() {
        // 回転90°CW（2×3→3×2）→ 左右反転 → 右側クロップ。
        // 回転後: 5 3 1 / 6 4 2 → 反転: 1 3 5 / 2 4 6（w=3）。
        // クロップ x0.5..1.0: left=round(1.5)=2, right=3, top=0, bottom=2 → 1×2 = 5 / 6。
        val out = RecipeGeometry.apply(
            plane23(),
            90.0,
            mirrorX = true,
            mirrorY = false,
            cropNorm = listOf(0.5, 0.0, 1.0, 1.0),
        )
        assertEquals(1, out.width)
        assertEquals(2, out.height)
        assertEquals(listOf(5, 6).toIntArray().toList(), out.pixels.toList())
    }

    @Test
    fun apply_quarterTurns_usesAngleDegAndNormalizesNegativeValues() {
        assertEquals(0, RecipeGeometry.quarterTurns(0.0))
        assertEquals(1, RecipeGeometry.quarterTurns(90.0))
        assertEquals(2, RecipeGeometry.quarterTurns(180.0))
        assertEquals(3, RecipeGeometry.quarterTurns(270.0))
        // PR-28b: 負角・360°超は mod 360 で同じ回転へ畳み込む。
        assertEquals(3, RecipeGeometry.quarterTurns(-90.0))
        assertEquals(0, RecipeGeometry.quarterTurns(360.0))
        assertEquals(1, RecipeGeometry.quarterTurns(450.0))
        // 90°刻みでない角度は任意角経路（quarter=0 の「回転なし」ではない）。
        assertEquals(0, RecipeGeometry.quarterTurns(12.5))
    }

    // ---- 任意角回転（PR-28b・黒縁付き外接矩形+バイリニア逆写像）----

    /** 4×2 平面（w=4, h=2）。ARGB 個別値（固定値 golden 用）。 */
    private fun plane42(): RecipeGeometry.PixPlane {
        val pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
            0xFF000000.toInt(), 0xFF808080.toInt(), 0xFF804020.toInt(), 0xFF204080.toInt(),
        )
        return RecipeGeometry.PixPlane(pixels, 4, 2)
    }

    @Test
    fun outputSize_matchesRotatedBoundingBox() {
        assertEquals(4 to 2, RecipeGeometry.outputSize(4, 2, 0.0))
        assertEquals(2 to 4, RecipeGeometry.outputSize(4, 2, 90.0))
        assertEquals(4 to 2, RecipeGeometry.outputSize(4, 2, 180.0))
        assertEquals(2 to 4, RecipeGeometry.outputSize(4, 2, 270.0))
        // 30°: ceil(4·√3/2 + 2·1/2)=ceil(4.464)=5 / ceil(4·1/2 + 2·√3/2)=ceil(3.732)=4
        assertEquals(5 to 4, RecipeGeometry.outputSize(4, 2, 30.0))
        // 360° は 0° と同じ。
        assertEquals(4 to 2, RecipeGeometry.outputSize(4, 2, 360.0))
    }

    @Test
    fun rotateArbitrary_30deg_matchesFixedGoldenPixels() {
        // 実装から生成した固定値（4×2 を 30°CW・外接矩形 5×4・被覆外は不透明黒）。
        // 検算: 行0/行3 は全黒、内容は右下がり（CW 回転）に並ぶ。
        val out = RecipeGeometry.rotateArbitrary(plane42(), 30.0)
        assertEquals(5, out.width)
        assertEquals(4, out.height)
        val expected = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF60461C.toInt(), 0xFF09BA42.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF774F41.toInt(), 0xFF7775B8.toInt(), 0xFF000000.toInt(),
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
        )
        org.junit.Assert.assertArrayEquals(expected, out.pixels)
    }

    @Test
    fun rotateArbitrary_isPeriodicModulo360() {
        val base = RecipeGeometry.rotateArbitrary(plane42(), 30.0)
        for (angle in listOf(390.0, -330.0, 750.0, -690.0)) {
            val out = RecipeGeometry.rotateArbitrary(plane42(), angle)
            assertEquals("angle=$angle", base.width, out.width)
            assertEquals("angle=$angle", base.height, out.height)
            org.junit.Assert.assertArrayEquals("angle=$angle", base.pixels, out.pixels)
        }
    }

    @Test
    fun rotateArbitrary_quarterAnglesUseExactNoResamplePath() {
        val out = RecipeGeometry.rotateArbitrary(plane42(), 90.0)
        val exact = RecipeGeometry.rotateCw90(plane42(), 1)
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        org.junit.Assert.assertArrayEquals(exact.pixels, out.pixels)
        // 画素値が入力のまま（補間されていない）ことも確認する。
        for (v in out.pixels) {
            assertTrue("unexpected interpolated value: ${v.toUInt().toString(16)}", v in plane42().pixels.toList())
        }
    }

    @Test
    fun rotateArbitrary_fillsUncoveredCornersWithOpaqueBlack() {
        val out = RecipeGeometry.rotateArbitrary(plane42(), 30.0)
        assertEquals(RecipeGeometry.EDGE_FILL_ARGB, out.pixels[0])
        assertEquals(RecipeGeometry.EDGE_FILL_ARGB, out.pixels[out.width - 1])
        assertEquals(RecipeGeometry.EDGE_FILL_ARGB, out.pixels[out.pixels.size - 1])
    }

    @Test
    fun apply_arbitraryRotationThenFlipThenCrop_inDocumentedOrder() {
        // 回転（5×4）→ 左右反転 → 正規化 crop [0.4,0.25,0.6,0.75]
        //   left=round(2.0)=2, right=round(3.0)=3, top=round(1.0)=1, bottom=round(3.0)=3 → 1×2。
        // 手順の正本（回転→反転→クロップ）を、公開関数の合成結果と照合する。
        val rotated = RecipeGeometry.rotateArbitrary(plane42(), 30.0)
        val flipped = RecipeGeometry.flipHorizontal(rotated)
        val out = RecipeGeometry.apply(
            plane42(),
            angleDeg = 30.0,
            mirrorX = true,
            mirrorY = false,
            cropNorm = listOf(0.4, 0.25, 0.6, 0.75),
        )
        assertEquals(1, out.width)
        assertEquals(2, out.height)
        assertEquals(flipped.pixels[1 * 5 + 2], out.pixels[0])
        assertEquals(flipped.pixels[2 * 5 + 2], out.pixels[1])
    }

    @Test
    fun applyIfNeeded_freeAngleProducesNewPlane_fullTurnIsIdentity() {
        val src = plane42()
        val rotated = RecipeGeometry.applyIfNeeded(src, 12.5, false, false, listOf(0.0, 0.0, 1.0, 1.0))
        assertTrue(rotated !== src)

        val identity = RecipeGeometry.applyIfNeeded(src, 360.0, false, false, listOf(0.0, 0.0, 1.0, 1.0))
        assertTrue(identity === src)
    }

    // ---- 正準写像としての develop 順との合成可換性（点操作 develop は座標非依存）----
    // per-pixel develop が画素ごとの写像であることを 2×3 パターン上で検証する:
    // geometry→develop = develop→geometry（スライダー/カーブ/HSL は画素位置を参照しない）。

    @Test
    fun geometry_developAppliedPerPixel_commutesWithRotation() {
        // 点操作 develop（温度/露出/彩度）は画素位置を参照しない（座標非依存の界数）ため、
        // geometry→develop = develop→geometry。2×3 パターン上で厳密検証する。
        val develop = EditRecipeSample()
        val src1 = plane23().pixels.copyOf()
        val after = RecipeGeometry.rotateCw90(RecipeGeometry.PixPlane(src1, 2, 3), 1)
        RecipeDevelopApplier.applyToPixels(after.pixels, develop)
        val src2 = plane23().pixels.copyOf()
        RecipeDevelopApplier.applyToPixels(src2, develop)
        val expected = RecipeGeometry.rotateCw90(RecipeGeometry.PixPlane(src2, 2, 3), 1)
        assertEquals(expected.pixels.toList(), after.pixels.toList())
    }

    /** テスト用の非中立 develop 由来（R/G/B 異なる写像を作る温度）。 */
    private fun EditRecipeSample() = com.imageshot.core.model.EditRecipe.DevelopParams(
        whiteBalance = com.imageshot.core.model.EditRecipe.WhiteBalance(tempKelvin = 4800.0),
        tone = com.imageshot.core.model.EditRecipe.Tone(exposureEv = 0.5),
        color = com.imageshot.core.model.EditRecipe.ColorParams(vibrance = 0.3, saturation = 0.1),
    )
}
