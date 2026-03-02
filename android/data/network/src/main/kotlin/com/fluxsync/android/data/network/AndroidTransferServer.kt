package com.fluxsync.android.data.network

import android.util.Log
import com.fluxsync.core.protocol.ChunkSizeNegotiator
import com.fluxsync.core.protocol.ConsentRequestPacket
import com.fluxsync.core.protocol.ConsentResponsePacket
import com.fluxsync.core.protocol.ControlPacketIO
import com.fluxsync.core.protocol.HandshakePacket
import com.fluxsync.core.protocol.SessionCancelPacket
import com.fluxsync.core.protocol.SessionCompletePacket
import com.fluxsync.core.resumability.FluxPartDebouncer
import com.fluxsync.core.security.CertificateManager
import com.fluxsync.core.security.DeviceCertificate
import com.fluxsync.core.security.PairingResult
import com.fluxsync.core.security.TofuPairingCoordinator
import com.fluxsync.core.transfer.ChunkAssembler
import com.fluxsync.core.transfer.ChunkReceiver
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Data class representing an incoming transfer request waiting for consent. */
data class IncomingTransferRequest(
        val senderName: String,
        val fileCount: Int,
        val totalSizeBytes: Long,
        val totalSizeFormatted: String,
        val consentDeferred: CompletableDeferred<Boolean>,
)

/**
 * Android-side TCP server that listens for incoming desktop connections.
 *
 * Lifecycle:
 * 1. [start] opens a `ServerSocket` on port 5001 (TLS wrapped)
 * 2. Each accepted connection runs the reverse of [DesktopTransferSession]:
 * ```
 *    handshake → pairing → consent → chunk receive → file assembly
 * ```
 * 3. [stop] tears down the server and any active sessions
 */
