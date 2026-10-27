package com.imageshot.feature.editor

import com.imageshot.core.model.EditNodeSpecs
import com.imageshot.core.model.EditRecipe
import com.imageshot.core.model.MaskSpecs
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 局所マスク（PR-29c）の UI 編集ロジック（純 Kotlin・JVM テスト対象）。
 *
 * ここに置くのは「画面座標↔正規化座標の写像」「ハンドル位置と移動のクランプ」
 * 「新規マスクの既定値」「表示スケール（レシピドメイン値↔スライダー表示値）」
 * 「ブラシの間引き」のみで、永続化・描画・履歴は [EditorViewModel] と core が担う。
 *
 * 座標系の契約（core と同一・[com.imageshot.core.render.MaskWeight]）:
 * - マスクの座標は **geometry 適用後の画像全体を [0,1]² に正規化した値**（crop 相対ではない）。
 * - 画面側は `ContentScale.Fit` の実表示矩形を [MaskOverlayGeometry.fitRect] で求め、
 *   その矩形内で線形写像する（レターボックスを考慮）。
 * - プレビューのズーム/パンは画像と overlay の Canvas に **同じ graphicsLayer** を掛けるため、
 *   写像は「Canvas ローカル座標」で閉じる（scale/offset を写像式へ持ち込まない）。
 */
internal data class ScreenPoint(val x: Float, val y: Float)

/** `ContentScale.Fit` で画像が実際に描かれる矩形（Canvas ローカル px）。 */
internal data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** 正規化座標↔画面座標の写像（レターボックス対応・[FitRect] 基準）。 */
internal object MaskOverlayGeometry {

    /**
     * `ContentScale.Fit` の実表示矩形。画像と容器のアスペクト比の大きい側に合わせて
     * 中央寄せする（Android の `ContentScale.Fit` と同じ規則）。
     * 不正な寸法（0 以下）は null。
     */
    fun fitRect(containerWidth: Int, containerHeight: Int, imageWidth: Int, imageHeight: Int): FitRect? {
        if (containerWidth <= 0 || containerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
        val containerAspect = containerWidth.toDouble() / containerHeight.toDouble()
        val imageAspect = imageWidth.toDouble() / imageHeight.toDouble()
        val width: Double
        val height: Double
        if (imageAspect >= containerAspect) {
            width = containerWidth.toDouble()
            height = width / imageAspect
        } else {
            height = containerHeight.toDouble()
            width = height * imageAspect
        }
        return FitRect(
            left = ((containerWidth.toDouble() - width) / 2.0).toFloat(),
            top = ((containerHeight.toDouble() - height) / 2.0).toFloat(),
            width = width.toFloat(),
            height = height.toFloat(),
        )
    }

    /** 正規化座標 → 画面座標。 */
    fun toScreen(rect: FitRect, x: Double, y: Double): ScreenPoint = ScreenPoint(
        x = rect.left + (x * rect.width).toFloat(),
        y = rect.top + (y * rect.height).toFloat(),
    )

    /**
     * 画面座標 → 正規化座標（0..1 にクランプ）。画像矩形が退化している場合は null。
     * 画像外（レターボックス上）のドラッグは端へ寄せる（操作を失わない）。
     */
    fun toNormalized(rect: FitRect, screenX: Float, screenY: Float): Pair<Double, Double>? {
        if (rect.width <= 0f || rect.height <= 0f) return null
        val x = ((screenX - rect.left) / rect.width).toDouble()
        val y = ((screenY - rect.top) / rect.height).toDouble()
        if (!x.isFinite() || !y.isFinite()) return null
        return x.coerceIn(0.0, 1.0) to y.coerceIn(0.0, 1.0)
    }

    /** 正規化 2 点の画面距離（px・縦横比を反映）。 */
    fun distancePx(rect: FitRect, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = (ax - bx) * rect.width
        val dy = (ay - by) * rect.height
        return sqrt(dx * dx + dy * dy)
    }

    /** 当たり判定の許容距離内で最も近いハンドル（無ければ null）。 */
    fun nearestHandle(
        node: EditRecipe.MaskNode,
        rect: FitRect,
        screenX: Float,
        screenY: Float,
        tolerancePx: Float,
    ): MaskHandles.Kind? {
        var best: MaskHandles.Kind? = null
        var bestDist = Double.MAX_VALUE
        for (handle in MaskHandles.handlesOf(node)) {
            val point = toScreen(rect, handle.x, handle.y)
            val dx = (point.x - screenX).toDouble()
            val dy = (point.y - screenY).toDouble()
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestDist) {
                bestDist = dist
                best = handle.kind
            }
        }
        return if (bestDist <= tolerancePx.toDouble()) best else null
    }

