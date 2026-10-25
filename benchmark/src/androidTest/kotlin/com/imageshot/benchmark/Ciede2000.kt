package com.imageshot.benchmark

/**
 * CIEDE2000（CIE ΔE2000）の純 Kotlin 実装（PR-18・androidTest 専用）。
 *
 * 【計画段階の実装】（Wave3 計画書 PR-18 §3 の補助指標）
 * - 本実装は ΔE2000 の計算器としてのみ存在し、この時点では閾値の契約を持たない。
 * - golden ハーネスからの assert も行わない（主指標は引き続き CIE ΔE76・契約連続性）。
 * - ΔE2000 の実測統計は golden_dE_report.json と logcat に記録され、将来の実行（PR-18 の
 *   5 連続実行の分布記録）で測定価値が確定した時点で nfr_catalog への採用/閾値確定を
 *   判断する材料になる。採否が確定するまでは品質契約ではない。
 *
 * 入力は golden ハーネスの labOf() と同一の CIE Lab（D65・[L, a, b]）。sRGB ピクセルから
 * Lab への変換は持たず、既存 ΔE76 計量と同一の変換経路の結果をそのまま受ける。
 * 定式化は Sharma, Wu & Dalal (2005) の実装注記（色相平均の 3 分岐・hue リセット規則を
 * 含む手順）に従い、評価パラメータは CIE 既定の KL=KC=KH=1 に固定する。
 *
 * 純 Kotlin（android.* に非依存・JVM テストからも実行可能）であり、全画素集計の
 * パフォーマンス上、配列を確保しないスカラー版を本体とする。
 *
 * 検証（2026-10-25 実施）: 本ファイルを JVM 側で単独コンパイルし、Sharma らの公開
 * test data 34 ペア（各値は小数点第4位表記）すべてとの一致と、引数入替えの対称性
 * （非対称 0/34）を確認済み。Android 端末での実行は未実施であり、端末の超越関数実装差
 * （sin/cos/atan2/pow の最下位桁差）が小数点第4位の丸めに影響する可能性は排除しない。
 * 記録用途（assert なし）を前提とするため、その範囲の差は契約判断には使わない。
 */
internal object Ciede2000 {

    /**
     * 既存ハーネスの labOf() 出力（FloatArray(3)=[L, a, b]）をそのまま入力に取るオーバーロード。
     * Float への丸めによる微小な精度損失があるが、全画素集計は既存 ΔE76 と同型の
     * FloatArray+sort 手順（nearest-rank 百分位）に合わせるための形状である。
     */
    fun deltaE2000(lab1: FloatArray, lab2: FloatArray): Float {
        require(lab1.size >= 3 && lab2.size >= 3) {
            "Lab 配列は [L, a, b] を期待: lab1.size=${lab1.size}, lab2.size=${lab2.size}"
        }
        return deltaE2000(
            lab1[0].toDouble(), lab1[1].toDouble(), lab1[2].toDouble(),
            lab2[0].toDouble(), lab2[1].toDouble(), lab2[2].toDouble(),
        ).toFloat()
    }

