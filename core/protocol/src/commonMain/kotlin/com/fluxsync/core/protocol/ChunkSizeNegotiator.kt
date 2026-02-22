package com.fluxsync.core.protocol

object ChunkSizeNegotiator {
    const val SMALL = 64 * 1024
    const val NORMAL = 256 * 1024
    const val BIG = 512 * 1024
    const val MASSIVE = 1024 * 1024
    const val GLOBAL_BUFFER_CAP = 64 * 1024 * 1024

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * 1024L * 1024L
    private const val SMALL_FILE_MAX_BYTES_EXCLUSIVE = 100L * MB
    private const val NORMAL_FILE_MAX_BYTES_INCLUSIVE = GB
    private const val BIG_FILE_MAX_BYTES_INCLUSIVE = 5L * GB

    fun negotiate(fileSizeBytes: Long, peerMaxChunkBytes: Int): Int {
        require(fileSizeBytes >= 0) { "fileSizeBytes must be non-negative" }
        require(peerMaxChunkBytes > 0) { "peerMaxChunkBytes must be > 0" }

        val preferred = when {
            fileSizeBytes < SMALL_FILE_MAX_BYTES_EXCLUSIVE -> SMALL
            fileSizeBytes <= NORMAL_FILE_MAX_BYTES_INCLUSIVE -> NORMAL
            fileSizeBytes <= BIG_FILE_MAX_BYTES_INCLUSIVE -> BIG
            else -> MASSIVE
        }

        return minOf(preferred, peerMaxChunkBytes)
    }

    fun queueCapacity(chunkSizeBytes: Int): Int {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
        return GLOBAL_BUFFER_CAP / chunkSizeBytes
    }

    fun tierName(chunkSizeBytes: Int): String = when (chunkSizeBytes) {
        SMALL -> "Small"
        NORMAL -> "Normal"
        BIG -> "Big"
        MASSIVE -> "Massive"
        else -> error("Unsupported chunk size tier: $chunkSizeBytes")
    }
}
