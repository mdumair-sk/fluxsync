package com.fluxsync.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ChunkSizeNegotiatorTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `negotiate chooses small below 100MB boundary`() {
        val fileSize = (100L * mb) - 1

        val chunkSize = ChunkSizeNegotiator.negotiate(fileSize, peerMaxChunkBytes = Int.MAX_VALUE)

        assertEquals(ChunkSizeNegotiator.SMALL, chunkSize)
        assertEquals("Small", ChunkSizeNegotiator.tierName(chunkSize))
    }

    @Test
    fun `negotiate chooses normal at 100MB boundary`() {
        val fileSize = 100L * mb

        val chunkSize = ChunkSizeNegotiator.negotiate(fileSize, peerMaxChunkBytes = Int.MAX_VALUE)

        assertEquals(ChunkSizeNegotiator.NORMAL, chunkSize)
        assertEquals("Normal", ChunkSizeNegotiator.tierName(chunkSize))
    }

    @Test
    fun `negotiate chooses big above 1GB boundary`() {
        val fileSize = gb + 1

        val chunkSize = ChunkSizeNegotiator.negotiate(fileSize, peerMaxChunkBytes = Int.MAX_VALUE)

        assertEquals(ChunkSizeNegotiator.BIG, chunkSize)
        assertEquals("Big", ChunkSizeNegotiator.tierName(chunkSize))
    }

    @Test
    fun `negotiate chooses massive above 5GB boundary`() {
        val fileSize = (5L * gb) + 1

        val chunkSize = ChunkSizeNegotiator.negotiate(fileSize, peerMaxChunkBytes = Int.MAX_VALUE)

        assertEquals(ChunkSizeNegotiator.MASSIVE, chunkSize)
        assertEquals("Massive", ChunkSizeNegotiator.tierName(chunkSize))
    }

    @Test
    fun `negotiate is capped by peer max chunk size`() {
        val fileSize = 10L * gb

        val chunkSize = ChunkSizeNegotiator.negotiate(fileSize, peerMaxChunkBytes = ChunkSizeNegotiator.NORMAL)

        assertEquals(ChunkSizeNegotiator.NORMAL, chunkSize)
    }

    @Test
    fun `queue capacity is computed from global buffer cap`() {
        assertEquals(1024, ChunkSizeNegotiator.queueCapacity(ChunkSizeNegotiator.SMALL))
        assertEquals(256, ChunkSizeNegotiator.queueCapacity(ChunkSizeNegotiator.NORMAL))
        assertEquals(128, ChunkSizeNegotiator.queueCapacity(ChunkSizeNegotiator.BIG))
        assertEquals(64, ChunkSizeNegotiator.queueCapacity(ChunkSizeNegotiator.MASSIVE))
    }
}
