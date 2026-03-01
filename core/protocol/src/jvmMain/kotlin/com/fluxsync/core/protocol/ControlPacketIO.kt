package com.fluxsync.core.protocol

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Length-prefixed framing for [ControlPacketSerializer] over raw socket streams.
 *
 * Wire format: `[4-byte big-endian length] [encoded control packet bytes]`
 *
 * Used during the handshake / consent / session-complete phases. Chunk data is written as raw
 * [ChunkPacket] binary — NOT via this helper.
 */
object ControlPacketIO {

    /** Writes a control packet to [out] with a 4-byte big-endian length prefix. */
    fun writePacket(out: OutputStream, packet: Any) {
        val encoded = ControlPacketSerializer.encodeToBytes(packet)
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        header.putInt(encoded.size)
        out.write(header.array())
        out.write(encoded)
        out.flush()
    }

    /**
     * Reads a single length-prefixed control packet from [input].
     *
     * @throws EOFException if the stream ends before a complete packet is read.
     */
    fun readPacket(input: InputStream): Any {
        val headerBytes = ByteArray(4)
        readFully(input, headerBytes, 4)
        val length = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN).int
        require(length in 1..MAX_CONTROL_PACKET_BYTES) {
            "Control packet length out of bounds: $length"
        }

        val body = ByteArray(length)
        readFully(input, body, length)
        return ControlPacketSerializer.decodeFromBytes(body)
    }

    /**
     * Reads a control packet and casts it to the expected type. Throws [IllegalStateException] if
     * the deserialized packet has a different type.
     */
    inline fun <reified T> readTyped(input: InputStream): T {
        val packet = readPacket(input)
        return packet as? T
                ?: error("Expected ${T::class.simpleName} but got ${packet::class.simpleName}")
    }

    private fun readFully(input: InputStream, target: ByteArray, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val n = input.read(target, totalRead, length - totalRead)
            if (n < 0) throw EOFException("Stream ended after $totalRead/$length bytes")
            totalRead += n
        }
    }

    private const val MAX_CONTROL_PACKET_BYTES = 1024 * 1024 // 1 MB safety cap
}
