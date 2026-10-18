package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * HSL/カラーミキサー（標準8系統×色相/彩度/明るさ）の設計段階実装。
 *
 * 【明示】方式の選択（近似であり、画質の定量評価は未実施）:
 * - 色相・彩度は HSV 空間（hue 0..360 度 / sat 0..1 / val 0..1）で処理する。
 * - 明るさのみ 線形光ドメインで乗算する（sRGB→線形→ゲイン→sRGB、シャドウ潰れの回避が目的）。
 *   ゲインは `2^(lum)`（lum ∈ −1..1 = ±1 EV 相当の対称形）。
 * - バンド判定は色相セグメントの核（EditNodeSpecs.HUE_BOUNDARIES_DEG）で行い、
 *   境界 ±[EditNodeSpecs.HSL_SOFT_EDGE_DEG] を比例ブレンド（隣接バンドのみ配分、総和 = 1）。
 * - ソフトエッジは色相軸のみ。sat/v 軸の分割はしない（M2 以降の改善候補）。
 *
 * パイプライン位置（RecipeDevelopApplier KDoc 参照）: スライダー LUT → master カーブ →
 * 彩度/自然な彩度 → 本モジュール（HSL）。HSL を最後に置く理由: バンド補正が最終的な
 * 全体色に対して行われることを単純化・固定するため。
 *
 * 正本 key は EditNodeSpecs（例 `red.hue`）。未知 key は無視（0 効果・DB 側 PRESERVED_ONLY）。
 */
object RecipeHsl {

    /** パラメータが中立（空 or 全 0 有限）なら HSL ノードを丸ごとスキップできる。 */
    fun isNeutral(params: Map<String, Double>): Boolean =
        params.isEmpty() || params.values.all { it.isFinite() && abs(it) < 1.0e-12 }

    /**
     * hue（度 0..360）のバンド重み。`out`（長さ8、EditNodeSpecs.HSL_BANDS 順）へ加算でなく
     * 上書きで書込む（呼出し側のバッファ再利用契約）。総和は常に 1。
     */
    fun bandWeights(hueDeg: Double, out: DoubleArray) {
        // 初期化（前回値の残留を出さない）。
        for (i in out.indices) out[i] = 0.0
        // 検索範囲を 15..375 に正規化（red の循環セグメント 345..375 を含める）。
        var h = hueDeg
        if (h < 15.0) h += 360.0
        if (h >= 375.0) h -= 360.0 * kotlin.math.floor(h / 360.0) // 安全側（NaN/範囲外ガード後）
        val boundaries = EditNodeSpecs.HUE_BOUNDARIES_DEG
        val segBands = EditNodeSpecs.HUE_SEGMENT_BAND_IDS
        var k = boundaries.size - 1
        for (b in boundaries.indices) {
            if (h >= boundaries[b]) k = b else break
        }
        val segStart = boundaries[k]
        val segWidth = (boundaries[(k + 1) % 8] + if ((k + 1) % 8 == 0) 360.0 else 0.0) - segStart
        val d = h - segStart
        val soft = EditNodeSpecs.HSL_SOFT_EDGE_DEG
        var wPrim = 1.0
        // 始端側（前バンドとの境界ソフトゾーン）。
        if (d < soft) {
            val prev = EditNodeSpecs.bandIndex(segBands[(k + 7) % 8])!!
            val w = (soft - d) / (2.0 * soft)
            out[prev] += w
            wPrim -= w
        }
        // 終端側（次バンドとの境界ソフトゾーン）。
        val dEnd = segWidth - d
        if (dEnd < soft) {
            val next = EditNodeSpecs.bandIndex(segBands[(k + 1) % 8])!!
            val w = (soft - dEnd) / (2.0 * soft)
            out[next] += w
            wPrim -= w
        }
        out[EditNodeSpecs.bandIndex(segBands[k])!!] += wPrim
    }

    /**
     * HSL バンド調整（画素ごと）。[RecipeToneCurve.ColorMixer] と同一契約:
     * 書込み先 [out] に正規化 sRGB 値 0..1 を返す。入力は正規化 sRGB 値 0..1。
     */
    class HslMixer(params: Map<String, Double>) {

