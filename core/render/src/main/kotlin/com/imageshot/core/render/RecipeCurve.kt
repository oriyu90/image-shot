package com.imageshot.core.render

import com.imageshot.core.model.EditNodeSpecs
import kotlin.math.abs

/**
 * トーンカーブ（全体/master カーブ）の区間評価正本。
 *
 * 方式（設計段階・明示）:
 * - 制御点間は単調3次エルミート補間（Fritsch–Carlson を簡略化した区間単調クランプ）。
 *   各節点の接線 m は区間割合 Δ の平均を `|m| ≤ 3·min(|Δ前|, |Δ次|)` にクランプした値。
 *   この条件は区間ごとの単調性を保证する十分条件（データ単調 ⇒ 曲線単調）。
 * - 端点外は端点値にクランプ。出力 y は 0..1 にクランプ。
 * - master カーブのみ実装。R/G/B チャンネル別カーブは M1 対応外（適用しない・警告対象）。
 * - 画質（色再現）の定量評価は未実施（golden 記述子なし）。式の性質のみテストする。
 *
 * 適用位置（RecipeToneCurve / RecipeDevelopApplier のパイプライン順）:
 *   スライダー LUT（露出→…→コントラスト、sRGB 表示値へ復号済み）→ master カーブ（本クラス）→
 *   彩度/自然な彩度（ColorMixer）→ HSL（バンド）。
 */
object RecipeCurve {

    /** 制御点を正規化する（[EditNodeSpecs.normalizeCurve] への委譲、render 側の唯一の入口）。 */
    fun normalized(points: List<List<Double>>): List<List<Double>>? =
        EditNodeSpecs.normalizeCurve(points)

    /** 中立（恒等）カーブか。未正規化入力も受理する。 */
    fun isNeutral(points: List<List<Double>>): Boolean =
        EditNodeSpecs.isNeutralCurve(points)

    /**
     * カーブ 1点評価。`points` は正規化済み（昇順・2要素）である前提。
     * x ≤ 先頭 x は先頭 y、x ≥ 末尾 x は末尾 y。
     */
    fun evaluate(points: List<List<Double>>, x: Double): Double {
        val n = points.size
        val first = points[0]
        val last = points[n - 1]
        if (x <= first[0]) return first[1]
        if (x >= last[0]) return last[1]

        // 区間検索（制御点数 ≤ 16 の線形走査で十分）。
        var i = 0
        while (i < n - 1 && points[i + 1][0] <= x) i++
        val x0 = points[i][0]
        val y0 = points[i][1]
        val x1 = points[i + 1][0]
        val y1 = points[i + 1][1]
        val h = x1 - x0
        if (h <= 0.0) return y1
        val d0 = if (i == 0) (y1 - y0) / h else (points[i][1] - points[i - 1][1]) / (x0 - points[i - 1][0])
        val d1 = if (i == n - 2) {
            (y1 - y0) / h
        } else {
            (points[i + 2][1] - y1) / (points[i + 2][0] - x1)
        }
        val m0 = tangentAt(points, i, d0)
        val m1 = tangentAt(points, i + 1, d1)

        // 単調3次エルミート（バーンスタイン基底）。
        val t = (x - x0) / h
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2.0 * t3 - 3.0 * t2 + 1.0
        val h10 = t3 - 2.0 * t2 + t
        val h01 = -2.0 * t3 + 3.0 * t2
        val h11 = t3 - t2
        val y = h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
        return y.coerceIn(0.0, 1.0)
    }

    /**
     * 節点 idx の接線。Fritsch–Carlson 簡略形:
     * - 両側 Δ が同符号: 平均 Δ を `3·min(|Δ前|,|Δ次|)` にクランプ。
     * - 符号不一致 / どちらかが 0: 0（平坦継続）。
     * - 端点: 隣接区間の Δ をそのまま使用（0 ≤ m ≤ 3Δ を満たす）。
     */
    private fun tangentAt(points: List<List<Double>>, idx: Int, endpointDelta: Double): Double {
        val n = points.size
        if (idx == 0 || idx == n - 1) return endpointDelta
        val dm1 = (points[idx][1] - points[idx - 1][1]) / (points[idx][0] - points[idx - 1][0])
        val dp1 = (points[idx + 1][1] - points[idx][1]) / (points[idx + 1][0] - points[idx][0])
        if (dm1 * dp1 <= 0.0) return 0.0
        val avg = (dm1 + dp1) / 2.0
        val bound = 3.0 * minOf(abs(dm1), abs(dp1))
        return if (abs(avg) <= bound) avg else bound * (if (avg > 0.0) 1.0 else -1.0)
    }
}
