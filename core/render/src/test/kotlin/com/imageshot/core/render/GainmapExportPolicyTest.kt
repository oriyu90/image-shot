package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GainmapExportPolicy.classify] の純ロジック検証（JVM）。
 *
 * 契約（ADR-026 stage-1・PR-28a 同期更新）:
 * - 画素へ効く変換が全て乗算型（露出 EV・WB 温度/tint 利得）で、実行ジオメトリ変換が
 *   なく format=JPEG なら PASS_THROUGH。
 * - 非乗算 LUT（コントラスト等）・master カーブ非中立・R/G/B チャンネルカーブ非中立
 *   （PR-28a で画素適用が開始）・彩度/HSL・実行ジオメトリ・非JPEG 出力 → STRIP_TO_SDR。
 * - 現行で画素に適用されないノード（detail・自由角度・レンズ/透視・局所マスク）は
 *   判定に含めない（将来の適用化時に同期更新必要・KDoc 参照）。
 *
 * 【明示】gainmap 自体の保全/剝離挙動は androidTest（実機）の担当。本テストは分類器のみ。
 */
class GainmapExportPolicyTest {

    private fun recipe(
        develop: EditRecipe.DevelopParams = EditRecipe.DevelopParams(),
        geometry: EditRecipe.GeometryParams = EditRecipe.GeometryParams(),
        masks: List<EditRecipe.MaskNode> = emptyList(),
    ): EditRecipe = EditRecipe(
        documentId = "policy-test",
        source = EditRecipe.RecipeSource(assetId = "a", fingerprint = "f"),
        develop = develop,
        geometry = geometry,
        masks = masks,
    )

    @Test
    fun `neutral recipe on JPEG is pass-through`() {
        assertEquals(
            GainmapExportPolicy.Mode.PASS_THROUGH,
            GainmapExportPolicy.classify(recipe(), "JPEG"),
        )
    }

