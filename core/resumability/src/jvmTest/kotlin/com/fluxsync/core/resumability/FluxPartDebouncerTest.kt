package com.fluxsync.core.resumability

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FluxPartDebouncerTest {

    @Test
    fun `rapid schedule calls result in one disk write`() = runTest {
        val tempDir = Files.createTempDirectory("fluxpart-debouncer-test")
        val fluxPartFile = tempDir.resolve("state.fluxpart").toFile()

        val writeCount = AtomicInteger(0)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)

        val debouncer = FluxPartDebouncer(
            fluxPartFile = fluxPartFile,
            scope = scope,
            debounceMs = 2_000L,
            writer = { state, file ->
                writeCount.incrementAndGet()
                FluxPartSerializer.write(state, file)
            }
        )

        repeat(10) { index ->
            debouncer.scheduleWrite(sampleState(completedChunks = setOf(index)))
            scope.advanceTimeBy(100L)
        }

        scope.advanceTimeBy(2_000L)
        scope.testScheduler.runCurrent()

        assertEquals(1, writeCount.get())
    }

    private fun sampleState(completedChunks: Set<Int>): FluxPartState = FluxPartState(
        sessionId = 42L,
        fileId = 1,
        totalChunks = 16,
        chunkSizeBytes = 256 * 1024,
        expectedSizeBytes = 4_194_304L,
        lastModifiedMs = 1_720_000_000_000L,
        firstChunkCRC32 = 0x11223344,
        createdAtMs = 1_720_000_000_123L,
        completedChunks = completedChunks
    )
}