        // バンド順: red, orange, yellow, green, aqua, blue, purple, magenta。既定 = 恒等。
        private val hueShiftDeg = DoubleArray(8)
        private val satMul = DoubleArray(8) { 1.0 }
        private val lumGain = DoubleArray(8) { 1.0 }
        private val weights = DoubleArray(8)

        init {
            for ((key, value) in params) {
                val parsed = EditNodeSpecs.parseHslKey(key) ?: continue // 未知 key は無視（0 効果）。
                val band = EditNodeSpecs.bandIndex(parsed.first)!!
                val v = if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0
                when (parsed.second) {
                    "hue" -> hueShiftDeg[band] = v * EditNodeSpecs.HSL_HUE_SPAN_DEG
                    "sat" -> satMul[band] = 1.0 + v // ±1 → 0..2 倍（0 = 無彩）。
                    "lum" -> lumGain[band] = lumGainOf(v)
                }
            }
        }

        fun apply(r: Float, g: Float, b: Float, out: FloatArray) {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val v = maxC
            if (v <= 0.0f) {
                // 純黒: hue/sat 未定義の画素。無通知変換を作らないため恒等で返す。
                out[0] = 0.0f
                out[1] = 0.0f
                out[2] = 0.0f
                return
            }
            val delta = maxC - minC
            // s が 0（無彩）でも hue は影響しない（s 積は 0 のまま）。lum は有効。
            val s = if (maxC > 0.0f) delta / maxC else 0.0f
            val h = when {
                delta <= 0.0f -> 0.0f
                maxC == r -> 60.0f * (((g - b) / delta) % 6.0f + 6.0f) % 360.0f
                maxC == g -> 60.0f * (((b - r) / delta) + 2.0f)
                else -> 60.0f * (((r - g) / delta) + 4.0f)
            }
            RecipeHsl.bandWeights(h.toDouble(), weights)
            var shift = 0.0
            var sMul = 0.0
            var lMul = 0.0
            for (i in weights.indices) {
                val w = weights[i]
                shift += w * hueShiftDeg[i]
                sMul += w * satMul[i]
                lMul += w * lumGain[i]
            }
            val h2 = (h + shift.toFloat()).let { if (it >= 360.0f) it - 360.0f else if (it < 0.0f) it + 360.0f else it }
            val s2 = (s * sMul.toFloat()).coerceIn(0.0f, 1.0f)
            // 明るさは線形光ドメイン（明示: sRGB 直の v 乗算はシャドウを潰す）。
            val vLin = RecipeToneCurve.srgbToLinear(v.toDouble()) * lMul
            val v2 = RecipeToneCurve.linearToSrgb(vLin.coerceIn(0.0, 1.0e12)).coerceIn(0.0, 1.0)
            hsvToRgb(h2.toDouble(), s2.toDouble(), v2.toFloat(), out)
        }
    }

    /** lum（−1..1）→ 線形光の乗算ゲイン（2^(lum)、対称 ±1 EV 形。設計段階の選択）。 */
    fun lumGainOf(lum: Double): Double = 2.0.pow(lum)

    private fun hsvToRgb(h: Double, s: Double, v: Float, out: FloatArray) {
        if (s <= 0.0) {
            out[0] = v
            out[1] = v
            out[2] = v
            return
        }
        val hd = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val i = hd.toInt()
        val f = hd - i
        val p = v * (1.0 - s).toFloat()
        val q = v * (1.0 - s * f).toFloat()
        val t = v * (1.0 - s * (1.0 - f)).toFloat()
        when (i) {
            0 -> { out[0] = v; out[1] = t; out[2] = p }
            1 -> { out[0] = q; out[1] = v; out[2] = p }
            2 -> { out[0] = p; out[1] = v; out[2] = t }
            3 -> { out[0] = p; out[1] = q; out[2] = v }
            4 -> { out[0] = t; out[1] = p; out[2] = v }
            else -> { out[0] = v; out[1] = p; out[2] = q }
        }
    }
}
