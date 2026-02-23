package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChunkPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransferViewModelTest {
    @Test
    fun fileProgressAndOutcomesReflectInUiState() = runTest {
        val drtlb = DRTLB(chunkSource = Channel<ChunkPacket>())
        val machine = SessionStateMachine(
            sessionId = 1L,
            scope = backgroundScope,
            onCancel = {},
            onComplete = {},
        )
        val viewModel = TransferViewModel(drtlb, machine, backgroundScope)

        val file = createTempFile(prefix = "fluxsync-transfer-viewmodel", suffix = ".bin")
        file.writeBytes(ByteArray(1024))

        viewModel.onFilesDropped(listOf(file))
        advanceUntilIdle()

        viewModel.updateFileProgress(fileId = 0, bytesWritten = 512, totalBytes = 1024)
        advanceUntilIdle()

        val progressEntry = viewModel.uiState.value.fileEntries.single()
        assertEquals(0.5f, progressEntry.progressFraction)
        assertNull(progressEntry.outcome)

        viewModel.markFileFailed(fileId = 0)
        advanceUntilIdle()

        assertEquals(FileOutcome.PARTIAL, viewModel.uiState.value.fileEntries.single().outcome)
        assertEquals(0.5f, viewModel.uiState.value.overallProgressFraction)

        file.delete()
    }

    @Test
    fun pendingConsentCountdownTicksAndResetsOnStateChange() = runTest {
        val drtlb = DRTLB(chunkSource = Channel<ChunkPacket>())
        val machine = SessionStateMachine(
            sessionId = 2L,
            scope = backgroundScope,
            onCancel = {},
            onComplete = {},
        )
        val viewModel = TransferViewModel(drtlb, machine, backgroundScope)

        machine.transition(SessionState.CONNECTING)
        machine.transition(SessionState.HANDSHAKING)
        machine.transition(SessionState.PENDING_CONSENT)

        advanceTimeBy(1_300)
        assertTrue(viewModel.uiState.value.pendingConsentTimeoutSeconds <= 59)

        machine.transition(SessionState.TRANSFERRING)
        advanceTimeBy(300)
        assertEquals(60, viewModel.uiState.value.pendingConsentTimeoutSeconds)
    }
}
