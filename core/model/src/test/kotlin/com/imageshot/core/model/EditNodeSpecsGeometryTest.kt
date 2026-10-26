package com.imageshot.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-28b: 自由角度ジオメトリの正規化・正準化・中立判定（[EditNodeSpecs]）の固定値テスト。
 * レンダラ側の任意角リサンプル（[com.imageshot.core.render.RecipeGeometry]）は
 * core:render の RecipeGeometryTest が担当する（ここは純粋な正規化のみ）。
 */
class EditNodeSpecsGeometryTest {

    // ---- canonicalAngleDeg ----

    @Test
    fun canonicalAngle_mapsToZeroTo360() {
        assertEquals(0.0, EditNodeSpecs.canonicalAngleDeg(0.0)!!, 0.0)
        assertEquals(0.0, EditNodeSpecs.canonicalAngleDeg(360.0)!!, 0.0)
        assertEquals(0.0, EditNodeSpecs.canonicalAngleDeg(720.0)!!, 0.0)
        assertEquals(0.0, EditNodeSpecs.canonicalAngleDeg(-360.0)!!, 0.0)
        assertEquals(270.0, EditNodeSpecs.canonicalAngleDeg(-90.0)!!, 0.0)
        assertEquals(90.0, EditNodeSpecs.canonicalAngleDeg(450.0)!!, 0.0)
        assertEquals(12.5, EditNodeSpecs.canonicalAngleDeg(372.5)!!, 0.0)
        assertEquals(359.9, EditNodeSpecs.canonicalAngleDeg(359.9)!!, 0.0)
        // -0.0 を値として残さない（payload の表記揺れ防止）。
        assertEquals(0.0, EditNodeSpecs.canonicalAngleDeg(-0.0)!!, 0.0)
        assertFalse(EditNodeSpecs.canonicalAngleDeg(-0.0)!!.toRawBits() == (-0.0).toRawBits())
    }

    @Test
    fun canonicalAngle_rejectsNonFinite() {
        assertNull(EditNodeSpecs.canonicalAngleDeg(Double.NaN))
        assertNull(EditNodeSpecs.canonicalAngleDeg(Double.POSITIVE_INFINITY))
        assertNull(EditNodeSpecs.canonicalAngleDeg(Double.NEGATIVE_INFINITY))
    }

    // ---- isQuarterTurnAngle / fineAngleDeg ----

    @Test
    fun quarterTurnDetection_coversEveryMultipleOf90() {
        for (a in listOf(0.0, 90.0, 180.0, 270.0, 360.0, -90.0, 450.0)) {
            assertTrue("angle=$a", EditNodeSpecs.isQuarterTurnAngle(a))
        }
        // 浮動小数の表現差のみ許容する。
        assertTrue(EditNodeSpecs.isQuarterTurnAngle(89.9999999999))
        assertFalse(EditNodeSpecs.isQuarterTurnAngle(89.9))
        assertFalse(EditNodeSpecs.isQuarterTurnAngle(12.5))
        assertFalse(EditNodeSpecs.isQuarterTurnAngle(Double.NaN))
    }

    @Test
    fun fineAngle_isResidueAgainstNearestQuarterBase() {
        val cases = mapOf(
            0.0 to 0.0,
            12.5 to 12.5,
            45.0 to 45.0,
            46.0 to -44.0,
            90.0 to 0.0,
            100.0 to 10.0,
            270.0 to 0.0,
            315.0 to 45.0,
            350.0 to -10.0,
            359.0 to -1.0,
        )
        for ((angle, expected) in cases) {
            assertEquals("angle=$angle", expected, EditNodeSpecs.fineAngleDeg(angle), 1e-9)
        }
    }

    // ---- normalizeGeometry ----

    @Test
    fun normalizeGeometry_acceptsFreeAngleAndCanonicalizes() {
        val free = EditNodeSpecs.normalizeGeometry(EditRecipe.GeometryParams(angleDeg = 3.5))!!
        assertEquals(3.5, free.angleDeg, 0.0)

        val negative = EditNodeSpecs.normalizeGeometry(EditRecipe.GeometryParams(angleDeg = -90.0))!!
        assertEquals(270.0, negative.angleDeg, 0.0)

        val overTwoTurns = EditNodeSpecs.normalizeGeometry(EditRecipe.GeometryParams(angleDeg = 732.5))!!
        assertEquals(12.5, overTwoTurns.angleDeg, 0.0)
    }

    @Test
    fun normalizeGeometry_rejectsNonFiniteAngleAndInvalidCrop() {
        assertNull(EditNodeSpecs.normalizeGeometry(EditRecipe.GeometryParams(angleDeg = Double.NaN)))
        assertNull(
            EditNodeSpecs.normalizeGeometry(
                EditRecipe.GeometryParams(cropNorm = listOf(0.5, 0.0, 0.5, 1.0)),
            ),
        )
        assertNull(
            EditNodeSpecs.normalizeGeometry(
                EditRecipe.GeometryParams(cropNorm = listOf(0.0, 0.0, 1.0)),
            ),
        )
        assertNull(
            EditNodeSpecs.normalizeGeometry(
                EditRecipe.GeometryParams(cropNorm = listOf(0.0, 0.0, 1.0, Double.NaN)),
            ),
        )
    }

    @Test
    fun normalizeGeometry_clampsCropAndKeepsSpan() {
        val g = EditNodeSpecs.normalizeGeometry(
            EditRecipe.GeometryParams(cropNorm = listOf(-0.2, -0.1, 1.3, 1.4)),
        )!!
        assertEquals(listOf(0.0, 0.0, 1.0, 1.0), g.cropNorm)
    }

    // ---- isNeutralGeometry ----

    @Test
    fun neutralGeometry_treatsFullTurnsAndSubEpsilonAnglesAsIdentity() {
        assertTrue(EditNodeSpecs.isNeutralGeometry(EditRecipe.GeometryParams(angleDeg = 360.0)))
        assertTrue(EditNodeSpecs.isNeutralGeometry(EditRecipe.GeometryParams(angleDeg = 1e-12)))
        assertFalse(EditNodeSpecs.isNeutralGeometry(EditRecipe.GeometryParams(angleDeg = 0.1)))
        assertFalse(
            EditNodeSpecs.isNeutralGeometry(
                EditRecipe.GeometryParams(cropNorm = listOf(0.0, 0.0, 0.999, 1.0)),
            ),
        )
        // 不正（非有限）は中立と主張しない（安全側）。
        assertFalse(EditNodeSpecs.isNeutralGeometry(EditRecipe.GeometryParams(angleDeg = Double.NaN)))
    }
}
