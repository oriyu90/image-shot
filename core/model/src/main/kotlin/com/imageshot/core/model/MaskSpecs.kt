package com.imageshot.core.model

import java.util.UUID

/**
 * 局所マスク（PR-29a）の正規化・検証・既定値の単一正本。
 *
 * UI（29c）・EditStore（payload 検証/再生）・レンダラ（重み計算/合成）がすべてここを参照し、
 * 型・キー名・値域の個別定義の重複を禁止する（[EditNodeSpecs] と同じ方針）。
 *
 * 契約:
 * - 座標は **正規化 [0,1]**（crop 相対ではない。マスクは常に「geometry 適用後の画像全体」を
 *   基準にした正規化座標で定義する）。
 * - 非有限値・未知キー・範囲外は **正規化失敗（null）**（§7.2 INVALID。既定値へ落とさない）。
 *   呼出し側（EditStore）は失敗を FAIL / PRESERVED_ONLY として扱う。
 * - 既定値の補完（feather 等）とキー順序の正準化は [normalize] が行う。
 * - Android 依存なし（純 Kotlin・JVM テスト対象）。
 */
object MaskSpecs {

    // ---------------------------------------------------------------- 種別・上限

    /** 受理するマスク種別（永続形式の安定ID・この順序で UI に並べる）。 */
    val MASK_TYPES: List<String> = listOf("linear", "radial", "brush")

    const val TYPE_LINEAR = "linear"
    const val TYPE_RADIAL = "radial"
    const val TYPE_BRUSH = "brush"

    /** 1 ドキュメントのマスク数上限（§5.3「リージョン数上限」・失敗時契約の防御）。 */
    const val MAX_MASKS = 8

    /** 1 マスクのブラシストローク数上限（CPU 合成の防御的上限・実測前の安全側固定値）。 */
    const val MAX_BRUSH_STROKES = 256

    /** maskId の最大長（UUID 36 文字 + 余裕。外部入力の肥大を防ぐ）。 */
    const val MAX_MASK_ID_LENGTH = 128

    // ---------------------------------------------------------------- パラメータキー

    const val KEY_X0 = "x0"
    const val KEY_Y0 = "y0"
    const val KEY_X1 = "x1"
    const val KEY_Y1 = "y1"
    const val KEY_CX = "cx"
    const val KEY_CY = "cy"
    const val KEY_RX = "rx"
    const val KEY_RY = "ry"
    const val KEY_ANGLE_DEG = "angleDeg"
    const val KEY_FEATHER = "feather"

    /** linear の正準パラメータキー順。 */
    val LINEAR_PARAM_KEYS: List<String> =
        listOf(KEY_X0, KEY_Y0, KEY_X1, KEY_Y1, KEY_FEATHER)

    /** radial の正準パラメータキー順。 */
    val RADIAL_PARAM_KEYS: List<String> =
        listOf(KEY_CX, KEY_CY, KEY_RX, KEY_RY, KEY_ANGLE_DEG, KEY_FEATHER)

    /** brush の正準パラメータキー順（ストローク列は `brushStrokes` 側）。 */
    val BRUSH_PARAM_KEYS: List<String> = listOf(KEY_FEATHER)

    // ---------------------------------------------------------------- 既定値

    /** linear の feather 既定（1.0 = A→B の全長ランプ）。 */
    const val DEFAULT_LINEAR_FEATHER = 1.0

    /** radial の feather 既定（0.5 = 内側 50% は不透明・外側 50% でランプ）。 */
    const val DEFAULT_RADIAL_FEATHER = 0.5

    /** brush の feather 既定（0.3）。 */
    const val DEFAULT_BRUSH_FEATHER = 0.3

    /** radial の角度既定（0 = 軸平行）。 */
    const val DEFAULT_ANGLE_DEG = 0.0

    // ---------------------------------------------------------------- 重み計算用の範囲定数

    /** radial の rx/ry の下限（0 除算・極端な伸長を防ぐ・この値**超**のみ受理）。 */
    const val MIN_RADIAL_RADIUS = 1.0e-4

    /** radial の rx/ry の上限（正規化座標）。 */
    const val MAX_RADIAL_RADIUS = 1.0

    /** brush ストローク半径の下限（この値**超**のみ受理）。 */
    const val MIN_BRUSH_RADIUS = 1.0e-4

    /** brush ストローク半径の上限（0.5 = 短辺の半分）。 */
    const val MAX_BRUSH_RADIUS = 0.5

    /** linear の A→B 長の二乗の下限（A==B を棄却する閾値）。 */
    const val MIN_LINEAR_LENGTH_SQ = 1.0e-12

    /** feather の範囲（全種別共通）。 */
    const val MIN_FEATHER = 0.0
    const val MAX_FEATHER = 1.0

    // ---------------------------------------------------------------- adjustments

