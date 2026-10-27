package com.imageshot.core.model

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * [MaskSpecs] の正規化・検証（PR-29a・JVM）。
 *
 * 契約:
 * - 既定値補完（feather / angleDeg）とキー順序の正準化。
 * - 未知キー・非有限・範囲外・形状不一致は **null**（既定値へ落とさない）。
 * - 件数上限 [MaskSpecs.MAX_MASKS]・maskId 重複は列正規化で拒否。
 * - 既存 recipe JSON（masks 無し / brushStrokes 無し）はそのまま読める（serialization 後方互換）。
 */
class MaskSpecsTest {

    private fun linear(
        parameters: Map<String, Double> = mapOf("x0" to 0.2, "y0" to 0.2, "x1" to 0.8, "y1" to 0.8),
        adjustments: Map<String, Double> = emptyMap(),
        id: String = "m1",
    ) = EditRecipe.MaskNode(
        maskId = id,
        maskType = "linear",
        parameters = parameters,
        adjustments = adjustments,
    )

    // ---------------------------------------------------------------- 既定値・正準順序

    @Test
    fun defaultParams_fillFeatherPerType() {
        assertEquals(
            mapOf("feather" to 1.0),
            MaskSpecs.defaultParams("linear"),
        )
        assertEquals(
            mapOf("angleDeg" to 0.0, "feather" to 0.5),
            MaskSpecs.defaultParams("radial"),
        )
        assertEquals(mapOf("feather" to 0.3), MaskSpecs.defaultParams("brush"))
        assertNull(MaskSpecs.defaultParams("vignette"))
        assertEquals(listOf("linear", "radial", "brush"), MaskSpecs.MASK_TYPES)
        assertEquals(8, MaskSpecs.MAX_MASKS)
    }

    @Test
    fun normalize_linear_completesDefaultsAndCanonicalKeyOrder() {
        val normalized = assertNotNull(
            MaskSpecs.normalize(
                linear(parameters = linkedMapOf("y1" to 0.8, "x1" to 0.8, "y0" to 0.2, "x0" to 0.2)),
            ),
        )
        // 入力順に関わらず正準順序（x0,y0,x1,y1,feather）で返る。
        assertEquals(listOf("x0", "y0", "x1", "y1", "feather"), normalized.parameters.keys.toList())
        assertEquals(1.0, normalized.parameters.getValue("feather"), 0.0)
        assertEquals("linear", normalized.maskType)
        assertEquals(1, normalized.version)
    }

