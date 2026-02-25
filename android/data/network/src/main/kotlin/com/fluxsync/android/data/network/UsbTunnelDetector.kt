package com.fluxsync.android.data.network

import android.util.Log
import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.NetworkChannel
import com.fluxsync.core.transfer.DRTLB
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class TunnelStatus {
    ACTIVE,
    PORT_COLLISION,
    PORT_FREE_NO_TUNNEL,
}

class UsbTunnelDetector(
        private val drtlb: DRTLB,
        private val usbChannel: NetworkChannel,
        private val onPortCollision: () -> Unit,
        private val forceWifiOnly: () -> Boolean,
) {
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            if (!forceWifiOnly()) {
                when (verifyTunnelBinding(PORT)) {
                    TunnelStatus.ACTIVE -> {
                        if (usbChannel.state != ChannelState.ACTIVE) {
                            Log.i(
                                    TAG,
                                    "USB tunnel detected on port=$PORT. Registering USB channel=${usbChannel.id}"
                            )
                            usbChannel.state = ChannelState.ACTIVE
                            drtlb.registerChannel(usbChannel)
                        }
                    }
                    TunnelStatus.PORT_FREE_NO_TUNNEL -> {
                        if (usbChannel.state == ChannelState.ACTIVE) {
                            Log.w(
                                    TAG,
                                    "USB tunnel no longer reachable on port=$PORT. Removing USB channel=${usbChannel.id}"
                            )
                            usbChannel.state = ChannelState.OFFLINE
                            drtlb.removeChannel(usbChannel)
                        }
                    }
                    TunnelStatus.PORT_COLLISION -> {
                        Log.e(
                                TAG,
                                "Port collision detected on $PORT. Non-FluxSync service is responding"
                        )
                        onPortCollision()
                    }
                }
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private fun isPortReachable(
            host: String,
            port: Int,
            timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): Boolean {
        return runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, port), timeoutMs)
                        true
                    }
                }
                .getOrDefault(false)
    }

    private fun verifyTunnelBinding(port: Int): TunnelStatus {
        if (!isPortReachable(LOCALHOST, port)) {
            return TunnelStatus.PORT_FREE_NO_TUNNEL
        }

        return if (isFluxSyncResponding(port)) {
            TunnelStatus.ACTIVE
        } else {
            TunnelStatus.PORT_COLLISION
        }
    }

    private fun isFluxSyncResponding(port: Int): Boolean {
        return runCatching {
                    Socket().use { socket ->
                        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                        socket.connect(InetSocketAddress(LOCALHOST, port), HANDSHAKE_TIMEOUT_MS)

                        socket.getOutputStream().write(PROBE_BYTE)
                        socket.getOutputStream().flush()

                        socket.getInputStream().read() == EXPECTED_PONG
                    }
                }
                .getOrDefault(false)
    }

    private companion object {
        const val TAG = "UsbTunnelDetector"
        const val LOCALHOST = "127.0.0.1"
        const val PORT = 5002
        const val POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 500
        const val HANDSHAKE_TIMEOUT_MS = 1_000
        const val PROBE_BYTE = 0x0F
        const val EXPECTED_PONG = 0xF0
    }
}
