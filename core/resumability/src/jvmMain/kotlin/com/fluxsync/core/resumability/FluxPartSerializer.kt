package com.fluxsync.core.resumability

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

data class FluxPartState(
    val version: Int = 1,
    val sessionId: Long,
    val fileId: Int,
    val totalChunks: Int,
    val chunkSizeBytes: Int,
    val expectedSizeBytes: Long,
    val lastModifiedMs: Long,
    val firstChunkCRC32: Int,
    val createdAtMs: Long,
    val completedChunks: Set<Int>
)

object FluxPartSerializer {
    val MAGIC = 0x464C5558

    private const val HEADER_SIZE_BYTES =
        Int.SIZE_BYTES +
            Short.SIZE_BYTES +
            Long.SIZE_BYTES +
            Int.SIZE_BYTES +
            Int.SIZE_BYTES +
            Int.SIZE_BYTES +
            Long.SIZE_BYTES +
            Long.SIZE_BYTES +
            Int.SIZE_BYTES +
            Long.SIZE_BYTES

    fun write(state: FluxPartState, file: File) {
        require(state.totalChunks >= 0) { "totalChunks must be non-negative" }

        val bitmapSizeBytes = bitmapSizeBytes(state.totalChunks)
        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES + bitmapSizeBytes)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(MAGIC)
        buffer.putShort(state.version.toShort())
        buffer.putLong(state.sessionId)
        buffer.putInt(state.fileId)
        buffer.putInt(state.totalChunks)
        buffer.putInt(state.chunkSizeBytes)
        buffer.putLong(state.expectedSizeBytes)
        buffer.putLong(state.lastModifiedMs)
        buffer.putInt(state.firstChunkCRC32)
        buffer.putLong(state.createdAtMs)

        val bitmap = ByteArray(bitmapSizeBytes)
        state.completedChunks.forEach { chunkIndex ->
            if (chunkIndex in 0 until state.totalChunks) {
                val byteIndex = chunkIndex / 8
                val bitIndex = chunkIndex % 8
                bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
        }

        buffer.put(bitmap)
        file.outputStream().use { output ->
            output.write(buffer.array())
        }
    }

    fun read(file: File): FluxPartState {
        val bytes = file.readBytes()
        if (bytes.size < HEADER_SIZE_BYTES) {
            throw IllegalStateException("Invalid .fluxpart magic")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.int
        if (magic != MAGIC) {
            throw IllegalStateException("Invalid .fluxpart magic")
        }

        val version = buffer.short.toInt() and 0xFFFF
        val sessionId = buffer.long
        val fileId = buffer.int
        val totalChunks = buffer.int
        val chunkSizeBytes = buffer.int
        val expectedSizeBytes = buffer.long
        val lastModifiedMs = buffer.long
        val firstChunkCRC32 = buffer.int
        val createdAtMs = buffer.long

        val bitmapSizeBytes = bitmapSizeBytes(totalChunks)
        require(bytes.size >= HEADER_SIZE_BYTES + bitmapSizeBytes) {
            "Invalid .fluxpart size"
        }

        val bitmap = ByteArray(bitmapSizeBytes)
        buffer.get(bitmap)
        val completedChunks = decodeCompletedChunks(bitmap, totalChunks)

        return FluxPartState(
            version = version,
            sessionId = sessionId,
            fileId = fileId,
            totalChunks = totalChunks,
            chunkSizeBytes = chunkSizeBytes,
            expectedSizeBytes = expectedSizeBytes,
            lastModifiedMs = lastModifiedMs,
            firstChunkCRC32 = firstChunkCRC32,
            createdAtMs = createdAtMs,
            completedChunks = completedChunks
        )
    }

    fun isValid(file: File): Boolean {
        if (!file.exists() || file.length() < Int.SIZE_BYTES) {
            return false
        }

        return runCatching {
            file.inputStream().use { input ->
                val magicBytes = ByteArray(Int.SIZE_BYTES)
                val read = input.read(magicBytes)
                if (read != Int.SIZE_BYTES) {
                    false
                } else {
                    ByteBuffer.wrap(magicBytes).order(ByteOrder.BIG_ENDIAN).int == MAGIC
                }
            }
        }.getOrDefault(false)
    }

    private fun bitmapSizeBytes(totalChunks: Int): Int {
        if (totalChunks <= 0) {
            return 0
        }
        return ceil(totalChunks / 8.0).toInt()
    }

    private fun decodeCompletedChunks(bitmap: ByteArray, totalChunks: Int): Set<Int> {
        if (totalChunks <= 0) {
            return emptySet()
        }

        val completedChunks = mutableSetOf<Int>()
        for (chunkIndex in 0 until totalChunks) {
            val byteIndex = chunkIndex / 8
            val bitIndex = chunkIndex % 8
            val bitMask = 1 shl bitIndex
            if ((bitmap[byteIndex].toInt() and bitMask) != 0) {
                completedChunks += chunkIndex
            }
        }
        return completedChunks
    }
}
