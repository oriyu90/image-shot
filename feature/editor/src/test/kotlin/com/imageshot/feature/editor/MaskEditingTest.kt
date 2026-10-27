package com.imageshot.feature.editor

import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-29c のマスク編集ロジック（[MaskEditing.kt] の純関数）の JVM テスト。
 *
 * ここで固定するのは UI の写像・既定値・間引きだけであり、永続化/描画は
 * core:database / core:render 側のテスト（MaskSpecsTest / MaskWeightTest /
 * RecipeMaskApplierTest / EditStoreTest）が担う。
 */
class MaskEditingTest {

    // ---------------------------------------------------------------- fitRect

    @Test
    fun fitRect_wideContainer_letterboxesVertically() {
        // 容器 1000x500（2:1）・画像 1:1 → 高さ 500・幅 500・左右 250 のレターボックス。
        val rect = MaskOverlayGeometry.fitRect(1000, 500, 100, 100)!!
        assertEquals(250f, rect.left, 1e-4f)
        assertEquals(0f, rect.top, 1e-4f)
        assertEquals(750f, rect.right, 1e-4f)
        assertEquals(500f, rect.width, 1e-4f)
        assertEquals(500f, rect.height, 1e-4f)
    }

    @Test
    fun fitRect_tallContainer_letterboxesHorizontally() {
        // 容器 500x1000（0.5:1）・画像 16:9（1.7778:1）→ 幅 500・高さ 281.25・上下 359.375。
        val rect = MaskOverlayGeometry.fitRect(500, 1000, 1600, 900)!!
        assertEquals(0f, rect.left, 1e-4f)
        assertEquals(359.375f, rect.top, 1e-3f)
        assertEquals(500f, rect.width, 1e-4f)
        assertEquals(281.25f, rect.height, 1e-3f)
    }

    @Test
    fun fitRect_matchingAspect_fillsContainer() {
        val rect = MaskOverlayGeometry.fitRect(640, 480, 1920, 1440)!!
        assertEquals(0f, rect.left, 1e-4f)
        assertEquals(0f, rect.top, 1e-4f)
        assertEquals(640f, rect.width, 1e-4f)
        assertEquals(480f, rect.height, 1e-4f)
    }

    @Test
    fun fitRect_invalidSizes_returnsNull() {
        assertNull(MaskOverlayGeometry.fitRect(0, 500, 100, 100))
        assertNull(MaskOverlayGeometry.fitRect(500, 0, 100, 100))
        assertNull(MaskOverlayGeometry.fitRect(500, 500, 0, 100))
        assertNull(MaskOverlayGeometry.fitRect(500, 500, 100, -1))
    }

    // ---------------------------------------------------------------- 座標写像

    @Test
    fun toScreen_mapsNormalizedToRect() {
        val rect = FitRect(left = 100f, top = 50f, width = 200f, height = 100f)
        assertEquals(ScreenPoint(100f, 50f), MaskOverlayGeometry.toScreen(rect, 0.0, 0.0))
        assertEquals(ScreenPoint(300f, 150f), MaskOverlayGeometry.toScreen(rect, 1.0, 1.0))
        assertEquals(ScreenPoint(200f, 100f), MaskOverlayGeometry.toScreen(rect, 0.5, 0.5))
    }

    @Test
    fun toNormalized_roundTripsAndClampsToImage() {
        val rect = FitRect(left = 100f, top = 50f, width = 200f, height = 100f)
        val center = MaskOverlayGeometry.toNormalized(rect, 200f, 100f)!!
        assertEquals(0.5, center.first, 1e-9)
        assertEquals(0.5, center.second, 1e-9)
        // 画像外（レターボックス）は端へクランプする。
        val outside = MaskOverlayGeometry.toNormalized(rect, -500f, 999f)!!
        assertEquals(0.0, outside.first, 1e-9)
        assertEquals(1.0, outside.second, 1e-9)
    }

    @Test
    fun toNormalized_degenerateRect_returnsNull() {
        assertNull(MaskOverlayGeometry.toNormalized(FitRect(0f, 0f, 0f, 0f), 10f, 10f))
    }

