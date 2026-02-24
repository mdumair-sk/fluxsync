package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryViewModelTest {

    @Test
    fun `refresh loads entries sorted desc and toggles loading`() = runTest {
        val repository = FakeTransferHistoryRepository(
            entries = mutableListOf(sampleEntry(id = "1", completedAtMs = 1000), sampleEntry(id = "2", completedAtMs = 3000))
        )

        val viewModel = HistoryViewModel(repository = repository, scope = backgroundScope)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("2", "1"), state.entries.map { it.id })
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `onFilterChanged narrows list by type`() = runTest {
        val repository = FakeTransferHistoryRepository(
            entries = mutableListOf(
                sampleEntry(id = "sent", direction = TransferDirection.SENT),
                sampleEntry(id = "recv", direction = TransferDirection.RECEIVED),
                sampleEntry(id = "failed", outcome = TransferOutcome.FAILED),
            )
        )
        val viewModel = HistoryViewModel(repository = repository, scope = backgroundScope)
        advanceUntilIdle()

        viewModel.onFilterChanged(HistoryFilter.SENT)
        assertEquals(listOf("sent", "failed"), viewModel.uiState.value.entries.map { it.id })

        viewModel.onFilterChanged(HistoryFilter.RECEIVED)
        assertEquals(listOf("recv"), viewModel.uiState.value.entries.map { it.id })

        viewModel.onFilterChanged(HistoryFilter.FAILED)
        assertEquals(listOf("failed"), viewModel.uiState.value.entries.map { it.id })
    }

    @Test
    fun `onEntryTapped expands and collapses selected entry`() = runTest {
        val viewModel = HistoryViewModel(
            repository = FakeTransferHistoryRepository(entries = mutableListOf(sampleEntry(id = "a"))),
            scope = backgroundScope,
        )
        advanceUntilIdle()

        viewModel.onEntryTapped("a")
        assertEquals("a", viewModel.uiState.value.expandedEntryId)

        viewModel.onEntryTapped("a")
        assertNull(viewModel.uiState.value.expandedEntryId)
    }

    @Test
    fun `onDeleteEntry removes entry and clears expanded state`() = runTest {
        val repository = FakeTransferHistoryRepository(entries = mutableListOf(sampleEntry(id = "a"), sampleEntry(id = "b")))
        val viewModel = HistoryViewModel(repository = repository, scope = backgroundScope)
        advanceUntilIdle()
        viewModel.onEntryTapped("a")

        viewModel.onDeleteEntry("a")
        advanceUntilIdle()

        assertEquals(listOf("b"), viewModel.uiState.value.entries.map { it.id })
        assertNull(viewModel.uiState.value.expandedEntryId)
    }

    @Test
    fun `onRetryFailed emits event`() = runTest {
        val entry = sampleEntry(id = "failed")
        val viewModel = HistoryViewModel(
            repository = FakeTransferHistoryRepository(entries = mutableListOf(entry)),
            scope = backgroundScope,
        )
        advanceUntilIdle()
        val deferred = async { viewModel.retryFailed.first() }

        viewModel.onRetryFailed(entry)

        assertEquals(entry, deferred.await())
    }

    @Test
    fun `onCopyDetails returns expected summary structure`() = runTest {
        val entry = sampleEntry(
            id = "copy",
            startedAtMs = 0,
            completedAtMs = 90_000,
            totalSizeBytes = 3 * 1024 * 1024,
            averageSpeedBytesPerSec = 2 * 1024 * 1024,
            channelsUsed = listOf(ChannelType.WIFI, ChannelType.USB_ADB),
        )
        val viewModel = HistoryViewModel(
            repository = FakeTransferHistoryRepository(entries = mutableListOf(entry)),
            scope = backgroundScope,
        )
        advanceUntilIdle()

        val details = viewModel.onCopyDetails(entry)

        assertTrue(details.contains("FluxSync Transfer — 90000"))
        assertTrue(details.contains("Direction: Sent"))
        assertTrue(details.contains("Files: 1 · 3.0 MB · COMPLETED"))
        assertTrue(details.contains("Duration: 1m 30s · Peak: 2.00 MB/s"))
        assertTrue(details.contains("Channels: Wi-Fi 50% + USB 50%"))
    }

    private fun sampleEntry(
        id: String,
        direction: TransferDirection = TransferDirection.SENT,
        outcome: TransferOutcome = TransferOutcome.COMPLETED,
        startedAtMs: Long = 1_000,
        completedAtMs: Long = 2_000,
        totalSizeBytes: Long = 1024,
        averageSpeedBytesPerSec: Long = 1024,
        channelsUsed: List<ChannelType> = listOf(ChannelType.WIFI),
    ): TransferHistoryEntry = TransferHistoryEntry(
        id = id,
        sessionId = 1L,
        direction = direction,
        peerDeviceName = "Desktop",
        peerCertFingerprint = "AB:CD",
        files = listOf(HistoryFileEntry(name = "file.txt", sizeBytes = totalSizeBytes, outcome = outcome)),
        totalSizeBytes = totalSizeBytes,
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
        outcome = outcome,
        averageSpeedBytesPerSec = averageSpeedBytesPerSec,
        channelsUsed = channelsUsed,
        failedFileCount = if (outcome == TransferOutcome.FAILED) 1 else 0,
    )

    private class FakeTransferHistoryRepository(
        private val entries: MutableList<TransferHistoryEntry>,
    ) : TransferHistoryRepository {
        override suspend fun insert(entry: TransferHistoryEntry) {
            entries.add(entry)
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
