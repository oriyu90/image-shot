package com.imageshot.feature.library

import com.imageshot.core.model.Asset
import com.imageshot.core.storage.UriAccessibility

/**
 * G2（PR-30）: URI 可用性検査の「対象決定」と「DB 反映判断」だけを持つ純粋方針。
 *
 * 検査の起動点は 2 つあるが、対象の決め方と書き戻しの判断はここへ集約する:
 * 1. **表示時検査**（一覧の表示・再表示時）: `availability == ok` の資産のみを対象にする。
 *    失効/切断済みは再関連付け導線で扱い、表示のたびに全件を走査しない（バッテリー配慮）。
 * 2. **明示的な全走査**（「可用性確認」ボタン）: 全資産を対象にし、回復（ok へ戻る）も反映する。
 *
 * どちらの経路も [transitions] を通して「実際に変化した資産だけ」を更新対象にする
 * （同値の書き戻しをしない＝無駄な DB 書き込みと Room 購読通知を避ける）。
 *
 * ContentResolver には触れない（openInputStream の実行は呼び出し側）ため JVM 単体テストで
 * 方針そのものを検証できる。`Asset`/`UriAccessibility` は値型のみの参照。
 */
internal object AvailabilitySweep {

    /** 検査対象の最小表現（AssetEntity / Asset 全体を持ち回らない）。 */
    data class Entry(val id: String, val sourceUri: String, val availability: String)

    /** 対象と除外。表示時検査では失効/切断済みの資産が [skipped] に入る。 */
    data class Plan(val targets: List<Entry>, val skipped: List<Entry>)

    /** DB に反映する 1 件の状態遷移（変化なしは含めない）。 */
    data class Transition(val id: String, val from: String, val to: String)

    fun entryOf(asset: Asset): Entry = Entry(asset.id, asset.sourceUri, asset.availability)

    /**
     * 検査対象を決める。
     * @param includeUnavailable true で全件（明示ボタン経路）、false で availability==ok のみ
     *   （表示時経路。失効/切断済みは明示操作まで走査しない）。
     */
    fun plan(entries: List<Entry>, includeUnavailable: Boolean): Plan {
        val targets = ArrayList<Entry>(entries.size)
        val skipped = ArrayList<Entry>(0)
        for (entry in entries) {
            if (includeUnavailable || entry.availability == Asset.AVAILABILITY_OK) {
                targets += entry
            } else {
                skipped += entry
            }
        }
        return Plan(targets, skipped)
    }

    /** provider の観測結果 → DB の availability 文字列（core:model の定数をそのまま使う）。 */
    fun toAvailability(probe: UriAccessibility): String = when (probe) {
        UriAccessibility.OK -> Asset.AVAILABILITY_OK
        UriAccessibility.REVOKED -> Asset.AVAILABILITY_REVOKED
        UriAccessibility.DISCONNECTED -> Asset.AVAILABILITY_DISCONNECTED
    }

    /** 変化した資産だけを返す（変化なしは null で落とす）。 */
    fun transitions(results: List<Pair<Entry, UriAccessibility>>): List<Transition> =
        results.mapNotNull { (entry, probe) ->
            val next = toAvailability(probe)
            if (next == entry.availability) {
                null
            } else {
                Transition(id = entry.id, from = entry.availability, to = next)
            }
        }
}
