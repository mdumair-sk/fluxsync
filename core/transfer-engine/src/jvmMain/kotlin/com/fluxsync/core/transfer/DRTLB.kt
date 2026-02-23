package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.ChannelTelemetry
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.NetworkChannel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DRTLB(
    private val chunkSource: ReceiveChannel<ChunkPacket>,
    private val retrySlot: Channel<ChunkPacket> = Channel(capacity = 64),
) {
    private val channels = mutableListOf<NetworkChannel>()
    private val channelsMutex = Mutex()

    private val _telemetryFlow = MutableStateFlow<List<ChannelTelemetry>>(emptyList())
    val telemetryFlow: StateFlow<List<ChannelTelemetry>> = _telemetryFlow.asStateFlow()

    fun registerChannel(channel: NetworkChannel) = runBlocking {
        channelsMutex.withLock {
            channels.removeAll { it.id == channel.id }
            channels += channel
        }
    }

    fun removeChannel(channel: NetworkChannel) = runBlocking {
        channelsMutex.withLock {
            channels.remove(channel)
        }
    }

    fun onForceWifiOnlyChanged(enabled: Boolean) = runBlocking {
        if (!enabled) return

        channelsMutex.withLock {
            channels.removeAll { it.type == ChannelType.USB_ADB }
        }
    }

    suspend fun run() = coroutineScope {
        launch {
            telemetryLoop()
        }

        val activeChannels = channelsMutex.withLock {
            channels.filter { it.state == ChannelState.ACTIVE }
        }

        activeChannels.forEach { channel ->
            launch {
                channelWorker(channel)
            }
        }
    }

    fun sendToRetry(chunk: ChunkPacket) {
        val result = retrySlot.trySend(chunk)
        check(result.isSuccess) {
            "Unable to re-enqueue chunk ${chunk.chunkIndex} for retry: ${result.exceptionOrNull()?.message}"
        }
    }

    private suspend fun channelWorker(channel: NetworkChannel) {
        while (true) {
            val chunk = try {
                select {
                    retrySlot.onReceive { it }
                    chunkSource.onReceive { it }
                }
            } catch (_: ClosedReceiveChannelException) {
                return
            }

            try {
                channel.send(chunk)
                channel.recordSuccess(chunk.payloadLength.toLong())
            } catch (_: IOException) {
                channel.state = ChannelState.DEGRADED
                retrySlot.send(chunk)
                return
            }
        }
    }

    private suspend fun telemetryLoop() {
        while (isActive) {
            try {
                val snapshot = channelsMutex.withLock { channels.toList() }
                val totalThroughput = snapshot.sumOf { it.measuredThroughput }

                val telemetry = snapshot.map { channel ->
                    ChannelTelemetry(
                        channelId = channel.id,
                        type = channel.type,
                        state = channel.state,
                        throughputBytesPerSec = channel.measuredThroughput,
                        weightFraction = if (totalThroughput > 0L) {
                            channel.measuredThroughput.toFloat() / totalThroughput.toFloat()
                        } else {
                            0f
                        },
                        bufferFillPercent = channel.sendBufferFillFraction * 100f,
                        latencyMs = channel.lastLatencyMs,
                    )
                }

                _telemetryFlow.value = telemetry
                delay(200)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
            }
        }
    }
}
