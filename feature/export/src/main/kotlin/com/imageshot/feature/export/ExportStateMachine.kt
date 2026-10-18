package com.imageshot.feature.export

import com.imageshot.core.model.ExportJob
import com.imageshot.core.model.ExportJob.State

/**
 * §8.2 の2段階コミット状態機械の合法性と分類。純Kotlin（JVM単体テスト対象）。
 *
 * QUEUED -> PREPARING -> RENDERING -> ENCODING -> VERIFYING -> PUBLISHING -> COMPLETE
 *   正常系は順方向の1段のみ。失敗系（FAILED_RETRYABLE / FAILED_PERMANENT / CANCELLED）は
 *   いずれのアクティブ状態からも到達可。FAILED_RETRYABLE からの再試行は QUEUED へ戻す
 *   （attempt を +1 し、staging・未確定出力物を破棄した上でフル再実行）。
 */
object ExportStateMachine {

    /** 順序通りに並んだ正常系のシーケンス。 */
    val FORWARD_ORDER: List<State> = listOf(
        State.QUEUED,
        State.PREPARING,
        State.RENDERING,
        State.ENCODING,
        State.VERIFYING,
        State.PUBLISHING,
        State.COMPLETE,
    )

    /** 中断スキャン（§8.2, ADR-010）対象の「作業中」状態。 */
    fun isBusyState(state: State): Boolean = when (state) {
        State.PREPARING, State.RENDERING, State.ENCODING, State.VERIFYING, State.PUBLISHING -> true
        else -> false
    }

    /** 復旧不可の終端状態か。 */
    fun isTerminalState(state: State): Boolean = when (state) {
        State.COMPLETE, State.FAILED_PERMANENT, State.CANCELLED -> true
        else -> false
    }

    /**
     * 隣接遷移の合法性（呼び出し側での契約不完全検出用；DAO直書を抑止する）。
     * 終端状態（COMPLETE / FAILED_PERMANENT / CANCELLED）からの出遷移は一切禁止
     * （再開は「再試行」として QUEUED 経由でのみ。KDoc の記述に合致させる遷移制限の修正）。
     */
    fun canTransition(from: State, to: State): Boolean {
        if (from == to) return false
        if (isTerminalState(from)) return false
        if (to == State.FAILED_RETRYABLE || to == State.FAILED_PERMANENT || to == State.CANCELLED) {
            return true
        }
        if (to == State.QUEUED) return false // QUEUED のみエントリ点。飛び戻りは禁止。
        val fromIdx = FORWARD_ORDER.indexOf(from)
        val toIdx = FORWARD_ORDER.indexOf(to)
        return fromIdx >= 0 && toIdx > fromIdx && toIdx - fromIdx == 1
    }

    /** FAILED_RETRYABLE から再試行の受け入れ可否。 */
    fun canRetry(state: State): Boolean =
        state == State.QUEUED || state == State.FAILED_RETRYABLE

    /** 再試行でリセットする状態。失敗からは QUEUED へ戻す（フル再実行; 部分物の再利用はしない）。 */
    fun resetStateForRetry(state: State): State? = when (state) {
        State.QUEUED, State.FAILED_RETRYABLE -> State.QUEUED
        else -> null
    }
}