    /**
     * radial の楕円半径（px）。rx は幅・ry は高さ基準の正規化値なので、
     * 画面では `rx*width` / `ry*height` になる（[com.imageshot.core.render.MaskWeight] と同一）。
     */
    fun ellipseRadiiPx(rect: FitRect, rx: Double, ry: Double): Pair<Float, Float> =
        (rx * rect.width).toFloat().coerceAtLeast(0f) to (ry * rect.height).toFloat().coerceAtLeast(0f)

    /** brush の 1 stroke 半径（px）。core は距離を `min(w,h)` で正規化するため短辺基準。 */
    fun brushRadiusPx(rect: FitRect, radius: Double): Float =
        (radius * min(rect.width, rect.height).toDouble()).toFloat().coerceAtLeast(1f)

    /**
     * linear の feather 帯の画面幅（px）。feather は A→B の長さに対する割合なので
     * `feather * |AB|` が帯の全幅になる（境界線が帯の中央）。
     */
    fun linearBandWidthPx(rect: FitRect, x0: Double, y0: Double, x1: Double, y1: Double, feather: Double): Float {
        val length = distancePx(rect, x0, y0, x1, y1)
        val clamped = if (feather.isFinite()) feather.coerceIn(MaskSpecs.MIN_FEATHER, MaskSpecs.MAX_FEATHER) else 0.0
        return (clamped * length).toFloat().coerceAtLeast(0f)
    }
}

/** マスク形状のハンドル（線形は A/B・radial は中心/rx/ry）。 */
internal object MaskHandles {

    enum class Kind { LINEAR_A, LINEAR_B, RADIAL_CENTER, RADIAL_RX, RADIAL_RY }

    data class Handle(val kind: Kind, val x: Double, val y: Double)

    /**
     * 半径ハンドルの UI 下限（正規化）。`MaskSpecs.MIN_RADIAL_RADIUS` は 1e-4 で
     * 掴めないため、UI では 2% を下限にする（保存値はこの限りでない）。
     */
    const val MIN_RADIAL_HANDLE_RADIUS = 0.02

    /** 表示・当たり判定に使うハンドル列（brush は持たない）。 */
    fun handlesOf(node: EditRecipe.MaskNode): List<Handle> {
        val p = node.parameters
        return when (node.maskType) {
            MaskSpecs.TYPE_LINEAR -> {
                val x0 = p[MaskSpecs.KEY_X0] ?: return emptyList()
                val y0 = p[MaskSpecs.KEY_Y0] ?: return emptyList()
                val x1 = p[MaskSpecs.KEY_X1] ?: return emptyList()
                val y1 = p[MaskSpecs.KEY_Y1] ?: return emptyList()
                listOf(Handle(Kind.LINEAR_A, x0, y0), Handle(Kind.LINEAR_B, x1, y1))
            }
            MaskSpecs.TYPE_RADIAL -> {
                val cx = p[MaskSpecs.KEY_CX] ?: return emptyList()
                val cy = p[MaskSpecs.KEY_CY] ?: return emptyList()
                val rx = p[MaskSpecs.KEY_RX] ?: return emptyList()
                val ry = p[MaskSpecs.KEY_RY] ?: return emptyList()
                // 回転は **正規化座標空間** で行う（core の MaskWeight と同じ）。
                // 画面 px で rotate すると縦横比でシアーし、角度付き楕円とハンドルがずれる。
                val theta = Math.toRadians(
                    p[MaskSpecs.KEY_ANGLE_DEG]?.takeIf { it.isFinite() } ?: 0.0,
                )
                val c = cos(theta)
                val s = sin(theta)
                listOf(
                    Handle(Kind.RADIAL_CENTER, cx, cy),
                    Handle(Kind.RADIAL_RX, cx + rx * c, cy + rx * s),
                    Handle(Kind.RADIAL_RY, cx - ry * s, cy + ry * c),
                )
            }
            else -> emptyList()
        }
    }

