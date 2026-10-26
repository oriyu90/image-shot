package com.imageshot.core.render

import com.imageshot.core.model.AdjustmentId
import com.imageshot.core.model.EditRecipe
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 8bit sRGB 暫定パイプライン用のトーン処理カーブ（式設計）。
 *
 * 【明示】画質は「実測ではなく式設計」である。以下は全て設計上の近似であり、
 * golden テスト・ColorChecker・ΔE 計測は未実施。ADR-005 の Linear Rec.2020 float
 * パイプラインの本命ではなく、UI 配線と同値契約の土台（8bit sRGB preview 専用）。
 *
 * 設計:
 * - 全ての点操作（露出/温度/白黒レベル/シャドウ/ハイライト/コントラスト）は
 *   1入力→1出力の単調増加写像として定義し、チャンネルごとの 256 段 LUT に合成する。
 *   白黒レベル/シャドウ/ハイライトは放物線バンプを作用半面のみに加算する
 *   （境界で 0 → 連続・単調を保持、対側は恒等）。
 * - 露出・温度のみ線形光量ドメインで掛ける（sRGB ← -> 線形 変換を含む）。
 * - 彩度・自然な彩度（Vibrance）のみ画素ごとの残差演算（LUT化不能）。
 * - トーンカーブ: スライダー写像後の sRGB 表示値ドメインで適用する。
 *   master（全体）カーブは R/G/B 等量に適用する（グレー軸を歪めない単調再写像）。
 *   R/G/B チャンネル別カーブは PR-28a から適用する（master の直接後・各チャンネルに独立適用）。
 *   いずれも中立カーブは恒等 short-circuit（量子化ドリフトを作らない）。不正形（正規化失敗）
 *   は恒等に落とす（警告側は通知する）。画質（色再現）の定量評価は golden ΔE（device 実測）
 *   まで行わない — 本クラスの KDoc は式設計を明示する。ディテール/局所マスク/ジオメトリは本クラスには存在しない。
 *
 * パイプライン順（RecipeDevelopApplier の正本と同一）:
 *   sRGB→線形 → 露出 → 温度利得 → tint利得（両者の積を合成して1回掛ける） → 黒レベル →
 *   シャドウ → 白レベル → ハイライト → コントラスト → 線形→sRGB → master カーブ（本LUT内）→
 *   [画素ループ: 彩度/自然な彩度 → HSL]。
 */
object RecipeToneCurve {

    // ---- sRGB 伝達関数（IEC 61966-2-1, double 精度）----

    fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(l: Double): Double =
        if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1.0 / 2.4) - 0.055

    /** 非有限入力を中立へ落とす（§7.1: 非有限値は無効）。 */
    private fun finite(v: Double): Boolean = v.isFinite()

    /** 温度 → R/G/B のチャンネル利得（近似）。K=null（as-shot）は中立。 */
    fun tempGains(tempKelvin: Double?): DoubleArray {
        if (tempKelvin == null || !finite(tempKelvin) || tempKelvin <= 100.0 ||
            tempKelvin > 300_000.0
        ) {
            return doubleArrayOf(1.0, 1.0, 1.0)
        }
        val mired = 1_000_000.0 / tempKelvin
        val rel = ((mired - 1_000_000.0 / AdjustmentId.TEMP_NEUTRAL_KELVIN) / 346.0)
            .coerceIn(-1.0, 1.0)
        // rel > 0（暖色、低K）→ R 増 / B 減。 rel < 0（寒色、高K）→ R 減 / B 増。
        val strength = 0.4
        return doubleArrayOf(1.0 + strength * rel, 1.0, 1.0 - strength * rel)
    }

    /**
     * tint → R/G/B のチャンネル利得（設計上の近似・未検証）。
     *
     * 【設計段であり画質検証は未実施】LR 風の緑／マゼンタ軸の近似で、
     *   t > 0（緑寄り）: G *= (1 + 0.30 t)、R *= (1 − 0.15 t)、B *= (1 − 0.15 t)
     *   t < 0（マゼンタ寄り）: 逆方向（R・B 増 / G 減）。
     * 係数（+0.30 / −0.15）は温度利得（strength 0.4）と同格の暫定近似値であり、
     * golden テスト・ΔE 計測は未実施。グレー軸の輝度は t = ±1 で積が最大 ≈ (1.30×0.85) と
     * 1から逸脱するが、温度利得の前例（luma 合成値の1からの逸脱を許容する）と同じ方針で受け
     * 入れ、画質への実際の影響は後段 ADR/実測で判断する。
     * null / 非有限 → 中立 [1,1,1]。範囲外は [-1,1] へクランプ。
     * 適用点は [buildChannelLuts]（温度利得との要素積を合成して1回掛ける）。
     */
    fun tintGains(tint: Double?): DoubleArray {
        if (tint == null || !finite(tint)) return doubleArrayOf(1.0, 1.0, 1.0)
        val t = tint.coerceIn(-1.0, 1.0)
        return doubleArrayOf(1.0 - 0.15 * t, 1.0 + 0.30 * t, 1.0 - 0.15 * t)
    }

    // ---- トーン区分写像（連続・単調、作用半面のみ変化）----
    // 各写像はバンプ（放物線 4l(0.5−l) または 4(l−0.5)(1−l)）を作用半分のみ加算する。
    // バンプは両境界で 0 になるので連続、かつ係数 ≤ 0.5 で単調（傾き 0 到達は接点のみ）。
    // 作用半面（元の扱う領域）外と [0,1] 域外は恒等。

    /** 黒レベル b: 下半分を平滑に持ち上げ／沈める（b>0 でリフト）。 */
    internal fun mapBlacks(l: Double, b: Double): Double =
        if (l in 0.0..0.5) l + 0.35 * b * (4.0 * l * (0.5 - l)) else l

    /** シャドウ s: 黒レベルより強い効き（0.5 係数）の同一形式。 */
    internal fun mapShadows(l: Double, s: Double): Double =
        if (l in 0.0..0.5) l + 0.5 * s * (4.0 * l * (0.5 - l)) else l

    /** 白レベル w: 上半分を平滑に持ち上げ／抑える（w>0 で白寄り）。 */
    internal fun mapWhites(l: Double, w: Double): Double =
        if (l in 0.5..1.0) l + 0.35 * w * (4.0 * (l - 0.5) * (1.0 - l)) else l

    /** ハイライト h: 白レベルより強い効き（0.5 係数）の同一形式。 */
    internal fun mapHighlights(l: Double, h: Double): Double =
        if (l in 0.5..1.0) l + 0.5 * h * (4.0 * (l - 0.5) * (1.0 - l)) else l

    /** コントラスト c: 中央で強く端で効かない S 曲線（2次ブレンド）。端点保持。 */
    internal fun mapContrast(l: Double, c: Double): Double {
        val stretched = 0.5 + (l - 0.5) * (1.0 + 0.6 * c)
        val t = (2.0 * l - 1.0).coerceIn(-1.0, 1.0)
        val blend = 1.0 - t * t // 1 at mid, 0 at 0/1
        return l + blend * (stretched - l)
    }

    /**
     * チャンネル LUT（R[0..255], G, B を 768 要素に連結）を構築する。
     * 展開後の値は sRGB 表示値 0..255 の整数。
     * LUT にトーン + master カーブ + R/G/B チャンネルカーブを合成する
     * （スライダー → master → チャンネルの順・恒等カーブ short-circuit は量子化ドリフトを作らない）。
     */
    fun buildChannelLuts(develop: EditRecipe.DevelopParams): IntArray {
        val tone = develop.tone
        val exposureEv = if (finite(tone.exposureEv)) tone.exposureEv.coerceIn(-5.0, 5.0) else 0.0
        val exScale = 2.0.pow(exposureEv)

        fun norm(v: Double): Double = if (finite(v)) v.coerceIn(-1.0, 1.0) else 0.0
        val contrast = norm(tone.contrast)
        val highlights = norm(tone.highlights)
        val shadows = norm(tone.shadows)
        val whites = norm(tone.whites)
        val blacks = norm(tone.blacks)

        val temp = tempGains(develop.whiteBalance.tempKelvin)
        val tint = tintGains(develop.whiteBalance.tint)
        // 温度利得 × tint利得 を要素積で合成（同一の設計段近似系・LUT 内で1回の掛け算で完結）。
        val gains = DoubleArray(3) { temp[it] * tint[it] }

        // master カーブ（中立なら null = 恒等。曲線評価は評価済み制御点のみで行う）。
        val curvePoints: List<List<Double>>? = when {
            RecipeCurve.isNeutral(develop.curve.rgb) -> null
            else -> RecipeCurve.normalized(develop.curve.rgb) // 不正形は恒等に落とす（警告側は通知する）。
        }

        // R/G/B チャンネルカーブ（PR-28a）: master の直接後・sRGB 表示値ドメインで
        // 各チャンネルに独立適用する。空または中立なら null = 恒等 short-circuit
        // （master と同一規約。恒等でない有効カーブのみ評価する）。不正形は恒等に落とす
        // （master と同一規約・警告側は通知する）。
        // master-only レシピ（全チャンネル null）では以降の評価を一切追加しないため、
        // 既存 golden ケース（bit 決定論）の計算順序・値は不変（PR-28a 保全契約）。
        val channelCurves: List<List<List<Double>>?> = run {
            fun curveOf(points: List<List<Double>>): List<List<Double>>? = when {
                points.isEmpty() -> null
                RecipeCurve.isNeutral(points) -> null
                else -> RecipeCurve.normalized(points)
            }
            listOf(
                curveOf(develop.curve.r),
                curveOf(develop.curve.g),
                curveOf(develop.curve.b),
            )
        }

        val lut = IntArray(768)
        for (v in 0..255) {
            val linear = srgbToLinear(v / 255.0) * exScale
            for (ch in 0..2) {
                var l = linear * gains[ch]
                l = mapBlacks(l, blacks)
                l = mapShadows(l, shadows)
                l = mapWhites(l, whites)
                l = mapHighlights(l, highlights)
                l = mapContrast(l, contrast)
                l = l.coerceIn(0.0, 1.0)
                var display = linearToSrgb(l)
                if (curvePoints != null) display = RecipeCurve.evaluate(curvePoints, display)
                val channelCurve = channelCurves[ch]
                if (channelCurve != null) display = RecipeCurve.evaluate(channelCurve, display)
                lut[ch * 256 + v] = (display.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255)
            }
        }
        return lut
    }

    /**
     * 画素ごとの残差演算（彩度/自然な彩度）。
     * 書込み先 [out] に正規化 sRGB 値 0..1 を返す。零確保のため呼出し側バッファ再利用。
     */
    class ColorMixer(vibrance: Double, saturation: Double) {
        private val sat: Float =
            (if (saturation.isFinite()) saturation.coerceIn(-1.0, 1.0) else 0.0).toFloat()
        private val vib: Float =
            (if (vibrance.isFinite()) vibrance.coerceIn(-1.0, 1.0) else 0.0).toFloat()

        fun mix(r: Float, g: Float, b: Float, out: FloatArray) {
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            // 1) 彩度: 輝度からの距離を (1+sat) 倍。sat=-1 で無彩、+1 で 2 倍。
            var rr = lum + (r - lum) * (1.0f + sat)
            var gg = lum + (g - lum) * (1.0f + sat)
            var bb = lum + (b - lum) * (1.0f + sat)
            // 2) 自然な彩度: 彩度が低い画素ほど効く重み (1-satPix)。
            val mx = max(max(rr, gg), bb)
            val mn = min(min(rr, gg), bb)
            val satPix = if (mx > 1.0e-5f) (mx - mn) / mx else 0.0f
            val f = 1.0f + vib * (1.0f - satPix)
            rr = lum + (rr - lum) * f
            gg = lum + (gg - lum) * f
            bb = lum + (bb - lum) * f
            out[0] = rr.coerceIn(0.0f, 1.0f)
            out[1] = gg.coerceIn(0.0f, 1.0f)
            out[2] = bb.coerceIn(0.0f, 1.0f)
        }
    }
}
