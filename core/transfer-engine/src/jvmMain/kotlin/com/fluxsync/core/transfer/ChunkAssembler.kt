package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.CRC32Helper
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.resumability.FluxPartDebouncer
import com.fluxsync.core.resumability.FluxPartState
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class WriteResult {
    data object Success : WriteResult()
    data object AlreadyComplete : WriteResult()
    data class ChecksumFailure(val chunkIndex: Int) : WriteResult()
}

/**
 * Receiver-side chunk writer.
 *
 * Callers must pre-allocate [targetFile] to its full size before starting assembly.
 */
class ChunkAssembler(
    private val targetFile: RandomAccessFile,
    private val totalChunks: Int,
    private val resumeState: FluxPartState?,
    private val fluxPartDebouncer: FluxPartDebouncer,
    private val onRetryRequired: (List<Int>) -> Unit,
    private val onFileComplete: () -> Unit,
    private val onFileFailed: (String) -> Unit,
) {
    private val completionBitmap = AtomicIntegerArray(totalChunks)
    private val failedChunks = ConcurrentHashMap.newKeySet<Int>()
    private val writeMutex = Mutex()
    private val completedCount = AtomicInteger(0)
    private val retryRounds = AtomicInteger(0)
    private val completionNotified = AtomicBoolean(false)
    private val failureNotified = AtomicBoolean(false)

    @Volatile
    private var stateSessionId: Long = resumeState?.sessionId ?: -1L

    @Volatile
    private var stateFileId: Int = resumeState?.fileId ?: -1

    @Volatile
    private var stateChunkSizeBytes: Int = resumeState?.chunkSizeBytes ?: 0

    @Volatile
    private var stateExpectedSizeBytes: Long = resumeState?.expectedSizeBytes ?: 0L

    @Volatile
    private var stateFirstChunkCRC32: Int = resumeState?.firstChunkCRC32 ?: 0

    @Volatile
    private var stateLastModifiedMs: Long = resumeState?.lastModifiedMs ?: 0L

    private val stateCreatedAtMs: Long = resumeState?.createdAtMs ?: System.currentTimeMillis()

    init {
        require(totalChunks >= 0) { "totalChunks must be non-negative" }

        resumeState?.completedChunks?.forEach { chunkIndex ->
            if (chunkIndex in 0 until totalChunks && completionBitmap.get(chunkIndex) == 0) {
                completionBitmap.set(chunkIndex, 1)
                completedCount.incrementAndGet()
            }
        }
    }

    suspend fun writeChunk(chunk: ChunkPacket): WriteResult {
        validateChunkIndex(chunk.chunkIndex)
        updateStateMetadata(chunk)

        if (completionBitmap.get(chunk.chunkIndex) == 1) {
            return WriteResult.AlreadyComplete
        }

        if (CRC32Helper.compute(chunk.payload) != chunk.checksum) {
            failedChunks.add(chunk.chunkIndex)
            requestRetryOrFail()
            return WriteResult.ChecksumFailure(chunk.chunkIndex)
        }

        writeMutex.withLock {
            targetFile.seek(chunk.offset)
            targetFile.write(chunk.payload)
        }

        completionBitmap.set(chunk.chunkIndex, 1)
        completedCount.incrementAndGet()
        failedChunks.remove(chunk.chunkIndex)

        val state = currentState()
        fluxPartDebouncer.scheduleWrite(state)
        checkCompletion(state)

        return WriteResult.Success
    }

    fun getFailedChunks(): Set<Int> = failedChunks.toSet()

    val isComplete: Boolean
        get() = completedCount.get() == totalChunks

    private fun validateChunkIndex(chunkIndex: Int) {
        require(chunkIndex in 0 until totalChunks) {
            "chunkIndex $chunkIndex out of bounds for totalChunks=$totalChunks"
        }
    }

    private fun updateStateMetadata(chunk: ChunkPacket) {
        if (stateSessionId < 0) stateSessionId = chunk.sessionId
        if (stateFileId < 0) stateFileId = chunk.fileId
        if (stateChunkSizeBytes == 0 || chunk.payloadLength > stateChunkSizeBytes) {
            stateChunkSizeBytes = chunk.payloadLength
        }
        val chunkEndOffset = chunk.offset + chunk.payloadLength
        if (chunkEndOffset > stateExpectedSizeBytes) {
            stateExpectedSizeBytes = chunkEndOffset
        }
        if (chunk.chunkIndex == 0) {
            stateFirstChunkCRC32 = chunk.checksum
        }
    }

    private fun requestRetryOrFail() {
        val currentRound = retryRounds.incrementAndGet()
        if (currentRound >= MAX_RETRY_ROUNDS) {
            if (failureNotified.compareAndSet(false, true)) {
                onFileFailed(
                    "Exceeded max retry rounds ($MAX_RETRY_ROUNDS). failedChunks=${failedChunks.sorted()}"
                )
            }
            return
        }

        onRetryRequired(failedChunks.sorted())
    }

    private suspend fun checkCompletion(state: FluxPartState) {
        if (!isComplete) return
        if (!completionNotified.compareAndSet(false, true)) return

        fluxPartDebouncer.flush(state)
        onFileComplete()
    }

    private fun currentState(): FluxPartState {
        val completedChunks = mutableSetOf<Int>()
        for (chunkIndex in 0 until totalChunks) {
            if (completionBitmap.get(chunkIndex) == 1) {
                completedChunks += chunkIndex
            }
        }

        return FluxPartState(
            sessionId = stateSessionId.coerceAtLeast(0L),
            fileId = stateFileId.coerceAtLeast(0),
            totalChunks = totalChunks,
            chunkSizeBytes = stateChunkSizeBytes,
            expectedSizeBytes = stateExpectedSizeBytes,
            lastModifiedMs = stateLastModifiedMs,
            firstChunkCRC32 = stateFirstChunkCRC32,
            createdAtMs = stateCreatedAtMs,
            completedChunks = completedChunks,
        )
    }

    private companion object {
        private const val MAX_RETRY_ROUNDS = 3
    }
}