    @Test
    fun `exposure only is pass-through`() {
        val r = recipe(develop = EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 0.35)))
        assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(r, "JPEG"))
    }

    @Test
    fun `wb temperature and tint gains are multiplicative`() {
        val r = recipe(
            develop = EditRecipe.DevelopParams(
                whiteBalance = EditRecipe.WhiteBalance(mode = "custom", tempKelvin = 5600.0, tint = 0.2),
            ),
        )
        assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(r, "JPEG"))
    }

    @Test
    fun `contrast breaks pass-through`() {
        val r = recipe(develop = EditRecipe.DevelopParams(tone = EditRecipe.Tone(contrast = 0.4)))
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(r, "JPEG"))
    }

    @Test
    fun `highlights shadows whites blacks each break pass-through`() {
        val variants = listOf(
            EditRecipe.Tone(highlights = 0.4),
            EditRecipe.Tone(shadows = 0.4),
            EditRecipe.Tone(whites = 0.4),
            EditRecipe.Tone(blacks = 0.4),
        )
        for (tone in variants) {
            val r = recipe(develop = EditRecipe.DevelopParams(tone = tone))
            assertEquals("tone=$tone", GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(r, "JPEG"))
        }
    }

    @Test
    fun `vibrance saturation hsl break pass-through`() {
        val vibrance = recipe(develop = EditRecipe.DevelopParams(color = EditRecipe.ColorParams(vibrance = 0.2)))
        val saturation = recipe(develop = EditRecipe.DevelopParams(color = EditRecipe.ColorParams(saturation = 0.2)))
        val hsl = recipe(develop = EditRecipe.DevelopParams(color = EditRecipe.ColorParams(hsl = mapOf("red/hue" to 5.0))))
        for (r in listOf(vibrance, saturation, hsl)) {
            assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(r, "JPEG"))
        }
    }

    @Test
    fun `non-identity master curve breaks pass-through`() {
        val r = recipe(
            develop = EditRecipe.DevelopParams(curve = EditRecipe.Curve(rgb = listOf(listOf(0.0, 0.0), listOf(0.5, 0.6), listOf(1.0, 1.0)))),
        )
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(r, "JPEG"))
    }

    @Test
    fun `non-neutral channel curves break pass-through after PR-28a`() {
        // PR-28a で R/G/B チャンネルカーブは画素適用対象になった（master 直後・表示値ドメイン）。
        val r = recipe(
            develop = EditRecipe.DevelopParams(
                curve = EditRecipe.Curve(
                    rgb = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                    r = listOf(listOf(0.0, 0.0), listOf(0.5, 0.62), listOf(1.0, 1.0)),
                ),
            ),
        )
        val g = recipe(
            develop = EditRecipe.DevelopParams(
                curve = EditRecipe.Curve(g = listOf(listOf(0.0, 0.0), listOf(1.0, 0.9))),
            ),
        )
        val b = recipe(
            develop = EditRecipe.DevelopParams(
                curve = EditRecipe.Curve(b = listOf(listOf(0.0, 0.0), listOf(0.5, 0.8), listOf(1.0, 1.0))),
            ),
        )
        for (item in listOf("r" to r, "g" to g, "b" to b)) {
            assertEquals(
                "channel=${item.first}",
                GainmapExportPolicy.Mode.STRIP_TO_SDR,
                GainmapExportPolicy.classify(item.second, "JPEG"),
            )
        }
    }

    @Test
    fun `channel curves detail and masks are not applied and do not block`() {
        // チャンネルの「非空だが恒等（中立 2 点）」は画素不変なので pass-through を維持する。
        // （detail・マスクは画素不変ノードであり判定に含めない — KDoc 契約は不変。）
        val channels = recipe(
            develop = EditRecipe.DevelopParams(
                curve = EditRecipe.Curve(
                    r = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                    b = EditNodeSpecs.CURVE_NEUTRAL_RGB,
                ),
                detail = EditRecipe.Detail(sharpen = 0.5),
            ),
        )
        val masks = recipe(masks = listOf(EditRecipe.MaskNode(maskId = "m1", maskType = "linear")))
        for (r in listOf(channels, masks)) {
            assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(r, "JPEG"))
        }
    }

    @Test
    fun `lens and perspective markers do not block (not applied at pixels)`() {
        val r = recipe(
            geometry = EditRecipe.GeometryParams(lensProfileId = "some-profile", perspective = mapOf("k" to 1.0)),
        )
        assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(r, "JPEG"))
    }

    @Test
    fun `free angle geometry blocks pass-through`() {
        // PR-28b: 自由角度は適用される（リサンプル=非乗算変換）→ gainmap 同時再索引が無いため STRIP。
        val freeAngle = recipe(geometry = EditRecipe.GeometryParams(angleDeg = 37.5))
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(freeAngle, "JPEG"))
        // 全周回転（360°）は中立へ正準化されるため pass-through を維持する。
        val fullTurn = recipe(geometry = EditRecipe.GeometryParams(angleDeg = 360.0))
        assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(fullTurn, "JPEG"))
        // 非有限角は正規化不能（分類不能）→ 安全側で STRIP。
        val nonFinite = recipe(geometry = EditRecipe.GeometryParams(angleDeg = Double.NaN))
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(nonFinite, "JPEG"))
    }

    @Test
    fun `orientation transformed decode blocks pass-through`() {
        // decode 段の orientation 正立化（platform 自動適用 or 手動正規化）は基底画素の再索引。
        // gainmap 同時再索引は本実装に存在しないため、乗算型 only でも STRIP（ADR-026 stage-1）。
        assertEquals(
            GainmapExportPolicy.Mode.STRIP_TO_SDR,
            GainmapExportPolicy.classify(recipe(), "JPEG", orientationTransformed = true),
        )
        assertEquals(
            GainmapExportPolicy.Mode.STRIP_TO_SDR,
            GainmapExportPolicy.classify(
                recipe(develop = EditRecipe.DevelopParams(tone = EditRecipe.Tone(exposureEv = 0.35))),
                "JPEG",
                orientationTransformed = true,
            ),
        )
    }

    @Test
    fun `applied geometry transforms break pass-through`() {
        val rotate = recipe(geometry = EditRecipe.GeometryParams(angleDeg = 90.0))
        val mirrorX = recipe(geometry = EditRecipe.GeometryParams(mirrorX = true))
        val mirrorY = recipe(geometry = EditRecipe.GeometryParams(mirrorY = true))
        val crop = recipe(
            geometry = EditRecipe.GeometryParams(cropNorm = listOf(0.1, 0.1, 0.9, 0.9)),
        )
        for (g in listOf(rotate, mirrorX, mirrorY, crop)) {
            assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(g, "JPEG"))
        }
    }

    @Test
    fun `png output always strips`() {
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(recipe(), "PNG"))
        assertEquals(GainmapExportPolicy.Mode.STRIP_TO_SDR, GainmapExportPolicy.classify(recipe(), "png"))
    }

    @Test
    fun `jpeg format case-insensitive`() {
        assertEquals(GainmapExportPolicy.Mode.PASS_THROUGH, GainmapExportPolicy.classify(recipe(), "jpeg"))
    }
}
