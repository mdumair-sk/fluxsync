package com.fluxsync.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

data class ChunkPacket(
    val sessionId: Long,
    val fileId: Int,
    val chunkIndex: Int,
    val offset: Long,
    val payloadLength: Int,
    val checksum: Int,
    val payload: ByteArray,
) {
    fun writeTo(buffer: ByteBuffer) {
        require(payloadLength == payload.size) {
            "payloadLength ($payloadLength) must match payload size (${payload.size})"
        }

        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(sessionId)
        buffer.putInt(fileId)
        buffer.putInt(chunkIndex)
        buffer.putLong(offset)
        buffer.putInt(payloadLength)
        buffer.putInt(checksum)
        buffer.put(payload)
    }

    companion object {
        fun readFrom(buffer: ByteBuffer): ChunkPacket {
            buffer.order(ByteOrder.BIG_ENDIAN)

            val sessionId = buffer.long
            val fileId = buffer.int
            val chunkIndex = buffer.int
            val offset = buffer.long
            val payloadLength = buffer.int
            val checksum = buffer.int

            require(payloadLength >= 0) { "payloadLength must be non-negative: $payloadLength" }

            val payload = ByteArray(payloadLength)
            buffer.get(payload)

            return ChunkPacket(
                sessionId = sessionId,
                fileId = fileId,
                chunkIndex = chunkIndex,
                offset = offset,
                payloadLength = payloadLength,
                checksum = checksum,
                payload = payload,
            )
        }
    }
}

object ChunkPacketCodec {
    const val HEADER_SIZE: Int = 32

    fun writeTo(chunk: ChunkPacket, buffer: ByteBuffer) {
        require(buffer.isDirect) { "ChunkPacketCodec requires a direct ByteBuffer" }

        val requiredBytes = HEADER_SIZE + chunk.payloadLength
        require(buffer.capacity() >= requiredBytes) {
            "Buffer capacity (${buffer.capacity()}) is smaller than required bytes ($requiredBytes)"
        }

        buffer.clear()
        chunk.writeTo(buffer)
        buffer.flip()
    }
}

object CRC32Helper {
    fun compute(payload: ByteArray): Int {
        val crc32 = CRC32()
        crc32.update(payload)
        return crc32.value.toInt()
    }
}
