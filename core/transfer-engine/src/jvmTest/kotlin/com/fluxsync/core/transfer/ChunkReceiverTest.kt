package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.CRC32Helper
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkPacketCodec
import com.fluxsync.core.resumability.FluxPartDebouncer
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkReceiverTest {

    @Test
    fun `socketReadLoop feeds assemblyLoop and writes all chunks`() = runTest {
        val payloadOne = byteArrayOf(1, 2, 3, 4)
        val payloadTwo = byteArrayOf(5, 6, 7, 8)
        val packetOne = packet(chunkIndex = 0, offset = 0, payload = payloadOne)
        val packetTwo = packet(chunkIndex = 1, offset = 4, payload = payloadTwo)

        val input = ByteArrayInputStream(encode(packetOne) + encode(packetTwo))
        val completeNotified = AtomicBoolean(false)

        createAssembler(totalChunks = 2, fileLength = 8, onFileComplete = { completeNotified.set(true) }).use { fixture ->
            val receiver = ChunkReceiver(assembler = fixture.assembler, chunkSizeBytes = 64 * 1024)

            val assemblyJob = async { receiver.assemblyLoop() }
            receiver.socketReadLoop(input)
            receiver.close()
            assemblyJob.await()

            val result = ByteArray(8)
            fixture.targetFile.seek(0)
            fixture.targetFile.readFully(result)

            assertContentEquals(payloadOne + payloadTwo, result)
            assertTrue(completeNotified.get())
        }
    }

    @Test
    fun `socketReadLoop fails loudly when payload exceeds configured chunk size`() = runTest {
        val payload = ByteArray(8) { it.toByte() }
        val packet = packet(chunkIndex = 0, offset = 0, payload = payload)
        val input = ByteArrayInputStream(encode(packet))

        createAssembler(totalChunks = 1, fileLength = 8).use { fixture ->
            val receiver = ChunkReceiver(assembler = fixture.assembler, chunkSizeBytes = 4)

            assertFailsWith<IllegalArgumentException> {
                receiver.socketReadLoop(input)
            }
        }
    }

    private fun packet(chunkIndex: Int, offset: Long, payload: ByteArray): ChunkPacket = ChunkPacket(
        sessionId = 777L,
        fileId = 12,
        chunkIndex = chunkIndex,
        offset = offset,
        payloadLength = payload.size,
        checksum = CRC32Helper.compute(payload),
        payload = payload,
    )

    private fun encode(packet: ChunkPacket): ByteArray {
        val buffer = ByteBuffer.allocate(ChunkPacketCodec.HEADER_SIZE + packet.payloadLength)
            .order(ByteOrder.BIG_ENDIAN)
        packet.writeTo(buffer)
        return buffer.array()
    }

    private fun createAssembler(
        totalChunks: Int,
        fileLength: Long,
        onFileComplete: () -> Unit = {},
    ): AssemblerFixture {
        val tempFile = Files.createTempFile("chunk-receiver", ".bin").toFile()
        val targetFile = RandomAccessFile(tempFile, "rw")
        targetFile.setLength(fileLength)

        val fluxPartFile = Files.createTempFile("chunk-receiver", ".fluxpart").toFile()
        val debouncer = FluxPartDebouncer(
            fluxPartFile = fluxPartFile,
            scope = this,
            debounceMs = 1,
        )

        val assembler = ChunkAssembler(
            targetFile = targetFile,
            totalChunks = totalChunks,
            resumeState = null,
            fluxPartDebouncer = debouncer,
            onRetryRequired = {},
            onFileComplete = onFileComplete,
            onFileFailed = { error("Unexpected failure callback: $it") },
        )

        return AssemblerFixture(assembler = assembler, targetFile = targetFile)
    }

    private data class AssemblerFixture(
        val assembler: ChunkAssembler,
        val targetFile: RandomAccessFile,
    ) : AutoCloseable {
        override fun close() {
            targetFile.close()
        }
    }
}
