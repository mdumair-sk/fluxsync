package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.CRC32Helper
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.resumability.FluxPartDebouncer
import com.fluxsync.core.resumability.FluxPartState
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChunkAssemblerTest {

    @Test
    fun `writeChunk skips already completed chunk from resume state`() = runTest {
        val file = Files.createTempFile("chunk-assembler", ".bin").toFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(8)

            val assembler = assembler(
                targetFile = raf,
                totalChunks = 2,
                resumeState = resumeState(completedChunks = setOf(0), totalChunks = 2),
            )

            val payload = byteArrayOf(1, 2, 3, 4)
            val result = assembler.writeChunk(packet(chunkIndex = 0, offset = 0L, payload = payload))

            assertEquals(WriteResult.AlreadyComplete, result)
            assertFalse(assembler.isComplete)
        }
    }

    @Test
    fun `writeChunk rejects invalid checksum and requests retry`() = runTest {
        val file = Files.createTempFile("chunk-assembler", ".bin").toFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(4)

            val retryCount = AtomicInteger(0)
            val assembler = assembler(
                targetFile = raf,
                totalChunks = 1,
                onRetryRequired = {
                    retryCount.incrementAndGet()
                    assertEquals(listOf(0), it)
                },
            )

            val payload = byteArrayOf(9, 8, 7, 6)
            val badPacket = packet(chunkIndex = 0, offset = 0L, payload = payload, checksum = 123)

            val result = assembler.writeChunk(badPacket)

            assertIs<WriteResult.ChecksumFailure>(result)
            assertEquals(setOf(0), assembler.getFailedChunks())
            assertEquals(1, retryCount.get())
        }
    }

    @Test
    fun `writeChunk writes payload marks complete and notifies completion`() = runTest {
        val file = Files.createTempFile("chunk-assembler", ".bin").toFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(4)

            val completed = AtomicBoolean(false)
            val assembler = assembler(
                targetFile = raf,
                totalChunks = 1,
                onFileComplete = { completed.set(true) },
            )

            val payload = byteArrayOf(4, 3, 2, 1)
            val result = assembler.writeChunk(packet(chunkIndex = 0, offset = 0L, payload = payload))

            assertEquals(WriteResult.Success, result)
            assertTrue(assembler.isComplete)
            assertTrue(completed.get())

            val readBack = ByteArray(4)
            raf.seek(0)
            raf.readFully(readBack)
            assertContentEquals(payload, readBack)
        }
    }

    @Test
    fun `fails file after 3 checksum retry rounds`() = runTest {
        val file = Files.createTempFile("chunk-assembler", ".bin").toFile()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(4)

            val failed = AtomicBoolean(false)
            val retries = AtomicInteger(0)
            val assembler = assembler(
                targetFile = raf,
                totalChunks = 1,
                onRetryRequired = { retries.incrementAndGet() },
                onFileFailed = { failed.set(true) },
            )

            val bad = packet(chunkIndex = 0, offset = 0L, payload = byteArrayOf(1, 1, 1, 1), checksum = 999)
            assembler.writeChunk(bad)
            assembler.writeChunk(bad)
            assembler.writeChunk(bad)

            assertTrue(failed.get())
            assertEquals(2, retries.get())
        }
    }

    private fun assembler(
        targetFile: RandomAccessFile,
        totalChunks: Int,
        resumeState: FluxPartState? = null,
        onRetryRequired: (List<Int>) -> Unit = {},
        onFileComplete: () -> Unit = {},
        onFileFailed: (String) -> Unit = {},
    ): ChunkAssembler {
        val tempDir = Files.createTempDirectory("chunk-assembler-state")
        val fluxPartFile = tempDir.resolve("state.fluxpart").toFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val debouncer = FluxPartDebouncer(
            fluxPartFile = fluxPartFile,
            scope = scope,
            debounceMs = 1,
        )

        return ChunkAssembler(
            targetFile = targetFile,
            totalChunks = totalChunks,
            resumeState = resumeState,
            fluxPartDebouncer = debouncer,
            onRetryRequired = onRetryRequired,
            onFileComplete = onFileComplete,
            onFileFailed = onFileFailed,
        )
    }

    private fun packet(
        chunkIndex: Int,
        offset: Long,
        payload: ByteArray,
        checksum: Int = CRC32Helper.compute(payload),
    ): ChunkPacket = ChunkPacket(
        sessionId = 99L,
        fileId = 7,
        chunkIndex = chunkIndex,
        offset = offset,
        payloadLength = payload.size,
        checksum = checksum,
        payload = payload,
    )

    private fun resumeState(completedChunks: Set<Int>, totalChunks: Int): FluxPartState = FluxPartState(
        sessionId = 1L,
        fileId = 1,
        totalChunks = totalChunks,
        chunkSizeBytes = 4,
        expectedSizeBytes = totalChunks * 4L,
        lastModifiedMs = 0L,
        firstChunkCRC32 = 0,
        createdAtMs = 1L,
        completedChunks = completedChunks,
    )
}
