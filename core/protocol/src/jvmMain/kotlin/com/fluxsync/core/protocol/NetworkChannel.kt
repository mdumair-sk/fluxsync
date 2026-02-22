package com.fluxsync.core.protocol

enum class ChannelType {
    WIFI,
    USB_ADB,
}

enum class ChannelState {
    ACTIVE,
    DEGRADED,
    OFFLINE,
}

interface NetworkChannel {
    val id: String
    val type: ChannelType
    var state: ChannelState
    val measuredThroughput: Long
    val lastLatencyMs: Long
    val sendBufferFillFraction: Float

    suspend fun send(chunk: ChunkPacket)
    fun recordSuccess(bytesSent: Long)
    fun close()
}

data class ChannelTelemetry(
    val channelId: String,
    val type: ChannelType,
    val state: ChannelState,
    val throughputBytesPerSec: Long,
    val weightFraction: Float,
    val bufferFillPercent: Float,
    val latencyMs: Long,
)

class ThroughputTracker {
    private val samples = ArrayDeque<Sample>()
    private var rollingBytes = 0L

    val bytesPerSecond: Long
        get() {
            trimExpired(nowMs = System.currentTimeMillis())
            return rollingBytes
        }

    fun record(bytes: Long) {
        require(bytes >= 0) { "bytes must be non-negative: $bytes" }

        val nowMs = System.currentTimeMillis()
        trimExpired(nowMs)

        samples.addLast(Sample(timestampMs = nowMs, bytes = bytes))
        rollingBytes += bytes
    }

    private fun trimExpired(nowMs: Long) {
        val cutoffMs = nowMs - WINDOW_MS
        while (true) {
            val first = samples.firstOrNull() ?: return
            if (first.timestampMs > cutoffMs) {
                return
            }

            samples.removeFirst()
            rollingBytes -= first.bytes
        }
    }

    private data class Sample(
        val timestampMs: Long,
        val bytes: Long,
    )

    private companion object {
        const val WINDOW_MS = 1_000L
    }
}
