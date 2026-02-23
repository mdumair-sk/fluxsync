package com.fluxsync.core.resumability

import com.fluxsync.core.protocol.CRC32Helper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResumeValidatorTest {

    @Test
    fun `validate returns OK when all checks match`() {
        val tempFile = Files.createTempFile("resume-validator-ok", ".bin").toFile()
        val data = ByteArray(1_024) { (it % 251).toByte() }
        tempFile.writeBytes(data)

        val state = fluxPartStateFor(tempFile, chunkSizeBytes = 256)

        assertEquals(ResumeValidationResult.OK, ResumeValidator.validate(state, tempFile))
    }

    @Test
    fun `validate returns ABORT_FILE_CHANGED when size mismatch`() {
        val tempFile = Files.createTempFile("resume-validator-size", ".bin").toFile()
        tempFile.writeBytes(ByteArray(128) { it.toByte() })

        val state = fluxPartStateFor(tempFile, chunkSizeBytes = 64).copy(expectedSizeBytes = tempFile.length() + 1)

        assertEquals(ResumeValidationResult.ABORT_FILE_CHANGED, ResumeValidator.validate(state, tempFile))
    }

    @Test
    fun `validate returns ABORT_FILE_CHANGED when timestamp mismatch`() {
        val tempFile = Files.createTempFile("resume-validator-time", ".bin").toFile()
        tempFile.writeBytes(ByteArray(128) { it.toByte() })

        val state = fluxPartStateFor(tempFile, chunkSizeBytes = 64).copy(lastModifiedMs = tempFile.lastModified() + 1)

        assertEquals(ResumeValidationResult.ABORT_FILE_CHANGED, ResumeValidator.validate(state, tempFile))
    }

    @Test
    fun `validate returns ABORT_FILE_CHANGED when first chunk crc mismatch`() {
        val tempFile = Files.createTempFile("resume-validator-crc", ".bin").toFile()
        tempFile.writeBytes(ByteArray(512) { (it % 100).toByte() })

        val state = fluxPartStateFor(tempFile, chunkSizeBytes = 128).copy(firstChunkCRC32 = 0)

        assertEquals(ResumeValidationResult.ABORT_FILE_CHANGED, ResumeValidator.validate(state, tempFile))
    }

    @Test
    fun `validate returns ABORT_FLUXPART_CORRUPT when io exception occurs during crc check`() {
        val tempDir = Files.createTempDirectory("resume-validator-io").toFile()
        val state = FluxPartState(
            sessionId = 99L,
            fileId = 10,
            totalChunks = 1,
            chunkSizeBytes = 64,
            expectedSizeBytes = tempDir.length(),
            lastModifiedMs = tempDir.lastModified(),
            firstChunkCRC32 = 123,
            createdAtMs = System.currentTimeMillis(),
            completedChunks = emptySet()
        )

        assertEquals(ResumeValidationResult.ABORT_FLUXPART_CORRUPT, ResumeValidator.validate(state, tempDir))
    }

    @Test
    fun `computeFirstChunkCRC reads only first chunk bytes`() {
        val tempFile = Files.createTempFile("resume-validator-first-chunk", ".bin").toFile()
        val firstChunk = ByteArray(64) { 1 }
        val rest = ByteArray(64) { 2 }
        tempFile.writeBytes(firstChunk + rest)

        val expected = CRC32Helper.compute(firstChunk)

        assertEquals(expected, ResumeValidator.computeFirstChunkCRC(tempFile, 64))
    }

    @Test
    fun `isOrphaned flags stale fluxpart state`() {
        val now = System.currentTimeMillis()
        val stale = FluxPartState(
            sessionId = 1L,
            fileId = 1,
            totalChunks = 1,
            chunkSizeBytes = 64,
            expectedSizeBytes = 10,
            lastModifiedMs = 123,
            firstChunkCRC32 = 42,
            createdAtMs = now - (8 * 24 * 3_600 * 1_000L),
            completedChunks = emptySet()
        )

        assertTrue(ResumeValidator.isOrphaned(stale))
        assertFalse(ResumeValidator.isOrphaned(stale.copy(createdAtMs = now - 1_000)))
    }

    private fun fluxPartStateFor(sourceFile: java.io.File, chunkSizeBytes: Int): FluxPartState {
        return FluxPartState(
            sessionId = 99L,
            fileId = 10,
            totalChunks = 4,
            chunkSizeBytes = chunkSizeBytes,
            expectedSizeBytes = sourceFile.length(),
            lastModifiedMs = sourceFile.lastModified(),
            firstChunkCRC32 = ResumeValidator.computeFirstChunkCRC(sourceFile, chunkSizeBytes),
            createdAtMs = System.currentTimeMillis(),
            completedChunks = emptySet()
        )
    }
}
