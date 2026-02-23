package com.fluxsync.android.data.network

import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkPacketCodec
import com.fluxsync.core.protocol.NetworkChannel
import com.fluxsync.core.protocol.ThroughputTracker
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.Channels
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WifiNetworkChannel(
    private val socket: Socket,
    private val scope: CoroutineScope,
) : NetworkChannel {
    override val id: String = socket.remoteSocketAddress?.toString() ?: "wifi:${socket.hashCode()}"
    override val type: ChannelType = ChannelType.WIFI
    override var state: ChannelState = ChannelState.ACTIVE

    override val measuredThroughput: Long
        get() = throughputTracker.bytesPerSecond

    override val lastLatencyMs: Long = 0L

    // TCP send-buffer occupancy is managed by the kernel and is not directly observable for java.net.Socket.
    override val sendBufferFillFraction: Float = 0f

    private val throughputTracker = ThroughputTracker()
    private val writeMutex = Mutex()
    private val sendBuffer = ByteBuffer.allocateDirect(ChunkPacketCodec.HEADER_SIZE + MAX_CHUNK_SIZE)
    private val writableChannel = Channels.newChannel(socket.getOutputStream())

    init {
        configureSocket(socket)
    }

    override suspend fun send(chunk: ChunkPacket) {
        withContext(scope.coroutineContext + Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    ChunkPacketCodec.writeTo(chunk, sendBuffer)
                    while (sendBuffer.hasRemaining()) {
                        writableChannel.write(sendBuffer)
                    }
                    recordSuccess((ChunkPacketCodec.HEADER_SIZE + chunk.payloadLength).toLong())
                } catch (t: Throwable) {
                    state = ChannelState.DEGRADED
                    throw t
                }
            }
        }
    }

    override fun recordSuccess(bytesSent: Long) {
        throughputTracker.record(bytesSent)
    }

    override fun close() {
        state = ChannelState.OFFLINE
        writableChannel.close()
        socket.close()
    }

    companion object {
        const val MAX_CHUNK_SIZE: Int = 1_024 * 1_024

        fun configureSocket(socket: Socket) {
            socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
            socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 5)
            socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, 2)
            socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, 3)
        }
    }
}