    /**
     * CIEDE2000 の本体（スカラー版・Double 計算・KL=KC=KH=1）。
     * L*, a*, b* は CIE Lab D65 の 3 成分を直接受ける。
     */
    fun deltaE2000(
        l1: Double,
        a1: Double,
        b1: Double,
        l2: Double,
        a2: Double,
        b2: Double,
    ): Double {
        val c1 = Math.sqrt(a1 * a1 + b1 * b1)
        val c2 = Math.sqrt(a2 * a2 + b2 * b2)
        val cBar = (c1 + c2) * 0.5
        val g = 0.5 * (1.0 - Math.sqrt(Math.pow(cBar, 7.0) / (Math.pow(cBar, 7.0) + C_25_POW_7)))

        val a1p = (1.0 + g) * a1
        val a2p = (1.0 + g) * a2
        val c1p = Math.sqrt(a1p * a1p + b1 * b1)
        val c2p = Math.sqrt(a2p * a2p + b2 * b2)

        val h1p = hueDegree(a1p, b1)
        val h2p = hueDegree(a2p, b2)

        val deltaL = l2 - l1
        val deltaC = c2p - c1p
        val prodC = c1p * c2p

        // ΔH' = 2·sqrt(C1'·C2')·sin(Δh'/2)（±180° 超の hue 差は wrap 後の Δh' に畳み、符号は sin が担う）。
        // prodC=0 の場合 hue 差は未定義のため 0。
        val deltaH =
            if (prodC == 0.0) 0.0
            else {
                val deltaHp = wrapAround180(h2p - h1p)
                2.0 * Math.sqrt(prodC) * Math.sin(Math.toRadians(deltaHp / 2.0))
            }

        val lBar = (l1 + l2) * 0.5
        val cBarP = (c1p + c2p) * 0.5
        val hBar = meanHue(h1p, h2p, prodC)

        val t = 1.0 - 0.17 * Math.cos(Math.toRadians(hBar - 30.0)) +
            0.24 * Math.cos(Math.toRadians(2.0 * hBar)) +
            0.32 * Math.cos(Math.toRadians(3.0 * hBar + 6.0)) -
            0.20 * Math.cos(Math.toRadians(4.0 * hBar - 63.0))
        val deltaTheta = 30.0 * Math.exp(-((hBar - 275.0) / 25.0) * ((hBar - 275.0) / 25.0))
        val rc = 2.0 * Math.sqrt(Math.pow(cBarP, 7.0) / (Math.pow(cBarP, 7.0) + C_25_POW_7))
        val lBarMinus50 = lBar - 50.0
        val sl = 1.0 + 0.015 * lBarMinus50 * lBarMinus50 /
            Math.sqrt(20.0 + lBarMinus50 * lBarMinus50)
        val sc = 1.0 + 0.045 * cBarP
        val sh = 1.0 + 0.015 * cBarP * t
        val rt = -Math.sin(Math.toRadians(2.0 * deltaTheta)) * rc

        val dlTerm = deltaL / (KL * sl)
        val dcTerm = deltaC / (KC * sc)
        val dhTerm = deltaH / (KH * sh)
        return Math.sqrt(dlTerm * dlTerm + dcTerm * dcTerm + dhTerm * dhTerm + rt * dcTerm * dhTerm)
    }

    /** C1'·C2' == 0 の場合、hue は未定義であるため h1'+h2' をそのまま返す（hue リセット規則）。 */
    private fun meanHue(h1p: Double, h2p: Double, prodC: Double): Double {
        if (prodC == 0.0) return h1p + h2p
        val sum = h1p + h2p
        val diff = h2p - h1p
        return when {
            Math.abs(diff) <= 180.0 -> sum / 2.0
            sum < 360.0 -> (sum + 360.0) / 2.0
            else -> (sum - 360.0) / 2.0
        }
    }

    /** hue 関数（角度）。a'=0 かつ b=0 では 0（undefined hue の規則）。返り値は [0, 360)。 */
    private fun hueDegree(a: Double, b: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        var deg = Math.toDegrees(Math.atan2(b, a))
        if (deg < 0.0) deg += 360.0
        return deg
    }

    /**
     * h1'/h2' の差分を [-180, 180] へ畳む（Sharma 2005 eq.10 相当）。
     * 両角は [0, 360) 正規化済みのため入力範囲は (-360, 360) であり、|diff|<=180 は
     * そのまま、>180 / <=-180 は ±360 の加減で畳む。引数入替えでは符号反転となり、
     * 符号は後続の sin(Δh'/2) で反映される（検証済みの対称性）。
     */
    private fun wrapAround180(diff: Double): Double {
        var d = diff
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    private const val KL = 1.0
    private const val KC = 1.0
    private const val KH = 1.0

    /** CIEDE2000 定数の 25^7 = 6,103,515,625（定義の正確な整数値・べき乗を毎回計算しない）。 */
    private const val C_25_POW_7 = 6_103_515_625.0
}
