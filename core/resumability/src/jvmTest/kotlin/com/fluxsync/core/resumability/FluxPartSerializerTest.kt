package com.fluxsync.core.resumability

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FluxPartSerializerTest {

    @Test
    fun `read throws when magic is invalid`() {
        val tempDir = Files.createTempDirectory("fluxpart-invalid-magic")
        val fluxPartFile = tempDir.resolve("invalid.fluxpart").toFile()
        fluxPartFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))

        val exception = assertFailsWith<IllegalStateException> {
            FluxPartSerializer.read(fluxPartFile)
        }
        assertEquals("Invalid .fluxpart magic", exception.message)
    }


    @Test
    fun `write and read round trip with even chunks marked complete`() {
        val tempDir = Files.createTempDirectory("fluxpart-test")
        val fluxPartFile = tempDir.resolve("sample.fluxpart").toFile()

        val state = FluxPartState(
            sessionId = 99L,
            fileId = 7,
            totalChunks = 100,
            chunkSizeBytes = 256 * 1024,
            expectedSizeBytes = 20_000_000L,
            lastModifiedMs = 1_720_000_000_000,
            firstChunkCRC32 = 0x1A2B3C4D,
            createdAtMs = 1_720_000_100_000,
            completedChunks = (0 until 100).filter { it % 2 == 0 }.toSet()
        )

        FluxPartSerializer.write(state, fluxPartFile)

        assertTrue(FluxPartSerializer.isValid(fluxPartFile))

        val roundTripped = FluxPartSerializer.read(fluxPartFile)
        assertEquals(state, roundTripped)

        val expectedCompletedChunks = (0 until 100).filter { it % 2 == 0 }.toSet()
        assertEquals(expectedCompletedChunks, roundTripped.completedChunks)

        fluxPartFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertFalse(FluxPartSerializer.isValid(fluxPartFile))
    }
}
