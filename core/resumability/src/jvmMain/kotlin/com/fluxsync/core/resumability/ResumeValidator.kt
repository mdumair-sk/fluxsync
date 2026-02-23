package com.fluxsync.core.resumability

import com.fluxsync.core.protocol.CRC32Helper
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

enum class ResumeValidationResult {
    OK,
    ABORT_FILE_CHANGED,
    ABORT_FLUXPART_CORRUPT,
}

object ResumeValidator {
    private const val DEFAULT_ORPHAN_MAX_AGE_MS = 7 * 24 * 3_600 * 1_000L

    fun validate(fluxPart: FluxPartState, sourceFile: File): ResumeValidationResult {
        if (sourceFile.length() != fluxPart.expectedSizeBytes) {
            return ResumeValidationResult.ABORT_FILE_CHANGED
        }

        if (sourceFile.lastModified() != fluxPart.lastModifiedMs) {
            return ResumeValidationResult.ABORT_FILE_CHANGED
        }

        val firstChunkCRC = try {
            computeFirstChunkCRC(sourceFile, fluxPart.chunkSizeBytes)
        } catch (_: IOException) {
            return ResumeValidationResult.ABORT_FLUXPART_CORRUPT
        }

        if (firstChunkCRC != fluxPart.firstChunkCRC32) {
            return ResumeValidationResult.ABORT_FILE_CHANGED
        }

        return ResumeValidationResult.OK
    }

    @Throws(IOException::class)
    fun computeFirstChunkCRC(file: File, chunkSizeBytes: Int): Int {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be positive" }

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val maxBytesToRead = minOf(chunkSizeBytes.toLong(), channel.size()).toInt()
            if (maxBytesToRead == 0) {
                return CRC32Helper.compute(byteArrayOf())
            }

            val directBuffer = ByteBuffer.allocateDirect(maxBytesToRead)
            while (directBuffer.hasRemaining()) {
                val read = channel.read(directBuffer)
                if (read < 0) {
                    break
                }
            }

            directBuffer.flip()
            val payload = ByteArray(directBuffer.remaining())
            directBuffer.get(payload)
            return CRC32Helper.compute(payload)
        }
    }

    fun isOrphaned(fluxPart: FluxPartState, maxAgeMs: Long = DEFAULT_ORPHAN_MAX_AGE_MS): Boolean {
        require(maxAgeMs >= 0) { "maxAgeMs must be non-negative" }

        val ageMs = System.currentTimeMillis() - fluxPart.createdAtMs
        return ageMs > maxAgeMs
    }
}
