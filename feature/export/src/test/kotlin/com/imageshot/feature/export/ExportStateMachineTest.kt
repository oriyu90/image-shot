package com.imageshot.feature.export

import com.imageshot.core.model.ExportJob.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.2 二段階コミット状態機械の合法性テスト（純Kotlin）。
 */
class ExportStateMachineTest {

    @Test
    fun forwardChain_isAdjacentLegalSequence() {
        val order = ExportStateMachine.FORWARD_ORDER
        for (i in 0 until order.size - 1) {
            assertTrue(
                "adjacent ${order[i]} -> ${order[i + 1]} must be legal",
                ExportStateMachine.canTransition(order[i], order[i + 1]),
            )
        }
    }

    @Test
    fun skippedForwardTransitions_areIllegal() {
        val order = ExportStateMachine.FORWARD_ORDER
        for (i in 0 until order.size - 1) {
            for (j in i + 2 until order.size) {
                assertFalse(
                    "${order[i]} -> ${order[j]} (skip) must be illegal",
                    ExportStateMachine.canTransition(order[i], order[j]),
                )
            }
        }
    }

    @Test
    fun backwardTransitions_areIllegal() {
        val order = ExportStateMachine.FORWARD_ORDER
        for (i in order.indices) {
            for (j in 0 until i) {
                assertFalse(
                    "${order[i]} -> ${order[j]} (backward) must be illegal",
                    ExportStateMachine.canTransition(order[i], order[j]),
                )
            }
        }
    }

    @Test
    fun sameStateTransition_isIllegal() {
        State.entries.forEach { state ->
            assertFalse(ExportStateMachine.canTransition(state, state))
        }
    }

    @Test
    fun failureStates_areReachableFromAllActiveStates() {
        val failureStates = listOf(State.FAILED_RETRYABLE, State.FAILED_PERMANENT, State.CANCELLED)
        val activeStates = ExportStateMachine.FORWARD_ORDER.filter { it != State.COMPLETE }
        for (from in activeStates) {
            for (to in failureStates) {
                assertTrue("$from -> $to must be reachable", ExportStateMachine.canTransition(from, to))
            }
        }
    }

    @Test
    fun terminalStates_acceptNoOutgoingTransitions() {
        val terminals = listOf(State.COMPLETE, State.FAILED_PERMANENT, State.CANCELLED)
        for (from in terminals) {
            State.entries.forEach { to ->
                assertFalse("$from -> $to must be illegal", ExportStateMachine.canTransition(from, to))
            }
        }
        terminals.forEach { assertTrue(ExportStateMachine.isTerminalState(it)) }
    }

    @Test
    fun failedRetryable_canBeRetriedWithoutDirectQueuedTransition() {
        // 遷移図上は FAILED_RETRYABLE → QUEUED の直接遷移は禁止（再試行は実装側で
        // attempt を +1 し QUEUED からフル再実行する）。
        assertFalse(ExportStateMachine.canTransition(State.FAILED_RETRYABLE, State.QUEUED))
        assertTrue(ExportStateMachine.canRetry(State.FAILED_RETRYABLE))
        assertTrue(ExportStateMachine.canRetry(State.QUEUED))
        assertFalse(ExportStateMachine.canRetry(State.RENDERING))
        assertFalse(ExportStateMachine.canRetry(State.FAILED_PERMANENT))
    }

    @Test
    fun resetStateForRetry_acceptsOnlyQueuedAndFailedRetryable() {
        assertEquals(State.QUEUED, ExportStateMachine.resetStateForRetry(State.QUEUED))
        assertEquals(State.QUEUED, ExportStateMachine.resetStateForRetry(State.FAILED_RETRYABLE))
        assertNull(ExportStateMachine.resetStateForRetry(State.PUBLISHING))
        assertNull(ExportStateMachine.resetStateForRetry(State.COMPLETE))
        assertNull(ExportStateMachine.resetStateForRetry(State.FAILED_PERMANENT))
    }

    @Test
    fun isBusyState_coversPauseableProcessingStages() {
        val busy = listOf(State.PREPARING, State.RENDERING, State.ENCODING, State.VERIFYING, State.PUBLISHING)
        busy.forEach { assertTrue("$it must be busy", ExportStateMachine.isBusyState(it)) }
        State.entries.filter { it !in busy }.forEach {
            assertFalse("$it must not be busy", ExportStateMachine.isBusyState(it))
        }
    }
}