    /**
     * マスク内 adjustments に許可するレシピパスと範囲（レシピドメイン値。
     * スライダー値ではない点に注意: contrast 等は -1..1、露出のみ EV、温度のみ絶対 K）。
     */
    val ADJUSTMENT_RANGES: Map<String, ClosedFloatingPointRange<Double>> = LinkedHashMap<String, ClosedFloatingPointRange<Double>>().apply {
        put("develop.tone.exposureEv", -5.0..5.0)
        put("develop.tone.contrast", -1.0..1.0)
        put("develop.tone.highlights", -1.0..1.0)
        put("develop.tone.shadows", -1.0..1.0)
        put("develop.tone.whites", -1.0..1.0)
        put("develop.tone.blacks", -1.0..1.0)
        put("develop.whiteBalance.tempKelvin", 2000.0..12000.0)
        put("develop.whiteBalance.tint", -1.0..1.0)
        put("develop.color.vibrance", -1.0..1.0)
        put("develop.color.saturation", -1.0..1.0)
    }

    /** 許可する adjustments パスの正準順序（payload のキー順序もこの順）。 */
    val ADJUSTMENT_PATHS: List<String> = ADJUSTMENT_RANGES.keys.toList()

    /** パスの許可範囲（未知パスは null）。 */
    fun adjustmentRange(path: String): ClosedFloatingPointRange<Double>? = ADJUSTMENT_RANGES[path]

    // ---------------------------------------------------------------- 公開API

    /** 種別の正準パラメータキー順（未知種別は null）。 */
    fun paramKeys(maskType: String): List<String>? = when (maskType) {
        TYPE_LINEAR -> LINEAR_PARAM_KEYS
        TYPE_RADIAL -> RADIAL_PARAM_KEYS
        TYPE_BRUSH -> BRUSH_PARAM_KEYS
        else -> null
    }

    /**
     * 種別の既定パラメータ（正準キー順・正規化済み）。
     * 必須キー（linear の x0..y1、radial の cx/cy/rx/ry）は既定を持たないため含めない
     * （未指定は [normalize] が失敗させる）。
     */
    fun defaultParams(maskType: String): Map<String, Double>? = when (maskType) {
        TYPE_LINEAR -> linkedMapOf(KEY_FEATHER to DEFAULT_LINEAR_FEATHER)
        TYPE_RADIAL -> linkedMapOf(
            KEY_ANGLE_DEG to DEFAULT_ANGLE_DEG,
            KEY_FEATHER to DEFAULT_RADIAL_FEATHER,
        )
        TYPE_BRUSH -> linkedMapOf(KEY_FEATHER to DEFAULT_BRUSH_FEATHER)
        else -> null
    }

    /** 新規 maskId（衝突しない安定ID）。 */
    fun newMaskId(): String = UUID.randomUUID().toString()

    /**
     * マスクノードを正規化する。正規化できない入力は null（既定値へ落とさない）。
     *
     * 検証:
     * - maskId は空でなく [MAX_MASK_ID_LENGTH] 以下、maskType は [MASK_TYPES]、version >= 1。
     * - parameters: 未知キー・非有限・範囲外は失敗。必須キー欠落は失敗。feather/angleDeg は
     *   既定補完（不足時）。順序は [paramKeys] の順へ正準化。
     * - linear: x0..y1 ∈ [0,1]・A!=B（[MIN_LINEAR_LENGTH_SQ]）。
     * - radial: cx/cy ∈ [0,1]・rx/ry ∈ (MIN_RADIAL_RADIUS, 1]・angleDeg は有限を [0,360) へ正準化。
     * - brush: parameters は feather のみ・ストロークは [MAX_BRUSH_STROKES] 件以下で
     *   x/y ∈ [0,1]・radius ∈ (MIN_BRUSH_RADIUS, MAX_BRUSH_RADIUS]・opacity ∈ [0,1]。
     * - adjustments: [ADJUSTMENT_PATHS] のみ・範囲内・有限。順序は [ADJUSTMENT_PATHS] の順。
     */
    fun normalize(node: EditRecipe.MaskNode): EditRecipe.MaskNode? {
        val maskId = node.maskId
        if (maskId.isBlank() || maskId.length > MAX_MASK_ID_LENGTH) return null
        if (node.maskType !in MASK_TYPES) return null
        if (node.version < 1) return null
        val parameters = normalizeParameters(node.maskType, node.parameters) ?: return null
        val adjustments = normalizeAdjustments(node.adjustments) ?: return null
        val strokes = normalizeStrokes(node.maskType, node.brushStrokes) ?: return null
        return EditRecipe.MaskNode(
            maskId = maskId,
            maskType = node.maskType,
            version = node.version,
            parameters = parameters,
            adjustments = adjustments,
            brushStrokes = strokes,
        )
    }

    /**
     * マスク列の正規化（件数上限・maskId 重複の拒否を含む）。
     * 1件でも不正、または件数超過・重複があれば null（部分適用を作らない）。
     * 空リストは許可（全削除）。
     */
    fun normalizeAll(nodes: List<EditRecipe.MaskNode>): List<EditRecipe.MaskNode>? {
        if (nodes.size > MAX_MASKS) return null
        val normalized = ArrayList<EditRecipe.MaskNode>(nodes.size)
        val seen = HashSet<String>(nodes.size)
        for (node in nodes) {
            val n = normalize(node) ?: return null
            if (!seen.add(n.maskId)) return null
            normalized += n
        }
        return normalized
    }