class AndroidTransferServer(
        private val cert: DeviceCertificate,
        private val certManager: CertificateManager,
        private val pairingCoordinator: TofuPairingCoordinator,
        private val dropZoneDir: File,
        private val scope: CoroutineScope,
) {
    private var serverJob: Job? = null
    private var sessionJob: Job? = null
    private var serverSocket: ServerSocket? = null

    private val _incomingRequests =
            MutableSharedFlow<IncomingTransferRequest>(extraBufferCapacity = 1)
    val incomingRequests: SharedFlow<IncomingTransferRequest> = _incomingRequests

    private val _sessionStatus = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sessionStatus: SharedFlow<String> = _sessionStatus

    private val _boundPort = MutableStateFlow<Int?>(null)
    val boundPort: StateFlow<Int?> = _boundPort.asStateFlow()

    fun start() {
        if (serverJob != null) return

        serverJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        val sslContext = certManager.buildSslContext(cert, acceptAllPeers = true)
                        val sslServerSocketFactory = sslContext.serverSocketFactory

                        var foundSocket: SSLServerSocket? = null
                        var boundPortValue = PORT

                        // Try a range of ports (5001-5099) to handle collisions or weird environment blocks
                        for (p in PORT until PORT + 100) {
                            if (!isActive) return@launch
                            try {
                                foundSocket =
                                        sslServerSocketFactory.createServerSocket(p)
                                                as SSLServerSocket
                                boundPortValue = p
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to bind to port $p: ${e.message}")
                            }
                        }

                        val rawServerSocket =
                                foundSocket
                                        ?: throw IllegalStateException(
                                                "Could not bind to any port in range $PORT..${PORT+99}"
                                        )

                        rawServerSocket.needClientAuth = false
                        rawServerSocket.wantClientAuth = true
                        serverSocket = rawServerSocket
                        _boundPort.value = boundPortValue

                        Log.i(TAG, "Transfer server started on port $boundPortValue")

                        while (isActive) {
                            try {
                                val sslSocket = rawServerSocket.accept() as SSLSocket
                                sslSocket.soTimeout = 0
                                sslSocket.keepAlive = true

                                Log.i(
                                        TAG,
                                        "Incoming connection from ${sslSocket.inetAddress.hostAddress}"
                                )

                                // Cancel any existing session — one at a time
                                sessionJob?.cancelAndJoin()
                                sessionJob = launch { handleSession(sslSocket) }
                            } catch (e: Exception) {
                                if (isActive) {
                                    Log.w(TAG, "Accept failed: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Server fatal error: ${e.message}", e)
                        _sessionStatus.tryEmit("Server error: ${e.message}")
                    } finally {
                        _boundPort.value = null
                    }
                }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        sessionJob?.cancel()
        sessionJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Transfer server stopped")
    }

    private suspend fun handleSession(sslSocket: SSLSocket) {
        try {
            sslSocket.use { socket ->
                // ── 1. TLS handshake (automatic on first I/O) ──────────────
                socket.startHandshake()
                Log.i(TAG, "TLS handshake complete — cipher: ${socket.session.cipherSuite}")

                // ── 2. Protocol handshake ──────────────────────────────────
                val peerHandshake = ControlPacketIO.readTyped<HandshakePacket>(socket.inputStream)
                Log.i(
                        TAG,
                        "Peer handshake: ${peerHandshake.deviceName}, v${peerHandshake.protocolVersion}"
                )

                val myHandshake =
                        HandshakePacket(
                                protocolVersion = PROTOCOL_VERSION,
                                deviceName = android.os.Build.MODEL,
                                certFingerprint = cert.sha256Fingerprint,
                                maxChunkSizeBytes = ChunkSizeNegotiator.NORMAL,
                                availableMemoryMb =
                                        (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt(),
                        )
                ControlPacketIO.writePacket(socket.outputStream, myHandshake)

                // ── 3. TOFU pairing ───────────────────────────────────────
                if (!pairingCoordinator.isKnownPeer(socket)) {
                    Log.i(TAG, "Peer not trusted — starting server pairing")
                    _sessionStatus.tryEmit("Pairing with ${peerHandshake.deviceName}…")

                    when (val result = pairingCoordinator.runServerPairing(socket)) {
                        is PairingResult.Success ->
                                Log.i(TAG, "Pairing succeeded: ${result.peerFingerprint}")
                        is PairingResult.PinMismatch -> {
                            Log.w(TAG, "Pairing failed: PIN mismatch")
                            _sessionStatus.tryEmit("Pairing failed: PIN mismatch")
                            return
                        }
                        is PairingResult.Timeout -> {
                            Log.w(TAG, "Pairing failed: timeout")
                            _sessionStatus.tryEmit("Pairing timeout")
                            return
                        }
                        is PairingResult.Error -> {
                            Log.w(TAG, "Pairing error: ${result.cause.message}")
                            _sessionStatus.tryEmit("Pairing error")
                            return
                        }
                    }
                } else {
                    Log.i(TAG, "Peer already trusted — skipping pairing")
                }

                // ── 4. Read consent request ───────────────────────────────
                val consentRequest =
                        ControlPacketIO.readTyped<ConsentRequestPacket>(socket.inputStream)
                val manifest = consentRequest.manifest
                val totalSize = manifest.files.sumOf { it.sizeBytes }

                Log.i(
                        TAG,
                        "Consent request: ${manifest.files.size} file(s), ${formatBytes(totalSize)}"
                )

                // ── 5. Prompt user for consent ────────────────────────────
                val consentDeferred = CompletableDeferred<Boolean>()
                val request =
                        IncomingTransferRequest(
                                senderName = peerHandshake.deviceName,
                                fileCount = manifest.files.size,
                                totalSizeBytes = totalSize,
                                totalSizeFormatted = formatBytes(totalSize),
                                consentDeferred = consentDeferred,
                        )
                _incomingRequests.tryEmit(request)
                _sessionStatus.tryEmit("Waiting for consent…")

                val accepted = consentDeferred.await()
                ControlPacketIO.writePacket(
                        socket.outputStream,
                        ConsentResponsePacket(
                                sessionId = consentRequest.sessionId,
                                accepted = accepted,
                        ),
                )

                if (!accepted) {
                    Log.i(TAG, "Consent declined by user")
                    _sessionStatus.tryEmit("Transfer declined")
                    return
                }

                Log.i(TAG, "Consent accepted — starting file receive")
                _sessionStatus.tryEmit("Receiving files…")

                // ── 6. Receive chunks ─────────────────────────────────────
                val chunkSize = ChunkSizeNegotiator.NORMAL

                for (fileEntry in manifest.files) {
                    val targetFile = File(dropZoneDir, fileEntry.name + ".fluxdownload")
                    val finalFile = File(dropZoneDir, fileEntry.name)

                    Log.i(
                            TAG,
                            "Receiving ${fileEntry.name} (${formatBytes(fileEntry.sizeBytes)}, ${fileEntry.totalChunks} chunks)"
                    )

                    val raf = RandomAccessFile(targetFile, "rw")
                    raf.setLength(fileEntry.sizeBytes)

                    val fluxPartFile = File(dropZoneDir, fileEntry.name + ".fluxpart")

                    val fluxPartDebouncer =
                            FluxPartDebouncer(
                                    fluxPartFile = fluxPartFile,
                                    scope = scope,
                            )

                    val assembler =
                            ChunkAssembler(
                                    targetFile = raf,
                                    totalChunks = fileEntry.totalChunks,
                                    resumeState = null,
                                    fluxPartDebouncer = fluxPartDebouncer,
                                    onRetryRequired = { failedChunks ->
                                        Log.w(TAG, "Retry required for chunks: $failedChunks")
                                    },
                                    onFileComplete = {
                                        Log.i(TAG, "File assembly complete: ${fileEntry.name}")
                                    },
                                    onFileFailed = { reason ->
                                        Log.e(TAG, "File assembly failed: $reason")
                                    },
                            )

                    val receiver =
                            ChunkReceiver(
                                    assembler = assembler,
                                    chunkSizeBytes = chunkSize,
                            )

                    // Run socket read and chunk assembly in parallel
                    val readJob =
                            scope.launch(Dispatchers.IO) {
                                try {
                                    receiver.socketReadLoop(socket.inputStream)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Socket read ended: ${e.message}")
                                } finally {
                                    receiver.close()
                                }
                            }

                    val assemblyJob = scope.launch(Dispatchers.IO) { receiver.assemblyLoop() }

                    assemblyJob.join()
                    readJob.cancelAndJoin()

                    // Close the RandomAccessFile
                    raf.close()

                    // Atomic rename .fluxdownload → final name
                    if (targetFile.renameTo(finalFile)) {
                        Log.i(TAG, "File saved: ${finalFile.name}")
                        // Clean up .fluxpart
                        fluxPartFile.delete()
                    } else {
                        Log.w(TAG, "Failed to rename ${targetFile.name} to ${finalFile.name}")
                    }
                }

                // ── 7. Read session complete ──────────────────────────────
                try {
                    val completionPacket = ControlPacketIO.readPacket(socket.inputStream)
                    when (completionPacket) {
                        is SessionCompletePacket ->
                                Log.i(
                                        TAG,
                                        "Session ${completionPacket.sessionId} completed successfully"
                                )
                        is SessionCancelPacket ->
                                Log.w(TAG, "Session cancelled: ${completionPacket.reason}")
                        else ->
                                Log.w(
                                        TAG,
                                        "Unexpected packet after transfer: ${completionPacket::class.simpleName}"
                                )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read session completion: ${e.message}")
                }

                _sessionStatus.tryEmit("Transfer complete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session error: ${e.message}", e)
            _sessionStatus.tryEmit("Transfer error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AndroidTransferServer"
        private const val PORT = 5001
        private const val PROTOCOL_VERSION = 1

        private fun formatBytes(bytes: Long): String =
                when {
                    bytes < 1024 -> "${bytes}B"
                    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
                    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
                }
    }
}
