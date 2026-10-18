package com.imageshot.feature.export

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.ErrorCode
import com.imageshot.core.model.ImageOpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExportSettingsHash（settingsHash カラム用・純Kotlin）と
 * ExportFormatValidator（書き出し設定の妥当性検査・純Kotlin）の性質テスト。
 */
class ExportSettingsValidatorTest {

    private fun recipe(exposure: Double = 0.0): EditRecipe = EditRecipe(
        documentId = "doc",
        source = EditRecipe.RecipeSource(assetId = "asset", fingerprint = "fp"),
        develop = EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = exposure)),
    )

    private fun hash(
        documentId: String = "doc",
        revision: Long = 1L,
        recipe: EditRecipe = recipe(),
        format: String = "JPEG",
        quality: Int = 90,
        maxEdgePx: Int? = null,
        destinationKind: String = ExportDestination.KIND_SAF,
    ): String = ExportSettingsHash.compute(
        documentId = documentId,
        recipeRevision = revision,
        recipe = recipe,
        format = format,
        quality = quality,
        maxEdgePx = maxEdgePx,
        destinationKind = destinationKind,
    )

    // ---- ExportSettingsHash: 安定性と識別性 ----

    @Test
    fun hash_stable_forIdenticalInputs() {
        assertEquals(hash(), hash())
    }

    @Test
    fun hash_differs_whenAnySettingChanges() {
        val base = hash()
        assertNotEquals(base, hash(revision = 2L))
        assertNotEquals(base, hash(format = "PNG"))
        assertNotEquals(base, hash(quality = 80))
        assertNotEquals(base, hash(maxEdgePx = 2048))
        assertNotEquals(base, hash(destinationKind = ExportDestination.KIND_MEDIA_STORE))
        assertNotEquals(base, hash(recipe = recipe(exposure = 0.5)))
        assertNotEquals(base, hash(documentId = "doc2"))
    }

    @Test
    fun hash_isHexSha256() {
        val h = hash()
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ---- ExportFormatValidator: 設定値の範囲検査 ----

    @Test
    fun validate_acceptsJpegPngWithinBounds() {
        for (format in listOf("JPEG", "PNG")) {
            val result = ExportFormatValidator.validate(format, quality = 90, maxEdgePx = null)
            assertTrue(result is ImageOpResult.Success)
        }
    }

    @Test
    fun validate_rejectsQualityOutOf1to100() {
        for (bad in listOf(0, -1, 101, Int.MAX_VALUE)) {
            val result = ExportFormatValidator.validate("JPEG", quality = bad, maxEdgePx = null)
            assertTrue(
                "quality $bad must fail",
                result is ImageOpResult.Failure && result.code == ErrorCode.UNSUPPORTED_CODEC,
            )
        }
    }

    @Test
    fun validate_rejectsUnknownFormat() {
        val result = ExportFormatValidator.validate("TIFF", quality = 90, maxEdgePx = null)
        assertTrue(result is ImageOpResult.Failure && result.code == ErrorCode.UNSUPPORTED_CODEC)
    }

    @Test
    fun validate_rejectsMaxEdgeOutOfBounds() {
        val low = ExportFormatValidator.validate("JPEG", quality = 90, maxEdgePx = ExportFormatValidator.MIN_MAX_EDGE_PX - 1)
        val high = ExportFormatValidator.validate("JPEG", quality = 90, maxEdgePx = ExportFormatValidator.MAX_MAX_EDGE_PX + 1)
        assertTrue(low is ImageOpResult.Failure && low.code == ErrorCode.UNSUPPORTED_CODEC)
        assertTrue(high is ImageOpResult.Failure && high.code == ErrorCode.UNSUPPORTED_CODEC)
    }
}
