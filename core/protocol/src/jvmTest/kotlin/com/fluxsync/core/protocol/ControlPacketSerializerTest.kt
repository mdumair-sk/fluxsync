package com.fluxsync.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ControlPacketSerializerTest {
    @Test
    fun `encode and decode handshake packet`() {
        val packet = HandshakePacket(
            protocolVersion = 2,
            deviceName = "Desktop",
            certFingerprint = "ab:cd",
            maxChunkSizeBytes = 262_144,
            availableMemoryMb = 2048,
        )

        val encoded = ControlPacketSerializer.encodeToBytes(packet)
        val decoded = ControlPacketSerializer.decodeFromBytes(encoded)

        assertIs<HandshakePacket>(decoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun `encode and decode consent request packet`() {
        val packet = ConsentRequestPacket(
            sessionId = 42L,
            manifest = FileManifest(
                sessionId = 42L,
                files = listOf(
                    FileEntry(
                        fileId = 1,
                        name = "video.mp4",
                        sizeBytes = 4_000_000,
                        totalChunks = 16,
                        negotiatedChunkSizeBytes = 256_000,
                        resumeValidation = ResumeValidation(
                            fileId = "1",
                            expectedSizeBytes = 4_000_000,
                            lastModifiedEpochMs = 1_727_000_000_000,
                            firstChunkChecksum = 123,
                        ),
                    ),
                ),
            ),
        )

        val encoded = ControlPacketSerializer.encodeToBytes(packet)
        val decoded = ControlPacketSerializer.decodeFromBytes(encoded)

        assertIs<ConsentRequestPacket>(decoded)
        assertEquals(packet, decoded)
    }

    @Test
    fun `decode fails on unknown tag`() {
        val bytes = byteArrayOf(127, '{'.code.toByte(), '}'.code.toByte())

        assertFailsWith<IllegalArgumentException> {
            ControlPacketSerializer.decodeFromBytes(bytes)
        }
    }
}
