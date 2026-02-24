package com.fluxsync.core.transfer

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogTest {
    @AfterTest
    fun tearDown() {
        DebugLog.clear()
    }

    @Test
    fun `log keeps only the newest 500 entries`() {
        repeat(505) { index ->
            DebugLog.log(LogLevel.INFO, "DebugLogTest", "message-$index")
        }

        val entries = DebugLog.getEntries()
        assertEquals(500, entries.size)
        assertEquals("message-5", entries.first().message)
        assertEquals("message-504", entries.last().message)
    }

    @Test
    fun `string extension functions write expected levels`() {
        "debug".logD("DebugLogTest")
        "info".logI("DebugLogTest")
        "warn".logW("DebugLogTest")
        "error".logE("DebugLogTest")

        val levels = DebugLog.getEntries().map { it.level }
        assertEquals(listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR), levels)
    }

    @Test
    fun `exportToFile writes formatted entries`() {
        DebugLog.log(LogLevel.ERROR, "DebugLogTest", "boom")

        val output = Files.createTempFile("debug-log", ".txt").toFile()
        DebugLog.exportToFile(output)

        val content = output.readText()
        assertTrue(content.contains("ERROR DebugLogTest: boom"))
        assertFalse(content.isBlank())
    }
}
