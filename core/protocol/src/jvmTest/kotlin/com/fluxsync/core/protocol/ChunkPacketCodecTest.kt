package com.fluxsync.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChunkPacketCodecTest {
    @Test
    fun roundTripEncodeDecode() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = ChunkPacket(
            sessionId = 42L,
            fileId = 7,
            chunkIndex = 3,
            offset = 8192L,
            payloadLength = payload.size,
            checksum = CRC32Helper.compute(payload),
            payload = payload,
        )

        val buffer = ByteBuffer.allocateDirect(ChunkPacketCodec.HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        packet.writeTo(buffer)
        buffer.flip()

        val decoded = ChunkPacket.readFrom(buffer)

        assertEquals(packet.sessionId, decoded.sessionId)
        assertEquals(packet.fileId, decoded.fileId)
        assertEquals(packet.chunkIndex, decoded.chunkIndex)
        assertEquals(packet.offset, decoded.offset)
        assertEquals(packet.payloadLength, decoded.payloadLength)
        assertEquals(packet.checksum, decoded.checksum)
        assertContentEquals(packet.payload, decoded.payload)
    }
}
