package com.fluxsync.core.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStateMachineTest {
    @Test
    fun watchdogCancelsSessionAfterTenSecondsWithoutHeartbeat() = runTest {
        var cancelReason: String? = null

        val machine = SessionStateMachine(
            sessionId = 7L,
            scope = this,
            onCancel = { reason -> cancelReason = reason },
            onComplete = {},
        )

        machine.transition(SessionState.CONNECTING)
        machine.transition(SessionState.HANDSHAKING)
        machine.onStateAdvancingPacketReceived()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(null, cancelReason)
        assertEquals(SessionState.HANDSHAKING, machine.stateFlow.value)

        advanceTimeBy(1)
        runCurrent()

        assertEquals("Heartbeat timeout", cancelReason)
        assertEquals(SessionState.CANCELLED, machine.stateFlow.value)
    }

    @Test
    fun invalidTransitionThrowsIllegalStateException() {
        val testScope = TestScope(StandardTestDispatcher())
        val machine = SessionStateMachine(
            sessionId = 15L,
            scope = testScope,
            onCancel = {},
            onComplete = {},
        )

        val error = assertFailsWith<IllegalStateException> {
            machine.transition(SessionState.COMPLETED)
        }

        assertTrue(error.message?.contains("Invalid session state transition") == true)
    }

    @Test
    fun heartbeatResetsWhenStateAdvancingPacketArrives() = runTest {
        var cancelCount = 0

        val machine = SessionStateMachine(
            sessionId = 42L,
            scope = this,
            onCancel = { cancelCount += 1 },
            onComplete = {},
        )

        machine.transition(SessionState.CONNECTING)
        machine.transition(SessionState.HANDSHAKING)
        machine.onStateAdvancingPacketReceived()

        advanceTimeBy(5_000)
        machine.onStateAdvancingPacketReceived()
        advanceTimeBy(9_999)
        runCurrent()

        assertEquals(0, cancelCount)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(1, cancelCount)
    }
}