    /**
     * ハンドル移動（絶対座標を 0..1 にクランプ・radial 半径は UI 下限..
     * [MaskSpecs.MAX_RADIAL_RADIUS]）。正規化できない結果は元のノードを返す
     * （線形の A==B 退化など・「無変化」として扱う）。
     */
    fun move(node: EditRecipe.MaskNode, kind: Kind, x: Double, y: Double): EditRecipe.MaskNode {
        if (!x.isFinite() || !y.isFinite()) return node
        val nx = x.coerceIn(0.0, 1.0)
        val ny = y.coerceIn(0.0, 1.0)
        val keys = MaskSpecs.paramKeys(node.maskType) ?: return node
        val params = LinkedHashMap<String, Double>(keys.size)
        for (key in keys) {
            val value = node.parameters[key] ?: return node
            params[key] = value
        }
        when (kind) {
            Kind.LINEAR_A -> {
                if (node.maskType != MaskSpecs.TYPE_LINEAR) return node
                params[MaskSpecs.KEY_X0] = nx
                params[MaskSpecs.KEY_Y0] = ny
            }
            Kind.LINEAR_B -> {
                if (node.maskType != MaskSpecs.TYPE_LINEAR) return node
                params[MaskSpecs.KEY_X1] = nx
                params[MaskSpecs.KEY_Y1] = ny
            }
            Kind.RADIAL_CENTER -> {
                if (node.maskType != MaskSpecs.TYPE_RADIAL) return node
                params[MaskSpecs.KEY_CX] = nx
                params[MaskSpecs.KEY_CY] = ny
            }
            Kind.RADIAL_RX -> {
                if (node.maskType != MaskSpecs.TYPE_RADIAL) return node
                val cx = params[MaskSpecs.KEY_CX] ?: return node
                val cy = params[MaskSpecs.KEY_CY] ?: return node
                val theta = Math.toRadians(
                    params[MaskSpecs.KEY_ANGLE_DEG]?.takeIf { it.isFinite() } ?: 0.0,
                )
                // ハンドルは正規化空間で中心 + rx·(cosθ, sinθ) にあるため、
                // ドラッグ点のローカル x 成分（u 軸への射影）が新しい rx。
                val rx = (nx - cx) * cos(theta) + (ny - cy) * sin(theta)
                params[MaskSpecs.KEY_RX] =
                    rx.coerceIn(MIN_RADIAL_HANDLE_RADIUS, MaskSpecs.MAX_RADIAL_RADIUS)
            }
            Kind.RADIAL_RY -> {
                if (node.maskType != MaskSpecs.TYPE_RADIAL) return node
                val cx = params[MaskSpecs.KEY_CX] ?: return node
                val cy = params[MaskSpecs.KEY_CY] ?: return node
                val theta = Math.toRadians(
                    params[MaskSpecs.KEY_ANGLE_DEG]?.takeIf { it.isFinite() } ?: 0.0,
                )
                // ry ハンドルは中心 + ry·(−sinθ, cosθ)。ドラッグ点の v 軸射影が新しい ry。
                val ry = -(nx - cx) * sin(theta) + (ny - cy) * cos(theta)
                params[MaskSpecs.KEY_RY] =
                    ry.coerceIn(MIN_RADIAL_HANDLE_RADIUS, MaskSpecs.MAX_RADIAL_RADIUS)
            }
        }
        return MaskSpecs.normalize(node.copy(parameters = params)) ?: node
    }
}

/** 新規マスクの既定形状（[MaskSpecs] の既定値 + 必須キー）。 */
internal object MaskDraft {

    /** radial の既定半径（正規化・中央 30%）。 */
    const val DEFAULT_RADIAL_RADIUS = 0.3

    /** brush の既定ストローク半径（正規化・短辺基準）。 */
    const val DEFAULT_BRUSH_RADIUS = 0.1

    /** brush 半径スライダーの下限（[MaskSpecs.MIN_BRUSH_RADIUS] は 1e-4 で細すぎる）。 */
    const val MIN_BRUSH_RADIUS = 0.01

