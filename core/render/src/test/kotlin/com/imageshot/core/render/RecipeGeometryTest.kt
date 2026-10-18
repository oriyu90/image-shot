package com.imageshot.core.render

import org.junit.Assert.assertEquals
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
        // 未対応角度（自由角度・負の折返し）は恒等に落とす（正規化は EditStore 側・警告側で通知）。
        assertEquals(0, RecipeGeometry.quarterTurns(-90.0))
        assertEquals(0, RecipeGeometry.quarterTurns(12.5))
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
