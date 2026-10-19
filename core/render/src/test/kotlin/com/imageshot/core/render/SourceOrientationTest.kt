package com.imageshot.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXIF orientation（1..8）ベースの正規化（SourceOrientation・純Kotlin）の座標テスト。
 * 3×2 の非対称グリッド（値 1..6）に対し、androidx ExifInterface の TAG_ORIENTATION 標準語彙
 * （「正しく表示したとき 0th row / 0th column がどの辺に来るか」）から手計算で導いた
 * 期待配列をリテラルで突き合わせる。5（transpose）/7（transverse）は語彙からの導出値で確認する
 * （記憶頼みでなく、導出コメント付き）。既存プリミティブ（RecipeGeometry の回転/反転）の
 * 再利用確認も含む。
 */
class SourceOrientationTest {

    /** w=3, h=2 の平面。値は全て異なる（取り違え検出用）。 */
    private fun plane(): RecipeGeometry.PixPlane {
        val pixels = intArrayOf(
            1, 2, 3,
            4, 5, 6,
        )
        return RecipeGeometry.PixPlane(pixels, 3, 2)
    }

    /** orientation → (期待ピクセル列, w, h)。コメントは各値の EXIF 語彙と導出の骨子。 */
    private val expected = mapOf(
        // 1: 0th row=top / 0th col=left（無変換）。
        1 to Triple(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2),
        // 2: 0th row=top / 0th col=right → 水平反転。
        2 to Triple(intArrayOf(3, 2, 1, 6, 5, 4), 3, 2),
        // 3: 0th row=bottom / 0th col=right → 180°。
        3 to Triple(intArrayOf(6, 5, 4, 3, 2, 1), 3, 2),
        // 4: 0th row=bottom / 0th col=left → 垂直反転。
        4 to Triple(intArrayOf(4, 5, 6, 1, 2, 3), 3, 2),
        // 5 (transpose): 0th row=visual 左 / 0th col=visual top。
        //   導出: (x_d,y_d)=(ys,xs)。回転90°CW → 水平反転で x が h−1−(h−1−ys)=ys に。
        //   stored `1 2 3 / 4 5 6` → visual `1 4 / 2 5 / 3 6`。
        5 to Triple(intArrayOf(1, 4, 2, 5, 3, 6), 2, 3),
        // 6: 0th row=visual 右 / 0th col=visual top → 回転90°CW のみ。
        //   導出: (x_d,y_d)=(h−1−ys,xs)。row0: ys=0 → x_d=h−1（右端）/ col0: xs=0 → y_d=0（上端）。
        //   stored `1 2 3 / 4 5 6` → visual `4 1 / 5 2 / 6 3`。
        6 to Triple(intArrayOf(4, 1, 5, 2, 6, 3), 2, 3),
        // 7 (transverse): 0th row=visual 右 / 0th col=visual bottom。
        //   導出: (x_d,y_d)=(h−1−ys, w−1−xs)。回転90°CW → 垂直反転で y が w−1−xs に。
        //   stored `1 2 3 / 4 5 6` → visual `6 3 / 5 2 / 4 1`（= transpose の 180°回転）。
        7 to Triple(intArrayOf(6, 3, 5, 2, 4, 1), 2, 3),
        // 8: 0th row=visual 左 / 0th col=visual bottom → 270°CW（=CCW90）。
        //   導出: (x_d,y_d)=(ys, w−1−xs)=回転3回。stored → visual `3 6 / 2 5 / 1 4`。
        8 to Triple(intArrayOf(3, 6, 2, 5, 1, 4), 2, 3),
    )

    @Test
    fun normalize_matchesHandDerivedGrid_forAllEightOrientations() {
        for (o in 1..8) {
            val (pixels, w, h) = expected.getValue(o)
            val out = SourceOrientation.normalize(plane(), o)
            assertEquals("orientation=$o width", w, out.width)
            assertEquals("orientation=$o height", h, out.height)
            assertEquals("orientation=$o pixels", pixels.toList(), out.pixels.toList())
        }
    }

