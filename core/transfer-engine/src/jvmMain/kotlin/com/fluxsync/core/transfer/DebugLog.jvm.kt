package com.fluxsync.core.transfer

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String = "[${formatTime(timestampMs)}] ${level.name} $tag: $message"
}

object DebugLog {
    private const val MAX_SIZE = 500
    private val lock = ReentrantLock()
    private val entries = ArrayDeque<LogEntry>(MAX_SIZE)

    fun log(level: LogLevel, tag: String, message: String) {
        lock.withLock {
            if (entries.size >= MAX_SIZE) {
                entries.removeFirst()
            }
            entries.addLast(
                LogEntry(
                    timestampMs = epochMillisecondsNow(),
                    level = level,
                    tag = tag,
                    message = message,
                ),
            )
        }
    }

    fun getEntries(): List<LogEntry> = lock.withLock { entries.toList() }

    fun exportToFile(file: File) {
        val snapshot = getEntries()
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { writer ->
            snapshot.forEach { entry ->
                writer.appendLine(entry.format())
            }
        }
    }

    fun clear() {
        lock.withLock {
            entries.clear()
        }
    }
}

fun String.logD(tag: String) = DebugLog.log(level = LogLevel.DEBUG, tag = tag, message = this)

fun String.logI(tag: String) = DebugLog.log(level = LogLevel.INFO, tag = tag, message = this)

fun String.logW(tag: String) = DebugLog.log(level = LogLevel.WARN, tag = tag, message = this)

fun String.logE(tag: String) = DebugLog.log(level = LogLevel.ERROR, tag = tag, message = this)

private val logTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

private fun formatTime(timestampMs: Long): String =
    logTimestampFormatter.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))
