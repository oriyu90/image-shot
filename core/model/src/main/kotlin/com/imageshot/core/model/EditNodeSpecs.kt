package com.imageshot.core.model

import kotlin.math.abs

/**
 * curve / geometry / HSL イベント（P4 で IMPLEMENTED_M1 に昇格した3種）の
 * 正規化・検証の単一正本。UI・EditStore（payload 検証/再生）・レンダラが
 * すべてここを参照し、個別定義の重複を禁止する（§5.1 スライダーと同一方針）。
 *
 * - 非有限は無効（§7.2 INVALID）。値域超過はクランプ（§7.1 正規化）。
 * - 正規化できない入力は null（上位層は失敗として扱い、既定値へ落とさない）。
 */
object EditNodeSpecs {

    // ---------------------------------------------------------------- カーブ（全体/master）

    /** master カーブの制御点数の上下限（UI も同一値を使用する）。 */
    const val CURVE_MIN_POINTS = 2
    const val CURVE_MAX_POINTS = 16

    /** master カーブの中立（恒等）形状。レシピ既定値と同一。 */
    val CURVE_NEUTRAL_RGB: List<List<Double>> = listOf(listOf(0.0, 0.0), listOf(1.0, 1.0))

    /** master カーブの x が近い制御点を重複とみなす最小間隔（正規化グリッド）。 */
    private const val CURVE_MIN_X_GAP = 1.0e-6

