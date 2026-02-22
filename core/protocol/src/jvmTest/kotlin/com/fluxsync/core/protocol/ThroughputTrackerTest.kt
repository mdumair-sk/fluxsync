package com.fluxsync.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThroughputTrackerTest {
    @Test
    fun bytesPerSecond_tracksRollingWindow() {
        val tracker = ThroughputTracker()

        tracker.record(512)
        tracker.record(256)

        assertEquals(768, tracker.bytesPerSecond)
    }

    @Test
    fun bytesPerSecond_expiresOldSamplesAfterOneSecond() {
        val tracker = ThroughputTracker()

        tracker.record(1024)
        Thread.sleep(1_100)

        assertEquals(0, tracker.bytesPerSecond)
    }

    @Test
    fun record_rejectsNegativeBytes() {
        val tracker = ThroughputTracker()

        assertFailsWith<IllegalArgumentException> {
            tracker.record(-1)
        }
    }
}
