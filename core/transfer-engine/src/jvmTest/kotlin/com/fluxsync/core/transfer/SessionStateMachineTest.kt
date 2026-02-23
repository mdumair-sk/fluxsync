package com.fluxsync.core.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    @Test
    fun completionAndCancellationPersistTransferHistory() = runTest {
        val repository = InMemoryTransferHistoryRepository()
        val manager = TransferHistoryManager(repository = repository, nowMs = { 1_700_000_000_000L })

        val snapshot = TransferHistorySessionSnapshot(
            sessionId = 99L,
            direction = TransferDirection.SENT,
            peerDeviceName = "Receiver",
            peerCertFingerprint = "AA:BB",
            files = listOf(HistoryFileEntry(name = "demo.bin", sizeBytes = 42L, outcome = TransferOutcome.COMPLETED)),
            totalSizeBytes = 42L,
            startedAtMs = 1_699_999_999_000L,
            averageSpeedBytesPerSec = 2048L,
            channelsUsed = emptyList(),
            failedFileCount = 0,
        )

        val completeMachine = SessionStateMachine(
            sessionId = 99L,
            scope = this,
            onCancel = {},
            onComplete = {},
            transferHistoryManager = manager,
            transferHistorySnapshotProvider = { snapshot },
        )

        completeMachine.complete()

        val completedEntry = assertNotNull(repository.getById(repository.getAll().single().id))
        assertEquals(TransferOutcome.COMPLETED, completedEntry.outcome)

        val cancelMachine = SessionStateMachine(
            sessionId = 100L,
            scope = this,
            onCancel = {},
            onComplete = {},
            transferHistoryManager = manager,
            transferHistorySnapshotProvider = { snapshot.copy(sessionId = 100L) },
        )

        cancelMachine.cancel("test cancel")

        val all = repository.getAll()
        assertEquals(2, all.size)
        assertEquals(TransferOutcome.CANCELLED, all.last().outcome)
    }

    private class InMemoryTransferHistoryRepository : TransferHistoryRepository {
        private val entries = mutableListOf<TransferHistoryEntry>()

        override suspend fun insert(entry: TransferHistoryEntry) {
            entries += entry
        }

        override suspend fun getAll(): List<TransferHistoryEntry> = entries.toList()

        override suspend fun getFiltered(direction: TransferDirection): List<TransferHistoryEntry> {
            return entries.filter { it.direction == direction }
        }

        override suspend fun delete(id: String) {
            entries.removeAll { it.id == id }
        }

        override suspend fun getById(id: String): TransferHistoryEntry? = entries.firstOrNull { it.id == id }
    }
}