    /**
     * 種別ごとの新規ノード。未知種別は null。
     * - linear: 中央の斜め線（A=左下 25%/75% → B=右上 75%/25%）。
     * - radial: 中央・rx=ry=[DEFAULT_RADIAL_RADIUS]。
     * - brush: ストローク空（半径は UI のツール設定を使う）。
     */
    fun create(maskType: String, maskId: String = MaskSpecs.newMaskId()): EditRecipe.MaskNode? {
        val defaults = MaskSpecs.defaultParams(maskType) ?: return null
        val required: Map<String, Double> = when (maskType) {
            MaskSpecs.TYPE_LINEAR -> linkedMapOf(
                MaskSpecs.KEY_X0 to 0.25,
                MaskSpecs.KEY_Y0 to 0.75,
                MaskSpecs.KEY_X1 to 0.75,
                MaskSpecs.KEY_Y1 to 0.25,
            )
            MaskSpecs.TYPE_RADIAL -> linkedMapOf(
                MaskSpecs.KEY_CX to 0.5,
                MaskSpecs.KEY_CY to 0.5,
                MaskSpecs.KEY_RX to DEFAULT_RADIAL_RADIUS,
                MaskSpecs.KEY_RY to DEFAULT_RADIAL_RADIUS,
            )
            else -> emptyMap()
        }
        val params = LinkedHashMap<String, Double>(required.size + defaults.size)
        params.putAll(required)
        params.putAll(defaults)
        return MaskSpecs.normalize(
            EditRecipe.MaskNode(maskId = maskId, maskType = maskType, parameters = params),
        )
    }

    /** brush 半径のクランプ（非有限は既定値へ）。 */
    fun clampBrushRadius(radius: Double): Double {
        if (!radius.isFinite()) return DEFAULT_BRUSH_RADIUS
        return radius.coerceIn(MIN_BRUSH_RADIUS, MaskSpecs.MAX_BRUSH_RADIUS)
    }
}

/** feather / angleDeg のスライダー表示変換。 */
internal object MaskSlider {

    /** 保存値 [0,360) → 表示 -180..180。 */
    fun angleDisplay(savedDeg: Double): Double {
        if (!savedDeg.isFinite()) return 0.0
        val canonical = ((savedDeg % 360.0) + 360.0) % 360.0
        return if (canonical > 180.0) canonical - 360.0 else canonical
    }

    /** 表示 -180..180 → 保存値 [0,360)（正準化は core と同一規則）。 */
    fun angleSaved(displayDeg: Double): Double =
        EditNodeSpecs.canonicalAngleDeg(displayDeg) ?: MaskSpecs.DEFAULT_ANGLE_DEG

    /** feather のクランプ（0..1・非有限は種別既定）。 */
    fun feather(value: Double, fallback: Double): Double {
        if (!value.isFinite()) return fallback
        return value.coerceIn(MaskSpecs.MIN_FEATHER, MaskSpecs.MAX_FEATHER)
    }
}

/**
 * マスク内 adjustments の表示スケール（レシピドメイン値 ↔ スライダー表示値）。
 * スライダーは既存 UI と同じ「-100..100」系に揃え、保存は [MaskSpecs] の許可範囲へ写す。
 */
internal data class MaskAdjustmentSpec(
    val path: String,
    val displayMin: Double,
    val displayMax: Double,
    /** 表示値 → レシピ値の倍率。 */
    val scale: Double,
    /** 表示の小数桁（0 = 整数）。 */
    val decimals: Int,
    /** true なら正値に "+" を付ける。 */
    val signed: Boolean,
    /** 未設定時にスライダーを置く表示値（この値自体は適用されない）。 */
    val neutralDisplay: Double,
)

internal object MaskAdjustments {

    const val PATH_EXPOSURE = "develop.tone.exposureEv"
    const val PATH_CONTRAST = "develop.tone.contrast"
    const val PATH_SATURATION = "develop.color.saturation"
    const val PATH_TEMPERATURE = "develop.whiteBalance.tempKelvin"
    const val PATH_TINT = "develop.whiteBalance.tint"

    /** パネルに出す 5 種（順序は表示順）。 */
    val SPECS: List<MaskAdjustmentSpec> = listOf(
        MaskAdjustmentSpec(PATH_EXPOSURE, -5.0, 5.0, 1.0, 2, true, 0.0),
        MaskAdjustmentSpec(PATH_CONTRAST, -100.0, 100.0, 0.01, 0, true, 0.0),
        MaskAdjustmentSpec(PATH_SATURATION, -100.0, 100.0, 0.01, 0, true, 0.0),
        MaskAdjustmentSpec(PATH_TEMPERATURE, 2000.0, 12000.0, 1.0, 0, false, 6500.0),
        MaskAdjustmentSpec(PATH_TINT, -100.0, 100.0, 0.01, 0, true, 0.0),
    )

