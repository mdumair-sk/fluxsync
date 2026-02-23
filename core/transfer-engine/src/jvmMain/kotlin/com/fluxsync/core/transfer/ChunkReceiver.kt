package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkPacketCodec
import com.fluxsync.core.protocol.ChunkSizeNegotiator
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.Channel

class ChunkReceiver(
    private val assembler: ChunkAssembler,
    private val chunkSizeBytes: Int,
) {
    private val inboundQueue: Channel<ChunkPacket> = Channel(
        capacity = ChunkSizeNegotiator.queueCapacity(chunkSizeBytes),
    )

    suspend fun socketReadLoop(inputStream: InputStream) {
        val readBuffer = ByteBuffer.allocateDirect(ChunkPacketCodec.HEADER_SIZE + chunkSizeBytes)
            .order(ByteOrder.BIG_ENDIAN)
        val ioBuffer = ByteArray(readBuffer.capacity())

        while (!inboundQueue.isClosedForSend) {
            readFully(inputStream, ioBuffer, ChunkPacketCodec.HEADER_SIZE)

            readBuffer.clear()
            readBuffer.put(ioBuffer, 0, ChunkPacketCodec.HEADER_SIZE)
            readBuffer.flip()
            readBuffer.position(PAYLOAD_LENGTH_OFFSET)
            val payloadLength = readBuffer.int

            require(payloadLength in 0..chunkSizeBytes) {
                "Invalid payloadLength=$payloadLength, maxChunkSize=$chunkSizeBytes"
            }

            readFully(inputStream, ioBuffer, payloadLength, destinationOffset = ChunkPacketCodec.HEADER_SIZE)

            readBuffer.clear()
            readBuffer.put(ioBuffer, 0, ChunkPacketCodec.HEADER_SIZE + payloadLength)
            readBuffer.flip()

            inboundQueue.send(ChunkPacket.readFrom(readBuffer))
        }
    }

    suspend fun assemblyLoop() {
        for (chunk in inboundQueue) {
            assembler.writeChunk(chunk)
        }
    }

    fun close() {
        inboundQueue.close()
    }

    private fun readFully(
        inputStream: InputStream,
        target: ByteArray,
        length: Int,
        destinationOffset: Int = 0,
    ) {
        var totalRead = 0
        while (totalRead < length) {
            val bytesRead = inputStream.read(target, destinationOffset + totalRead, length - totalRead)
            if (bytesRead < 0) {
                throw EOFException("Unexpected end of stream after $totalRead/$length bytes")
            }
            totalRead += bytesRead
        }
    }

    private companion object {
        private const val PAYLOAD_LENGTH_OFFSET = 24
    }
}