    @Test
    fun normalize_dimensionSwap_onlyForOrientationsWithQuarterTurns() {
        // 1..4 は寸法不変。6 を含む 5/6/7/8 は w/h が入れ替わる。
        for (o in 1..4) {
            val out = SourceOrientation.normalize(plane(), o)
            assertEquals("orientation=$o w", 3, out.width)
            assertEquals("orientation=$o h", 2, out.height)
            assertFalse(SourceOrientation.toPlaneTransform(o).transposed)
        }
        for (o in 5..8) {
            val out = SourceOrientation.normalize(plane(), o)
            assertEquals("orientation=$o w", 2, out.width)
            assertEquals("orientation=$o h", 3, out.height)
            assertTrue(SourceOrientation.toPlaneTransform(o).transposed)
        }
    }

    @Test
    fun toPlaneTransform_minimalCompositions() {
        // 適用順（正本）: 90°CW×q → mirrorX → mirrorY。
        // 1:(0,F,F) 2:(0,T,F) 3:(0,T,T) 4:(0,F,T) 5:(1,T,F) 6:(1,F,F) 7:(1,F,T) 8:(3,F,F)。
        fun assertPlane(o: Int, q: Int, mx: Boolean, my: Boolean) {
            val t = SourceOrientation.toPlaneTransform(o)
            assertEquals("orientation=$o quarter", q, t.quarterTurnsCw)
            assertEquals("orientation=$o mirrorX", mx, t.mirrorX)
            assertEquals("orientation=$o mirrorY", my, t.mirrorY)
        }
        assertPlane(1, 0, false, false)
        assertPlane(2, 0, true, false)
        assertPlane(3, 0, true, true)
        assertPlane(4, 0, false, true)
        assertPlane(5, 1, true, false)
        assertPlane(6, 1, false, false)
        assertPlane(7, 1, false, true)
        assertPlane(8, 3, false, false)
    }

    @Test
    fun unknownOrientation_isIdentity_noFailure() {
        // 不明（0）・範囲外は恒等（失敗せず・正規化もしない — 不明は不明のままで呼出し側が警告する）。
        for (unknown in intArrayOf(0, 9, -1, Int.MAX_VALUE)) {
            val t = SourceOrientation.toPlaneTransform(unknown)
            assertEquals(0, t.quarterTurnsCw)
            assertFalse(t.mirrorX)
            assertFalse(t.mirrorY)
            val out = SourceOrientation.normalize(plane(), unknown)
            assertEquals(3, out.width)
            assertEquals(2, out.height)
            assertEquals(intArrayOf(1, 2, 3, 4, 5, 6).toList(), out.pixels.toList())
        }
    }

    @Test
    fun normalize_reusesExistingGeometryPrimitives_transposeEqualsRotateThenFlip() {
        // 5（transpose）= 回転90°CW → 水平反転（RecipeGeometry 直接合成と同値 — 実装が
        // 同じピクセル索引コードを再利用していることの構造的確認）。
        val viaNormalizer = SourceOrientation.normalize(plane(), 5)
        val viaCompose = RecipeGeometry.flipHorizontal(RecipeGeometry.rotateCw90(plane(), 1))
        assertEquals(viaCompose.width, viaNormalizer.width)
        assertEquals(viaCompose.height, viaNormalizer.height)
        assertEquals(viaCompose.pixels.toList(), viaNormalizer.pixels.toList())

        // 7（transverse）= 回転90°CW → 垂直反転。
        val viaNormalizer7 = SourceOrientation.normalize(plane(), 7)
        val viaCompose7 = RecipeGeometry.flipVertical(RecipeGeometry.rotateCw90(plane(), 1))
        assertEquals(viaCompose7.pixels.toList(), viaNormalizer7.pixels.toList())
    }

    @Test
    fun orientation6_swapsExactlyOneQuarterTurn_onTallSource() {
        // h=4, w=2 の縦長グリッドで orientation 6（90°CW）を再確認（寸法 2×4 → 4×2 の交換）。
        val p = RecipeGeometry.PixPlane(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 2, 4)
        val out = SourceOrientation.normalize(p, 6)
        assertEquals(4, out.width)
        assertEquals(2, out.height)
        // 90°CW（RecipeGeometryTest と同一規則・導出式 dst(x,y)=src(y,h−1−x)）:
        //   1 2        7 5 3 1
        //   3 4   →    8 6 4 2
        //   5 6
        //   7 8
        assertEquals(intArrayOf(7, 5, 3, 1, 8, 6, 4, 2).toList(), out.pixels.toList())
    }
}