    fun spec(path: String): MaskAdjustmentSpec? = SPECS.firstOrNull { it.path == path }

    /** 表示値 → レシピ値（範囲外はクランプ・許可パス外/範囲外は null）。 */
    fun toRecipe(spec: MaskAdjustmentSpec, display: Double): Double? {
        if (!display.isFinite()) return null
        val clamped = display.coerceIn(spec.displayMin, spec.displayMax)
        val recipe = clamped * spec.scale
        val range = MaskSpecs.adjustmentRange(spec.path) ?: return null
        if (!recipe.isFinite() || recipe < range.start || recipe > range.endInclusive) return null
        return recipe
    }

    /** レシピ値 → 表示値（表示範囲へクランプ）。 */
    fun toDisplay(spec: MaskAdjustmentSpec, recipe: Double): Double? {
        if (!recipe.isFinite() || spec.scale == 0.0) return null
        val display = recipe / spec.scale
        if (!display.isFinite()) return null
        return display.coerceIn(spec.displayMin, spec.displayMax)
    }

    /** 表示文字列（小数桁は spec・正値の "+" は signed のときのみ）。 */
    fun format(spec: MaskAdjustmentSpec, display: Double): String = formatFixed(display, spec.decimals, spec.signed)
}

/** 小数 [decimals] 桁の表示（ロケール非依存・整数は整数のまま・任意で "+" を付す）。 */
internal fun formatFixed(value: Double, decimals: Int, signed: Boolean = false): String {
    if (!value.isFinite()) return "0"
    val text = String.format(Locale.ROOT, "%.${decimals.coerceIn(0, 3)}f", value)
    return if (signed && value > 0.0) "+$text" else text
}

/**
 * brush のストローク間引き。指の軌跡は毎フレーム届くため、
 * 前回の点から **半径ぶん（画素距離で `radius * min(w,h)`）** 離れるまで捨てる。
 * これで 1 ジェスチャのストローク数が `移動距離 / 半径` 程度に収まる。
 */
internal object MaskBrushSampler {

    /** 間引き間隔（px）。[rect] は実表示矩形（縦横比を反映した画素距離）。 */
    fun spacingPx(rect: FitRect, radius: Double): Double =
        radius * min(rect.width, rect.height).toDouble()

    /** [last] から [x],[y] が十分離れているか（[last] が null なら常に true）。 */
    fun shouldAppend(
        last: EditRecipe.BrushStroke?,
        x: Double,
        y: Double,
        rect: FitRect,
        radius: Double,
    ): Boolean {
        if (last == null) return true
        if (!x.isFinite() || !y.isFinite()) return false
        val dx = (x - last.x) * rect.width
        val dy = (y - last.y) * rect.height
        val threshold = spacingPx(rect, radius)
        return sqrt(dx * dx + dy * dy) >= threshold
    }

    /**
     * ストローク列へ 1 点追加（上限 [max] で ignore・不正値は ignore・既存リストは不変）。
     * 同一内容の連続点も ignore する（ドラッグ中の重複呼び出しで履歴を汚さない）。
     */
    fun append(
        strokes: List<EditRecipe.BrushStroke>,
        stroke: EditRecipe.BrushStroke,
        max: Int = MaskSpecs.MAX_BRUSH_STROKES,
    ): List<EditRecipe.BrushStroke> {
        if (strokes.size >= max) return strokes
        if (!stroke.x.isFinite() || !stroke.y.isFinite()) return strokes
        if (stroke.x < 0.0 || stroke.x > 1.0 || stroke.y < 0.0 || stroke.y > 1.0) return strokes
        if (!stroke.radius.isFinite() || stroke.radius <= MaskSpecs.MIN_BRUSH_RADIUS ||
            stroke.radius > MaskSpecs.MAX_BRUSH_RADIUS
        ) {
            return strokes
        }
        if (!stroke.opacity.isFinite() || stroke.opacity < 0.0 || stroke.opacity > 1.0) return strokes
        val last = strokes.lastOrNull()
        if (last != null &&
            last.x == stroke.x && last.y == stroke.y && last.radius == stroke.radius &&
            last.opacity == stroke.opacity
        ) {
            return strokes
        }
        return strokes + stroke
    }
}
