package com.imageshot.core.model

import kotlin.math.abs
import kotlin.math.ceil

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

    /** 角度の同一判定（度）。浮動小数の表現差だけを吸収する（1e-9 度）。 */
    private const val ANGLE_EPS = 1.0e-9

    /** クロップが正味中立（全画面）とみなす最小誤差。 */
    private const val CROP_EPS = 1.0e-9

    /**
     * 自由クロップの最小スパン（正規化・PR-28b）。UI の縮小操作と検証の下限を一致させる
     * （span 0 は [normalizeGeometry] が失敗にするため、それより十分大きい操作可能下限）。
     */
    const val CROP_MIN_SPAN = 0.02

    /**
     * 角度を正準域 [0, 360) へ正規化する（PR-28b・自由角度）。
     * - 非有限は null（§7.2 INVALID。既定値へ落とさない）。
     * - 360° の倍数は 0°（-0.0 を含まない 0.0）へ、負値は +360 して同じ回転を表す。
     * - 90° の倍数は浮動小数誤差を含めてそのまま（例 89.9999999999 → 90.0）には丸めない。
     *   丸めは [isQuarterTurnAngle] の判定のみで行い、保存値は入力の回転角を保つ。
     */
    fun canonicalAngleDeg(angleDeg: Double): Double? {
        if (!angleDeg.isFinite()) return null
        var a = angleDeg % 360.0
        if (a < 0.0) a += 360.0
        if (a < ANGLE_EPS || a >= 360.0 - ANGLE_EPS) a = 0.0
        return if (a == -0.0) 0.0 else a
    }

    /**
     * 角度が 90°刻み（0/90/180/270）と同一視できるか。
     * 90°回転はピクセル再索引（無劣化）で適用し、それ以外は任意角リサンプル経路へ送る。
     */
    fun isQuarterTurnAngle(angleDeg: Double): Boolean {
        val c = canonicalAngleDeg(angleDeg) ?: return false
        val quarters = c / 90.0
        return abs(quarters - Math.round(quarters).toDouble()) < ANGLE_EPS
    }

    /**
     * 90°刻み基準に対する微調整成分（-45..45・PR-28b の角度スライダー正本）。
     * 例: 0→0 / 12.5→12.5 / 90→0 / 100→10 / 350→-10 / 45→45 / 46→-44。
     * UI は「基準角（90°の倍数）+ 微調整」で連続操作し、保存は [canonicalAngleDeg] の全角で行う。
     */
    fun fineAngleDeg(angleDeg: Double): Double {
        val c = canonicalAngleDeg(angleDeg) ?: 0.0
        // 90°刻みの基準は「微調整 ±45° の区間で最も近い下側の基準」。
        // ちょうど 45°（45/135/225/315）は +45 側へ寄せる（スライダーの連続性を保つ）。
        val base = 90.0 * ceil(c / 90.0 - 0.5)
        return c - base
    }

    /**
     * ジオメトリパラメータを正規化する。
     * - angleDeg は有限なら任意角を受理し、[canonicalAngleDeg] の正準域 [0,360) へ畳み込む
     *   （PR-28b: 90°刻み限定を解除。非有限のみ null = 失敗）。
     * - cropNorm は 4要素・有限のみ。0..1 にクランプし、x0<x1 / y0<y1（最小差 1e-9。降順/span 0 は null = 失敗）。
     */
    fun normalizeGeometry(g: EditRecipe.GeometryParams): EditRecipe.GeometryParams? {
        val angle = canonicalAngleDeg(g.angleDeg) ?: return null
        val crop = g.cropNorm
        if (crop.size != 4) return null
        val x0 = crop[0].coerceIn(0.0, 1.0)
        val y0 = crop[1].coerceIn(0.0, 1.0)
        val x1 = crop[2].coerceIn(0.0, 1.0)
        val y1 = crop[3].coerceIn(0.0, 1.0)
        for (v in g.cropNorm) if (!v.isFinite()) return null
        if (x1 - x0 < CROP_EPS || y1 - y0 < CROP_EPS) return null
        return g.copy(cropNorm = listOf(x0, y0, x1, y1), angleDeg = angle)
    }

    /** ジオメトリが中立（無変換）か。 */
    fun isNeutralGeometry(g: EditRecipe.GeometryParams): Boolean {
        val n = normalizeGeometry(g) ?: return false
        return n.angleDeg == 0.0 && !n.mirrorX && !n.mirrorY &&
            abs(n.cropNorm[0] + n.cropNorm[1]) < CROP_EPS &&
            abs(n.cropNorm[2] - 1.0) < CROP_EPS && abs(n.cropNorm[3] - 1.0) < CROP_EPS
    }

    // ---------------------------------------------------------------- HSL 8系統

    /** 標準8系統のバンドID（正本キー名・永続形式・この順で LUT を並べる）。表示ラベルは feature:editor res（PR-27）。 */
    val HSL_BANDS: List<String> = listOf(
        "red",
        "orange",
        "yellow",
        "green",
        "aqua",
        "blue",
        "purple",
        "magenta",
    )

    /** パラメータID（キー後半）。表示ラベルは feature:editor res（PR-27）。 */
    val HSL_PARAMS: List<String> = listOf(
        "hue",
        "sat",
        "lum",
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
    fun bandIndex(bandId: String): Int? = HSL_BANDS.indexOf(bandId).takeIf { it >= 0 }

    /** パラメータIDのインデックス（未知IDは null）。 */
    fun paramIndex(paramId: String): Int? = HSL_PARAMS.indexOf(paramId).takeIf { it >= 0 }

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