    @Test
    fun distancePx_reflectsAspect() {
        val rect = FitRect(0f, 0f, 400f, 100f)
        // 正規化 0.25 の横移動 = 100px / 縦 0.25 = 25px。
        assertEquals(100.0, MaskOverlayGeometry.distancePx(rect, 0.0, 0.0, 0.25, 0.0), 1e-6)
        assertEquals(25.0, MaskOverlayGeometry.distancePx(rect, 0.0, 0.0, 0.0, 0.25), 1e-6)
    }

    // ---------------------------------------------------------------- ハンドル

    private val rect = FitRect(left = 0f, top = 0f, width = 200f, height = 200f)

    @Test
    fun handlesOf_linear_hasAandB() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        val handles = MaskHandles.handlesOf(node)
        assertEquals(2, handles.size)
        assertEquals(MaskHandles.Kind.LINEAR_A, handles[0].kind)
        assertEquals(0.25, handles[0].x, 1e-9)
        assertEquals(0.75, handles[0].y, 1e-9)
        assertEquals(MaskHandles.Kind.LINEAR_B, handles[1].kind)
        assertEquals(0.75, handles[1].x, 1e-9)
        assertEquals(0.25, handles[1].y, 1e-9)
    }

    @Test
    fun handlesOf_radial_hasCenterRxRy() {
        val node = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val handles = MaskHandles.handlesOf(node)
        assertEquals(3, handles.size)
        assertEquals(MaskHandles.Kind.RADIAL_CENTER, handles[0].kind)
        assertEquals(0.8, handles[1].x, 1e-9) // cx + rx = 0.5 + 0.3
        assertEquals(0.5, handles[1].y, 1e-9)
        assertEquals(0.5, handles[2].x, 1e-9)
        assertEquals(0.8, handles[2].y, 1e-9) // cy + ry
    }

    @Test
    fun handlesOf_brush_isEmpty() {
        val node = MaskDraft.create(MaskSpecs.TYPE_BRUSH, "mask-brush")!!
        assertTrue(MaskHandles.handlesOf(node).isEmpty())
    }

    @Test
    fun nearestHandle_hitsWithinTolerance() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        val a = MaskOverlayGeometry.toScreen(rect, 0.25, 0.75)
        assertEquals(
            MaskHandles.Kind.LINEAR_A,
            MaskOverlayGeometry.nearestHandle(node, rect, a.x + 8f, a.y - 8f, tolerancePx = 24f),
        )
        // 中点（A/B から 70.7px）は 24px 許容の外。
        assertNull(MaskOverlayGeometry.nearestHandle(node, rect, 100f, 100f, tolerancePx = 24f))
    }

    @Test
    fun move_linearA_updatesParameters() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        val moved = MaskHandles.move(node, MaskHandles.Kind.LINEAR_A, 0.1, 0.2)
        assertEquals(0.1, moved.parameters.getValue(MaskSpecs.KEY_X0), 1e-9)
        assertEquals(0.2, moved.parameters.getValue(MaskSpecs.KEY_Y0), 1e-9)
        // 他のキーは保持（B 側）。
        assertEquals(0.75, moved.parameters.getValue(MaskSpecs.KEY_X1), 1e-9)
        assertEquals(0.25, moved.parameters.getValue(MaskSpecs.KEY_Y1), 1e-9)
    }

    @Test
    fun move_linearDegenerate_keepsPreviousState() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        // B を A と同一座標へ → 退化（長さ 0）のため正規化できず、元のノードを返す。
        val moved = MaskHandles.move(node, MaskHandles.Kind.LINEAR_B, 0.25, 0.75)
        assertEquals(node, moved)
    }

    @Test
    fun move_clampsNormalizedCoordinates() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        val moved = MaskHandles.move(node, MaskHandles.Kind.LINEAR_B, 5.0, -3.0)
        assertEquals(1.0, moved.parameters.getValue(MaskSpecs.KEY_X1), 1e-9)
        assertEquals(0.0, moved.parameters.getValue(MaskSpecs.KEY_Y1), 1e-9)
    }

    @Test
    fun move_radialCenter_keepsRadii() {
        val node = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val moved = MaskHandles.move(node, MaskHandles.Kind.RADIAL_CENTER, 0.2, 0.3)
        assertEquals(0.2, moved.parameters.getValue(MaskSpecs.KEY_CX), 1e-9)
        assertEquals(0.3, moved.parameters.getValue(MaskSpecs.KEY_CY), 1e-9)
        assertEquals(0.3, moved.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
        assertEquals(0.3, moved.parameters.getValue(MaskSpecs.KEY_RY), 1e-9)
    }

    @Test
    fun move_radialRx_clampsToUiBounds() {
        val node = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val enlarged = MaskHandles.move(node, MaskHandles.Kind.RADIAL_RX, 1.0, 0.5)
        assertEquals(0.5, enlarged.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
        // 中心より左へドラッグしても負にせず下限へ留める。
        val shrunk = MaskHandles.move(node, MaskHandles.Kind.RADIAL_RX, 0.4, 0.5)
        assertEquals(MaskHandles.MIN_RADIAL_HANDLE_RADIUS, shrunk.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
    }

    @Test
    fun handlesOf_radial_followsNormalizedRotation() {
        val base = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val node = base.copy(
            parameters = base.parameters + (MaskSpecs.KEY_ANGLE_DEG to 90.0),
        )
        val handles = MaskHandles.handlesOf(node)
        // angle=90: rx 軸 = (0,1), ry 軸 = (-1,0)。半径 0.3。
        assertEquals(0.5, handles[1].x, 1e-9)
        assertEquals(0.8, handles[1].y, 1e-9)
        assertEquals(0.2, handles[2].x, 1e-9)
        assertEquals(0.5, handles[2].y, 1e-9)
    }

    @Test
    fun move_radialHandles_projectOntoRotatedAxes() {
        val base = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val node = base.copy(
            parameters = base.parameters + (MaskSpecs.KEY_ANGLE_DEG to 90.0),
        )
        val rxMoved = MaskHandles.move(node, MaskHandles.Kind.RADIAL_RX, 0.5, 0.9)
        assertEquals(0.4, rxMoved.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
        assertEquals(0.3, rxMoved.parameters.getValue(MaskSpecs.KEY_RY), 1e-9)

        val ryMoved = MaskHandles.move(node, MaskHandles.Kind.RADIAL_RY, 0.1, 0.5)
        assertEquals(0.3, ryMoved.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
        assertEquals(0.4, ryMoved.parameters.getValue(MaskSpecs.KEY_RY), 1e-9)
    }

    @Test
    fun move_radialHandle_roundTripsAtArbitraryAngle() {
        val base = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        val node = base.copy(
            parameters = base.parameters + (MaskSpecs.KEY_ANGLE_DEG to 30.0),
        )
        val rxHandle = MaskHandles.handlesOf(node).first { it.kind == MaskHandles.Kind.RADIAL_RX }
        val moved = MaskHandles.move(node, MaskHandles.Kind.RADIAL_RX, rxHandle.x, rxHandle.y)
        // ハンドル位置へ戻しても rx は変わらない（写像と逆写像の一致）。
        assertEquals(0.3, moved.parameters.getValue(MaskSpecs.KEY_RX), 1e-6)
    }

    @Test
    fun move_kindMismatch_isNoOp() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        assertEquals(node, MaskHandles.move(node, MaskHandles.Kind.RADIAL_CENTER, 0.1, 0.1))
        assertEquals(node, MaskHandles.move(node, MaskHandles.Kind.RADIAL_RX, 0.9, 0.9))
    }

    // ---------------------------------------------------------------- 既定値

    @Test
    fun create_linear_matchesMaskSpecsDefaults() {
        val node = MaskDraft.create(MaskSpecs.TYPE_LINEAR, "mask-linear")!!
        assertEquals(MaskSpecs.TYPE_LINEAR, node.maskType)
        assertEquals(1, node.version)
        assertEquals(MaskSpecs.LINEAR_PARAM_KEYS, node.parameters.keys.toList())
        assertEquals(MaskSpecs.DEFAULT_LINEAR_FEATHER, node.parameters.getValue(MaskSpecs.KEY_FEATHER), 1e-9)
        assertTrue(node.brushStrokes.isEmpty())
        assertTrue(node.adjustments.isEmpty())
        // MaskSpecs.normalize を通っている（= そのまま永続化できる）。
        assertEquals(node, MaskSpecs.normalize(node))
    }

    @Test
    fun create_radial_centersWithGivenRadius() {
        val node = MaskDraft.create(MaskSpecs.TYPE_RADIAL, "mask-radial")!!
        assertEquals(MaskSpecs.RADIAL_PARAM_KEYS, node.parameters.keys.toList())
        assertEquals(0.5, node.parameters.getValue(MaskSpecs.KEY_CX), 1e-9)
        assertEquals(0.5, node.parameters.getValue(MaskSpecs.KEY_CY), 1e-9)
        assertEquals(MaskDraft.DEFAULT_RADIAL_RADIUS, node.parameters.getValue(MaskSpecs.KEY_RX), 1e-9)
        assertEquals(MaskDraft.DEFAULT_RADIAL_RADIUS, node.parameters.getValue(MaskSpecs.KEY_RY), 1e-9)
        assertEquals(MaskSpecs.DEFAULT_ANGLE_DEG, node.parameters.getValue(MaskSpecs.KEY_ANGLE_DEG), 1e-9)
        assertEquals(MaskSpecs.DEFAULT_RADIAL_FEATHER, node.parameters.getValue(MaskSpecs.KEY_FEATHER), 1e-9)
    }

    @Test
    fun create_brush_hasNoStrokes() {
        val node = MaskDraft.create(MaskSpecs.TYPE_BRUSH, "mask-brush")!!
        assertEquals(MaskSpecs.BRUSH_PARAM_KEYS, node.parameters.keys.toList())
        assertEquals(MaskSpecs.DEFAULT_BRUSH_FEATHER, node.parameters.getValue(MaskSpecs.KEY_FEATHER), 1e-9)
        assertTrue(node.brushStrokes.isEmpty())
    }

    @Test
    fun create_unknownType_returnsNull() {
        assertNull(MaskDraft.create("vignette", "mask-unknown"))
    }

    @Test
    fun clampBrushRadius_boundsAndFallback() {
        assertEquals(MaskDraft.MIN_BRUSH_RADIUS, MaskDraft.clampBrushRadius(0.0001), 1e-12)
        assertEquals(MaskSpecs.MAX_BRUSH_RADIUS, MaskDraft.clampBrushRadius(0.9), 1e-12)
        assertEquals(MaskDraft.DEFAULT_BRUSH_RADIUS, MaskDraft.clampBrushRadius(Double.NaN), 1e-12)
    }

    // ---------------------------------------------------------------- 角度・feather

    @Test
    fun angle_roundTripsBetweenDisplayAndStorage() {
        assertEquals(270.0, MaskSlider.angleSaved(-90.0), 1e-9)
        assertEquals(-90.0, MaskSlider.angleDisplay(270.0), 1e-9)
        assertEquals(180.0, MaskSlider.angleSaved(-180.0), 1e-9)
        assertEquals(180.0, MaskSlider.angleDisplay(180.0), 1e-9)
        assertEquals(0.0, MaskSlider.angleSaved(0.0), 1e-9)
        assertEquals(90.0, MaskSlider.angleSaved(90.0), 1e-9)
        // 保存値は常に [0,360)。
        val saved = MaskSlider.angleSaved(-359.0)
        assertTrue(saved >= 0.0 && saved < 360.0)
        assertEquals(1.0, saved, 1e-9)
    }

    @Test
    fun feather_clampsToUnitRange() {
        assertEquals(0.0, MaskSlider.feather(-1.0, 0.5), 1e-9)
        assertEquals(1.0, MaskSlider.feather(2.0, 0.5), 1e-9)
        assertEquals(0.5, MaskSlider.feather(Double.NaN, 0.5), 1e-9)
    }

    // ---------------------------------------------------------------- adjustments 表示スケール

    @Test
    fun adjustments_exposure_isEvDirect() {
        val spec = MaskAdjustments.spec(MaskAdjustments.PATH_EXPOSURE)!!
        assertEquals(2.5, MaskAdjustments.toRecipe(spec, 2.5)!!, 1e-9)
        assertEquals(5.0, MaskAdjustments.toRecipe(spec, 9.0)!!, 1e-9) // クランプ
        assertEquals(-1.25, MaskAdjustments.toDisplay(spec, -1.25)!!, 1e-9)
    }

    @Test
    fun adjustments_contrastAndSaturationAndTint_arePercent() {
        val contrast = MaskAdjustments.spec(MaskAdjustments.PATH_CONTRAST)!!
        assertEquals(-0.4, MaskAdjustments.toRecipe(contrast, -40.0)!!, 1e-9)
        assertEquals(-40.0, MaskAdjustments.toDisplay(contrast, -0.4)!!, 1e-9)
        val saturation = MaskAdjustments.spec(MaskAdjustments.PATH_SATURATION)!!
        assertEquals(1.0, MaskAdjustments.toRecipe(saturation, 100.0)!!, 1e-9)
        val tint = MaskAdjustments.spec(MaskAdjustments.PATH_TINT)!!
        assertEquals(-1.0, MaskAdjustments.toRecipe(tint, -100.0)!!, 1e-9)
    }

    @Test
    fun adjustments_temperature_isAbsoluteKelvin() {
        val spec = MaskAdjustments.spec(MaskAdjustments.PATH_TEMPERATURE)!!
        assertEquals(4500.0, MaskAdjustments.toRecipe(spec, 4500.0)!!, 1e-9)
        assertEquals(2000.0, MaskAdjustments.toRecipe(spec, 1000.0)!!, 1e-9)
        assertEquals(12000.0, MaskAdjustments.toRecipe(spec, 20000.0)!!, 1e-9)
        assertEquals(9000.0, MaskAdjustments.toDisplay(spec, 9000.0)!!, 1e-9)
    }

    @Test
    fun adjustments_recipeValuesStayWithinMaskSpecsRanges() {
        MaskAdjustments.SPECS.forEach { spec ->
            val range = MaskSpecs.adjustmentRange(spec.path)
            assertNotNull("path must be allowed by MaskSpecs: ${spec.path}", range)
            val recipe = MaskAdjustments.toRecipe(spec, spec.neutralDisplay)!!
            assertTrue("neutral display must be in range: ${spec.path}", recipe in range!!)
            val min = MaskAdjustments.toRecipe(spec, spec.displayMin)!!
            val max = MaskAdjustments.toRecipe(spec, spec.displayMax)!!
            assertTrue(min in range)
            assertTrue(max in range)
        }
    }

    @Test
    fun adjustments_formatting_usesFixedDecimalsAndSign() {
        val exposure = MaskAdjustments.spec(MaskAdjustments.PATH_EXPOSURE)!!
        assertEquals("+0.35", MaskAdjustments.format(exposure, 0.35))
        assertEquals("-0.20", MaskAdjustments.format(exposure, -0.2))
        assertEquals("0.00", MaskAdjustments.format(exposure, 0.0))
        val contrast = MaskAdjustments.spec(MaskAdjustments.PATH_CONTRAST)!!
        assertEquals("-40", MaskAdjustments.format(contrast, -40.0))
        assertEquals("0", MaskAdjustments.format(contrast, 0.0))
        val temperature = MaskAdjustments.spec(MaskAdjustments.PATH_TEMPERATURE)!!
        assertEquals("4500", MaskAdjustments.format(temperature, 4500.0))
        assertEquals("0.12", formatFixed(0.1234, 2))
        assertEquals("-1.0", formatFixed(-1.0, 1))
    }

    // ---------------------------------------------------------------- brush 間引き

    private val pixelRect = FitRect(left = 0f, top = 0f, width = 1000f, height = 1000f)

    @Test
    fun shouldAppend_firstPointAlwaysAccepted() {
        assertTrue(MaskBrushSampler.shouldAppend(null, 0.5, 0.5, pixelRect, 0.1))
    }

    @Test
    fun shouldAppend_requiresRadiusSpacing() {
        val last = EditRecipe.BrushStroke(x = 0.5, y = 0.5, radius = 0.1, opacity = 1.0)
        // 半径 0.1・min(w,h)=1000 → 間隔 100px。0.05（50px）は捨てる。
        assertTrue(!MaskBrushSampler.shouldAppend(last, 0.55, 0.5, pixelRect, 0.1))
        assertTrue(MaskBrushSampler.shouldAppend(last, 0.65, 0.5, pixelRect, 0.1))
        // 非有限は追加しない。
        assertTrue(!MaskBrushSampler.shouldAppend(last, Double.NaN, 0.5, pixelRect, 0.1))
    }

    @Test
    fun append_addsValidStroke() {
        val strokes = emptyList<EditRecipe.BrushStroke>()
        val next = MaskBrushSampler.append(strokes, EditRecipe.BrushStroke(0.2, 0.3, 0.1, 1.0))
        assertEquals(1, next.size)
        assertEquals(0.2, next[0].x, 1e-9)
        assertTrue(strokes.isEmpty()) // 元リストは不変
    }

    @Test
    fun append_rejectsDuplicatesAndInvalidValues() {
        val base = listOf(EditRecipe.BrushStroke(0.2, 0.3, 0.1, 1.0))
        assertSame(base, MaskBrushSampler.append(base, EditRecipe.BrushStroke(0.2, 0.3, 0.1, 1.0)))
        assertSame(base, MaskBrushSampler.append(base, EditRecipe.BrushStroke(1.2, 0.3, 0.1, 1.0)))
        assertSame(base, MaskBrushSampler.append(base, EditRecipe.BrushStroke(0.2, 0.3, 0.0, 1.0)))
        assertSame(base, MaskBrushSampler.append(base, EditRecipe.BrushStroke(0.2, 0.3, 0.6, 1.0)))
        assertSame(base, MaskBrushSampler.append(base, EditRecipe.BrushStroke(0.2, 0.3, 0.1, 1.5)))
    }

    @Test
    fun append_stopsAtMaxStrokes() {
        val full = List(MaskSpecs.MAX_BRUSH_STROKES) { i ->
            EditRecipe.BrushStroke(i / 1000.0, 0.5, 0.1, 1.0)
        }
        assertSame(full, MaskBrushSampler.append(full, EditRecipe.BrushStroke(0.9, 0.9, 0.1, 1.0)))
    }

    // ---------------------------------------------------------------- feather 帯・px 半径

    @Test
    fun linearBandWidth_isFeatherTimesLength() {
        val rect = FitRect(0f, 0f, 400f, 200f)
        // A(0,0)→B(1,0) の長さ 400px。
        assertEquals(400f, MaskOverlayGeometry.linearBandWidthPx(rect, 0.0, 0.0, 1.0, 0.0, 1.0), 1e-3f)
        assertEquals(0f, MaskOverlayGeometry.linearBandWidthPx(rect, 0.0, 0.0, 1.0, 0.0, 0.0), 1e-3f)
        // 範囲外はクランプ（feather 1.0 上限）。
        assertEquals(400f, MaskOverlayGeometry.linearBandWidthPx(rect, 0.0, 0.0, 1.0, 0.0, 3.0), 1e-3f)
    }

    @Test
    fun ellipseAndBrushRadii_followRendererNormalization() {
        val rect = FitRect(0f, 0f, 400f, 200f)
        val (rxPx, ryPx) = MaskOverlayGeometry.ellipseRadiiPx(rect, 0.5, 0.25)
        assertEquals(200f, rxPx, 1e-3f)
        assertEquals(50f, ryPx, 1e-3f)
        // brush は短辺基準（min(400,200)=200）。
        assertEquals(20f, MaskOverlayGeometry.brushRadiusPx(rect, 0.1), 1e-3f)
    }
}