    @Test
    fun normalize_rejectsUnknownKeysNonFiniteAndOutOfRange() {
        // 未知パラメータキー
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to 0.0, "y0" to 0.0, "x1" to 1.0, "y1" to 1.0, "blur" to 0.5))))
        // 非有限
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to Double.NaN, "y0" to 0.0, "x1" to 1.0, "y1" to 1.0))))
        // 範囲外
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to -0.1, "y0" to 0.0, "x1" to 1.0, "y1" to 1.0))))
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to 0.0, "y0" to 0.0, "x1" to 1.0, "y1" to 1.2))))
        // feather 範囲外
        assertNull(
            MaskSpecs.normalize(
                linear(
                    parameters = mapOf("x0" to 0.0, "y0" to 0.0, "x1" to 1.0, "y1" to 1.0, "feather" to 1.5),
                ),
            ),
        )
    }

    @Test
    fun normalize_linear_rejectsDegenerateAxis() {
        // A==B は重みが定義できない（spec: normalize で拒否）。
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to 0.5, "y0" to 0.5, "x1" to 0.5, "y1" to 0.5))))
        // 必須キー欠落も拒否（既定値で補わない）。
        assertNull(MaskSpecs.normalize(linear(parameters = mapOf("x0" to 0.2, "y0" to 0.2, "x1" to 0.8))))
    }

    @Test
    fun normalize_radial_canonicalizesAngleAndValidatesRadii() {
        val node = EditRecipe.MaskNode(
            maskId = "r1",
            maskType = "radial",
            parameters = mapOf("cx" to 0.5, "cy" to 0.5, "rx" to 0.3, "ry" to 0.2, "angleDeg" to -30.0),
        )
        val normalized = assertNotNull(MaskSpecs.normalize(node))
        assertEquals(330.0, normalized.parameters.getValue("angleDeg"), 1e-12)
        assertEquals(0.5, normalized.parameters.getValue("feather"), 0.0)
        assertEquals(listOf("cx", "cy", "rx", "ry", "angleDeg", "feather"), normalized.parameters.keys.toList())

        // 360° は 0° へ（[EditNodeSpecs.canonicalAngleDeg] と同一規則）。
        val fullTurn = assertNotNull(
            MaskSpecs.normalize(node.copy(parameters = node.parameters + ("angleDeg" to 360.0))),
        )
        assertEquals(0.0, fullTurn.parameters.getValue("angleDeg"), 0.0)

        // rx/ry は (1e-4, 1]。
        assertNull(MaskSpecs.normalize(node.copy(parameters = mapOf("cx" to 0.5, "cy" to 0.5, "rx" to 1.0e-4, "ry" to 0.2))))
        assertNull(MaskSpecs.normalize(node.copy(parameters = mapOf("cx" to 0.5, "cy" to 0.5, "rx" to 0.3, "ry" to 1.5))))
        // 非有限角は拒否（既定値へ落とさない）。
        assertNull(
            MaskSpecs.normalize(
                node.copy(
                    parameters = mapOf("cx" to 0.5, "cy" to 0.5, "rx" to 0.3, "ry" to 0.2, "angleDeg" to Double.NaN),
                ),
            ),
        )
    }

    @Test
    fun normalize_brush_validatesStrokesAndRejectsStrokesOnOtherTypes() {
        val brush = EditRecipe.MaskNode(
            maskId = "b1",
            maskType = "brush",
            brushStrokes = listOf(
                EditRecipe.BrushStroke(x = 0.5, y = 0.5, radius = 0.2),
                EditRecipe.BrushStroke(x = 0.1, y = 0.9, radius = 0.05, opacity = 0.4),
            ),
        )
        val normalized = assertNotNull(MaskSpecs.normalize(brush))
        assertEquals(2, normalized.brushStrokes.size)
        assertEquals(1.0, normalized.brushStrokes[0].opacity, 0.0)
        assertEquals(mapOf("feather" to 0.3), normalized.parameters)

        // 空ストロークは有効（重み 0）。
        assertTrue(assertNotNull(MaskSpecs.normalize(brush.copy(brushStrokes = emptyList()))).brushStrokes.isEmpty())

        // 半径 0 / 0.5 超 / opacity 範囲外 / 座標範囲外は拒否。
        assertNull(MaskSpecs.normalize(brush.copy(brushStrokes = listOf(EditRecipe.BrushStroke(0.5, 0.5, 0.0)))))
        assertNull(MaskSpecs.normalize(brush.copy(brushStrokes = listOf(EditRecipe.BrushStroke(0.5, 0.5, 0.6)))))
        assertNull(MaskSpecs.normalize(brush.copy(brushStrokes = listOf(EditRecipe.BrushStroke(0.5, 0.5, 0.2, opacity = 1.5)))))
        assertNull(MaskSpecs.normalize(brush.copy(brushStrokes = listOf(EditRecipe.BrushStroke(1.2, 0.5, 0.2)))))
        // 上限超過（防御的上限）。
        val tooMany = List(MaskSpecs.MAX_BRUSH_STROKES + 1) { EditRecipe.BrushStroke(0.5, 0.5, 0.2) }
        assertNull(MaskSpecs.normalize(brush.copy(brushStrokes = tooMany)))

        // brush 以外に brushStrokes が入っている payload は形状不一致（黙って捨てない）。
        assertNull(MaskSpecs.normalize(linear().copy(brushStrokes = listOf(EditRecipe.BrushStroke(0.5, 0.5, 0.2)))))
    }

    @Test
    fun normalize_rejectsBlankIdUnknownTypeAndNonPositiveVersion() {
        assertNull(MaskSpecs.normalize(linear().copy(maskId = " ")))
        assertNull(MaskSpecs.normalize(linear().copy(maskType = "vignette")))
        assertNull(MaskSpecs.normalize(linear().copy(version = 0)))
        // 未知の将来版は v1 の意味で適用しない（誤表示より PRESERVED_ONLY を選ぶ）。
        assertNull(MaskSpecs.normalize(linear().copy(version = 2)))
    }

    // ---------------------------------------------------------------- adjustments

    @Test
    fun normalize_adjustments_allowsOnlyRecipeDomainPathsInCanonicalOrder() {
        val node = linear(
            adjustments = linkedMapOf(
                "develop.color.saturation" to -0.5,
                "develop.tone.exposureEv" to 0.7,
                "develop.whiteBalance.tempKelvin" to 6500.0,
            ),
        )
        val normalized = assertNotNull(MaskSpecs.normalize(node))
        assertEquals(
            listOf(
                "develop.tone.exposureEv",
                "develop.whiteBalance.tempKelvin",
                "develop.color.saturation",
            ),
            normalized.adjustments.keys.toList(),
        )
        assertEquals(0.7, normalized.adjustments.getValue("develop.tone.exposureEv"), 0.0)
        assertEquals(6500.0, normalized.adjustments.getValue("develop.whiteBalance.tempKelvin"), 0.0)

        // 範囲は AdjustmentId と同一（レシピドメイン値）。
        assertEquals(-5.0..5.0, MaskSpecs.adjustmentRange("develop.tone.exposureEv"))
        assertEquals(-1.0..1.0, MaskSpecs.adjustmentRange("develop.tone.contrast"))
        assertEquals(2000.0..12000.0, MaskSpecs.adjustmentRange("develop.whiteBalance.tempKelvin"))
        assertNull(MaskSpecs.adjustmentRange("develop.detail.sharpen"))
        assertNull(MaskSpecs.adjustmentRange("develop.color.hsl.red.hue"))
    }

    @Test
    fun normalize_adjustments_rejectsUnknownNonFiniteAndOutOfRange() {
        // 未知パス（detail/curve/HSL は対象外）
        assertNull(MaskSpecs.normalize(linear(adjustments = mapOf("develop.detail.sharpen" to 0.5))))
        // 非有限
        assertNull(MaskSpecs.normalize(linear(adjustments = mapOf("develop.tone.exposureEv" to Double.POSITIVE_INFINITY))))
        // 範囲外（レシピドメインの -1..1 を超える）
        assertNull(MaskSpecs.normalize(linear(adjustments = mapOf("develop.tone.contrast" to 1.5))))
        assertNull(MaskSpecs.normalize(linear(adjustments = mapOf("develop.tone.exposureEv" to 6.0))))
        assertNull(MaskSpecs.normalize(linear(adjustments = mapOf("develop.whiteBalance.tempKelvin" to 1500.0))))
    }

    // ---------------------------------------------------------------- 列の正規化

    @Test
    fun normalizeAll_enforcesLimitAndDuplicateIdsAndAllowsEmpty() {
        assertTrue(MaskSpecs.normalizeAll(emptyList())!!.isEmpty())
        val eight = List(MaskSpecs.MAX_MASKS) { linear(id = "m$it") }
        assertEquals(MaskSpecs.MAX_MASKS, assertNotNull(MaskSpecs.normalizeAll(eight)).size)
        // 上限超過
        assertNull(MaskSpecs.normalizeAll(eight + linear(id = "m8")))
        // maskId 重複
        assertNull(MaskSpecs.normalizeAll(listOf(linear(id = "same"), linear(id = "same"))))
        // 1件でも不正なら全体を拒否（部分適用を作らない）
        assertNull(MaskSpecs.normalizeAll(listOf(linear(id = "ok"), linear(id = "bad").copy(maskType = "vignette"))))
    }

    @Test
    fun newMaskId_isNonBlankAndUnique() {
        val ids = List(32) { MaskSpecs.newMaskId() }
        assertTrue(ids.all { it.isNotBlank() })
        assertEquals(32, ids.toSet().size)
    }

    // ---------------------------------------------------------------- serialization 後方互換

    @Test
    fun recipeJson_backwardCompatible_additiveMaskFields() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // PR-29a 以前の recipe JSON（masks 空・brushStrokes 無し）。
        val legacy = """
            {
              "documentId": "doc",
              "source": {"assetId": "a", "fingerprint": "f"},
              "develop": {},
              "geometry": {},
              "masks": [],
              "displayIntent": "auto-sdr-hdr",
              "unknownExtensions": {},
              "rendererVersion": "1"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(EditRecipe.serializer(), legacy)
        assertTrue(decoded.masks.isEmpty())
        assertEquals(1, decoded.schema)

        // masks に brushStrokes が無い中間形式も読める（default = 空）。
        val withMask = """
            {
              "documentId": "doc",
              "source": {"assetId": "a", "fingerprint": "f"},
              "masks": [{"maskId":"m1","maskType":"brush","version":1,
                "parameters":{"feather":0.3},"adjustments":{"develop.tone.exposureEv":0.5}}]
            }
        """.trimIndent()
        val decodedMask = json.decodeFromString(EditRecipe.serializer(), withMask)
        assertEquals(1, decodedMask.masks.size)
        assertTrue(decodedMask.masks.single().brushStrokes.isEmpty())
        assertNotNull(MaskSpecs.normalize(decodedMask.masks.single()))

        // 現行の往復: brushStrokes を含めて再保存・再読込で値が保たれる。
        val roundTrip = json.decodeFromString(
            EditRecipe.serializer(),
            json.encodeToString(
                EditRecipe.serializer(),
                decodedMask.copy(
                    masks = listOf(
                        decodedMask.masks.single().copy(
                            brushStrokes = listOf(EditRecipe.BrushStroke(0.25, 0.75, 0.1, opacity = 0.5)),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(EditRecipe.BrushStroke(0.25, 0.75, 0.1, opacity = 0.5)),
            roundTrip.masks.single().brushStrokes,
        )
    }
}