    /**
     * master カーブ制御点を正規化する（[normalize]）。
     * - 各点は 2要素 `(x, y)`、両者有限のみ受理（無効は null）。
     * - x/y を 0..1 にクランプ、x 昇順にソート、近接 x（<[CURVE_MIN_X_GAP]）は先着優先で除去。
     * - 点数は [CURVE_MIN_POINTS]..[CURVE_MAX_POINTS]（超過/不足は null）。
     */
    fun normalizeCurve(points: List<List<Double>>): List<List<Double>>? {
        if (points.size < CURVE_MIN_POINTS || points.size > CURVE_MAX_POINTS) return null
        data class Pt(val x: Double, val y: Double)

        val parsed = mutableListOf<Pt>()
        for (pt in points) {
            if (pt.size != 2) return null
            val x = pt[0]
            val y = pt[1]
            if (!x.isFinite() || !y.isFinite()) return null
            parsed += Pt(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        }
        parsed.sortWith(compareBy({ it.x }, { it.y }))
        val deduped = mutableListOf<Pt>()
        for (pt in parsed) {
            if (deduped.isNotEmpty() && pt.x - deduped.last().x < CURVE_MIN_X_GAP) continue
            deduped += pt
        }
        if (deduped.size < CURVE_MIN_POINTS) return null
        return deduped.map { listOf(it.x, it.y) }
    }

    /** master カーブが中立（恒等写像）か。未正規化入力は正規化して判定する。 */
    fun isNeutralCurve(points: List<List<Double>>): Boolean =
        normalizeCurve(points) == CURVE_NEUTRAL_RGB

    // ---------------------------------------------------------------- ジオメトリ

    /** M1 で受理する角度（90°刻みのみ。自由角度は未実装）。 */
    val ALLOWED_ANGLE_DEG: List<Double> = listOf(0.0, 90.0, 180.0, 270.0)

    /** クロップが正味中立（全画面）とみなす最小誤差。 */
    private const val CROP_EPS = 1.0e-9
    /** angleDeg が 90°刻み集合に一致するか。 */
    fun isAllowedAngle(angleDeg: Double): Boolean =
        ALLOWED_ANGLE_DEG.any { abs(angleDeg - it) < CROP_EPS }

    /**
     * ジオメトリパラメータを正規化する。
     * - angleDeg は [ALLOWED_ANGLE_DEG] のみ受理（それ以外は null = 失敗。クランプで誤魔化さない）。
     * - cropNorm は 4要素・有限のみ。0..1 にクランプし、x0<x1 / y0<y1（最小差 1e-9。降順/span 0 は null = 失敗）。
     */
    fun normalizeGeometry(g: EditRecipe.GeometryParams): EditRecipe.GeometryParams? {
        if (!isAllowedAngle(g.angleDeg)) return null
        val crop = g.cropNorm
        if (crop.size != 4) return null
        val x0 = crop[0].coerceIn(0.0, 1.0)
        val y0 = crop[1].coerceIn(0.0, 1.0)
        val x1 = crop[2].coerceIn(0.0, 1.0)
        val y1 = crop[3].coerceIn(0.0, 1.0)
        for (v in g.cropNorm) if (!v.isFinite()) return null
        if (x1 - x0 < CROP_EPS || y1 - y0 < CROP_EPS) return null
        return g.copy(cropNorm = listOf(x0, y0, x1, y1))
    }

    /** ジオメトリが中立（無変換）か。 */
    fun isNeutralGeometry(g: EditRecipe.GeometryParams): Boolean {
        val n = normalizeGeometry(g) ?: return false
        return n.angleDeg == 0.0 && !n.mirrorX && !n.mirrorY &&
            abs(n.cropNorm[0] + n.cropNorm[1]) < CROP_EPS &&
            abs(n.cropNorm[2] - 1.0) < CROP_EPS && abs(n.cropNorm[3] - 1.0) < CROP_EPS
    }

    // ---------------------------------------------------------------- HSL 8系統

    /** 標準8系統のバンドID（正本キー名・永続形式）と日本語表示ラベルの対。 */
    val HSL_BANDS: List<Pair<String, String>> = listOf(
        "red" to "赤",
        "orange" to "オレンジ",
        "yellow" to "黄",
        "green" to "緑",
        "aqua" to "水",
        "blue" to "青",
        "purple" to "紫",
        "magenta" to "マゼンタ",
    )

    /** パラメータID（キー後半）と日本語表示ラベルの対。 */
    val HSL_PARAMS: List<Pair<String, String>> = listOf(
        "hue" to "色相",
        "sat" to "彩度",
        "lum" to "明るさ",
    )

    /**
     * 色相セグメント境界（度。HSV hue 0..360、昇順・循環）。
     * セグメント k = (BOUNDARIES[k], BOUNDARIES[k+1 mod 8])。区分は標準8系統の核:
     *   red(−15..15), orange(15..45), yellow(45..75), green(75..150),
     *   aqua(150..210), blue(210..270), purple(270..315), magenta(315..345)。
     */
    val HUE_BOUNDARIES_DEG: List<Double> = listOf(15.0, 45.0, 75.0, 150.0, 210.0, 270.0, 315.0, 345.0)

    /** セグメント k に属するバンドID（[HUE_BOUNDARIES_DEG] と対）。末尾が red（345..375 循環）。 */
    val HUE_SEGMENT_BAND_IDS: List<String> = listOf(
        "orange", "yellow", "green", "aqua", "blue", "purple", "magenta", "red",
    )

    /** バンド境界のソフトゾーン幅（度）。境界 ±[HSL_SOFT_EDGE_DEG] を比例ブレンドする。 */
    const val HSL_SOFT_EDGE_DEG = 7.5

    /** 色相シフトの最大値（度）。スライダー ±100 = ±30°（標準現像系に相当する近似）。 */
    const val HSL_HUE_SPAN_DEG = 30.0

    /** バンドIDのインデックス（未知IDは null）。 */
    fun bandIndex(bandId: String): Int? = HSL_BANDS.indexOfFirst { it.first == bandId }.takeIf { it >= 0 }

    /** パラメータIDのインデックス（未知IDは null）。 */
    fun paramIndex(paramId: String): Int? = HSL_PARAMS.indexOfFirst { it.first == paramId }.takeIf { it >= 0 }

    /** 正規化済み key（例 `red.hue`）を生成する。 */
    fun hslKey(bandId: String, paramId: String): String = "$bandId.$paramId"

    /** 正規化済み key のみ分解する（未知バンド/未知パラメータは null）。 */
    fun parseHslKey(key: String): Pair<String, String>? {
        val parts = key.split('.')
        if (parts.size != 2) return null
        val band = parts[0]
        val param = parts[1]
        return if (bandIndex(band) != null && paramIndex(param) != null) band to param else null
    }
}