    // ---------------------------------------------------------------- 内部実装

    private fun normalizeParameters(
        maskType: String,
        raw: Map<String, Double>,
    ): Map<String, Double>? {
        val keys = paramKeys(maskType) ?: return null
        // 未知キーは失敗（黙って捨てない = 値の無断欠落を作らない）。
        if (raw.keys.any { it !in keys }) return null

        fun required(key: String, min: Double, max: Double): Double? {
            val v = raw[key] ?: return null
            if (!v.isFinite() || v < min || v > max) return null
            return v
        }

        fun optional(key: String, default: Double, min: Double, max: Double): Double? {
            val v = raw[key] ?: return default
            if (!v.isFinite() || v < min || v > max) return null
            return v
        }

        val values = HashMap<String, Double>(keys.size)
        when (maskType) {
            TYPE_LINEAR -> {
                for (key in listOf(KEY_X0, KEY_Y0, KEY_X1, KEY_Y1)) {
                    values[key] = required(key, 0.0, 1.0) ?: return null
                }
                val dx = values.getValue(KEY_X1) - values.getValue(KEY_X0)
                val dy = values.getValue(KEY_Y1) - values.getValue(KEY_Y0)
                // A==B（退化）は重みが定義できないため拒否。
                if (dx * dx + dy * dy < MIN_LINEAR_LENGTH_SQ) return null
                values[KEY_FEATHER] =
                    optional(KEY_FEATHER, DEFAULT_LINEAR_FEATHER, MIN_FEATHER, MAX_FEATHER) ?: return null
            }
            TYPE_RADIAL -> {
                values[KEY_CX] = required(KEY_CX, 0.0, 1.0) ?: return null
                values[KEY_CY] = required(KEY_CY, 0.0, 1.0) ?: return null
                // rx/ry は (MIN_RADIAL_RADIUS, MAX_RADIAL_RADIUS]。
                for (key in listOf(KEY_RX, KEY_RY)) {
                    val v = raw[key] ?: return null
                    if (!v.isFinite() || v <= MIN_RADIAL_RADIUS || v > MAX_RADIAL_RADIUS) return null
                    values[key] = v
                }
                val rawAngle = raw[KEY_ANGLE_DEG] ?: DEFAULT_ANGLE_DEG
                // 角度は任意の有限値を [0,360) へ正準化（[EditNodeSpecs.canonicalAngleDeg] と同一規則）。
                values[KEY_ANGLE_DEG] = EditNodeSpecs.canonicalAngleDeg(rawAngle) ?: return null
                values[KEY_FEATHER] =
                    optional(KEY_FEATHER, DEFAULT_RADIAL_FEATHER, MIN_FEATHER, MAX_FEATHER) ?: return null
            }
            TYPE_BRUSH -> {
                values[KEY_FEATHER] =
                    optional(KEY_FEATHER, DEFAULT_BRUSH_FEATHER, MIN_FEATHER, MAX_FEATHER) ?: return null
            }
        }
        // 正準キー順の LinkedHashMap（payload のキー順序・再現性の正本）。
        val ordered = LinkedHashMap<String, Double>(keys.size)
        for (key in keys) ordered[key] = values.getValue(key)
        return ordered
    }

    private fun normalizeAdjustments(raw: Map<String, Double>): Map<String, Double>? {
        if (raw.isEmpty()) return emptyMap()
        val ordered = LinkedHashMap<String, Double>()
        // 未知キーは失敗（範囲表の外は「未対応パス」= 適用できないため黙って落とさない）。
        for ((path, value) in raw) {
            if (!ADJUSTMENT_RANGES.containsKey(path)) return null
        }
        for (path in ADJUSTMENT_PATHS) {
            val value = raw[path] ?: continue
            val range = ADJUSTMENT_RANGES.getValue(path)
            if (!value.isFinite() || value < range.start || value > range.endInclusive) return null
            ordered[path] = value
        }
        return ordered
    }

    private fun normalizeStrokes(
        maskType: String,
        strokes: List<EditRecipe.BrushStroke>,
    ): List<EditRecipe.BrushStroke>? {
        if (maskType != TYPE_BRUSH) {
            // brush 以外にストロークが入っている payload は形状不一致（黙って捨てない）。
            return if (strokes.isEmpty()) emptyList() else null
        }
        if (strokes.size > MAX_BRUSH_STROKES) return null
        val out = ArrayList<EditRecipe.BrushStroke>(strokes.size)
        for (s in strokes) {
            if (!s.x.isFinite() || s.x < 0.0 || s.x > 1.0) return null
            if (!s.y.isFinite() || s.y < 0.0 || s.y > 1.0) return null
            if (!s.radius.isFinite() || s.radius <= MIN_BRUSH_RADIUS || s.radius > MAX_BRUSH_RADIUS) return null
            if (!s.opacity.isFinite() || s.opacity < 0.0 || s.opacity > 1.0) return null
            out += EditRecipe.BrushStroke(x = s.x, y = s.y, radius = s.radius, opacity = s.opacity)
        }
        return out
    }
}
