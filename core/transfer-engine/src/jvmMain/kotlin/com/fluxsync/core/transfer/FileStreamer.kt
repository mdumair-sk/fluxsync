package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.CRC32Helper
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkSizeNegotiator
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

class FileStreamer(
    private val file: File,
    private val outChannel: SendChannel<ChunkPacket>,
    private val chunkSizeBytes: Int,
    private val sessionId: Long,
    private val fileId: Int,
) {
    suspend fun stream() {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fileChannel ->
            val totalChunks = computeTotalChunks(fileChannel.size())
            val buffer = ByteBuffer.allocateDirect(chunkSizeBytes)

            for (chunkIndex in 0 until totalChunks) {
                val chunkPacket = readChunk(fileChannel = fileChannel, chunkIndex = chunkIndex, buffer = buffer)
                outChannel.send(chunkPacket)
            }
        }
    }

    suspend fun streamChunks(chunkIndices: List<Int>) {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fileChannel ->
            val fileSize = fileChannel.size()
            val buffer = ByteBuffer.allocateDirect(chunkSizeBytes)

            for (chunkIndex in chunkIndices) {
                require(chunkIndex >= 0) { "chunkIndex must be non-negative: $chunkIndex" }

                val offset = chunkIndex.toLong() * chunkSizeBytes
                require(offset < fileSize) {
                    "chunkIndex $chunkIndex (offset=$offset) is out of bounds for file size $fileSize"
                }

                val chunkPacket = readChunk(fileChannel = fileChannel, chunkIndex = chunkIndex, buffer = buffer)
                outChannel.send(chunkPacket)
            }
        }
    }

    private fun computeTotalChunks(fileSize: Long): Int {
        if (fileSize == 0L) return 0
        return ((fileSize + chunkSizeBytes - 1L) / chunkSizeBytes).toInt()
    }

    private fun readChunk(fileChannel: FileChannel, chunkIndex: Int, buffer: ByteBuffer): ChunkPacket {
        val offset = chunkIndex.toLong() * chunkSizeBytes

        buffer.clear()
        val bytesRead = fileChannel.read(buffer, offset)
        check(bytesRead > 0) {
            "Expected positive bytesRead for chunkIndex=$chunkIndex at offset=$offset, but got $bytesRead"
        }

        buffer.flip()

        val payload = ByteArray(bytesRead)
        buffer.get(payload)

        return ChunkPacket(
            sessionId = sessionId,
            fileId = fileId,
            chunkIndex = chunkIndex,
            offset = offset,
            payloadLength = payload.size,
            checksum = CRC32Helper.compute(payload),
            payload = payload,
        )
    }
}

data class FileStreamerBundle(
    val channel: Channel<ChunkPacket>,
    val fileStreamer: FileStreamer,
)

object FileStreamerFactory {
    fun create(
        file: File,
        chunkSizeBytes: Int,
        sessionId: Long,
        fileId: Int,
    ): FileStreamerBundle {
        val channelCapacity = ChunkSizeNegotiator.queueCapacity(chunkSizeBytes)
        val channel = Channel<ChunkPacket>(capacity = channelCapacity)

        return FileStreamerBundle(
            channel = channel,
            fileStreamer = FileStreamer(
                file = file,
                outChannel = channel,
                chunkSizeBytes = chunkSizeBytes,
                sessionId = sessionId,
                fileId = fileId,
            ),
        )
    }
}
