package com.fluxsync.desktop.data.network

import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkPacketCodec
import com.fluxsync.core.protocol.ChunkSizeNegotiator
import com.fluxsync.core.protocol.ConsentRequestPacket
import com.fluxsync.core.protocol.ConsentResponsePacket
import com.fluxsync.core.protocol.ControlPacketIO
import com.fluxsync.core.protocol.FileEntry
import com.fluxsync.core.protocol.FileManifest
import com.fluxsync.core.protocol.HandshakePacket
import com.fluxsync.core.protocol.SessionCancelPacket
import com.fluxsync.core.protocol.SessionCompletePacket
import com.fluxsync.core.security.CertificateManager
import com.fluxsync.core.security.DeviceCertificate
import com.fluxsync.core.security.PairingResult
import com.fluxsync.core.security.TofuPairingCoordinator
import com.fluxsync.core.transfer.SessionState
import com.fluxsync.core.transfer.SessionStateMachine
import com.fluxsync.core.transfer.TransferViewModel
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.logging.Logger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop-side sender session. Orchestrates the full TCP → TLS → pairing → handshake → consent →
 * file-streaming flow as a suspend function.
 *
 * Call [run] from a coroutine scoped to the ViewModel; cancelling that scope tears the session down
 * cleanly.
 */
class DesktopTransferSession(
        private val targetIp: String,
        private val targetPort: Int,
        private val targetDeviceName: String,
        private val files: List<File>,
        private val cert: DeviceCertificate,
        private val certManager: CertificateManager,
        private val pairingCoordinator: TofuPairingCoordinator,
        private val sessionMachine: SessionStateMachine,
        private val transferViewModel: TransferViewModel,
) {
    private var socket: Socket? = null

    suspend fun run() =
            withContext(Dispatchers.IO) {
                try {
                    // ── 1. Connect ────────────────────────────────────────────────
                    sessionMachine.transition(SessionState.CONNECTING)
                    logger.info("Connecting to $targetDeviceName at $targetIp:$targetPort")

                    val rawSocket = Socket()
                    rawSocket.soTimeout = 0
                    rawSocket.keepAlive = true
                    rawSocket.connect(InetSocketAddress(targetIp, targetPort), CONNECT_TIMEOUT_MS)
                    socket = rawSocket

                    logger.info("TCP connected to $targetIp:$targetPort")

                    // ── 2. TLS handshake ──────────────────────────────────────────
                    val sslContext = certManager.buildSslContext(cert, acceptAllPeers = true)
                    val sslSocketFactory = sslContext.socketFactory
                    val sslSocket =
                            sslSocketFactory.createSocket(
                                    rawSocket,
                                    targetIp,
                                    targetPort,
                                    true // autoClose underlying socket
                            ) as
                                    SSLSocket
                    sslSocket.useClientMode = true
                    sslSocket.startHandshake()

                    logger.info("TLS handshake complete — cipher: ${sslSocket.session.cipherSuite}")
                    socket = sslSocket

                    // ── 3. Protocol handshake ─────────────────────────────────────
                    sessionMachine.transition(SessionState.HANDSHAKING)

                    val myHandshake =
                            HandshakePacket(
                                    protocolVersion = PROTOCOL_VERSION,
                                    deviceName = System.getProperty("user.name") ?: "Desktop",
                                    certFingerprint = cert.sha256Fingerprint,
                                    maxChunkSizeBytes = ChunkSizeNegotiator.NORMAL,
                                    availableMemoryMb =
                                            (Runtime.getRuntime().maxMemory() / (1024 * 1024))
                                                    .toInt(),
                            )
                    ControlPacketIO.writePacket(sslSocket.outputStream, myHandshake)

                    val peerHandshake =
                            ControlPacketIO.readTyped<HandshakePacket>(sslSocket.inputStream)
                    logger.info(
                            "Peer handshake: ${peerHandshake.deviceName}, protocol v${peerHandshake.protocolVersion}"
                    )

                    // ── 4. TOFU Pairing ───────────────────────────────────────────
                    if (!pairingCoordinator.isKnownPeer(sslSocket)) {
                        sessionMachine.transition(SessionState.PAIRING)
                        logger.info("Peer not trusted — starting client pairing")

                        when (val result = pairingCoordinator.runClientPairing(sslSocket)) {
                            is PairingResult.Success ->
                                    logger.info(
                                            "Pairing succeeded, peer fingerprint: ${result.peerFingerprint}"
                                    )
                            is PairingResult.PinMismatch -> {
                                logger.warning("Pairing failed: PIN mismatch")
                                sessionMachine.cancel("PIN mismatch")
                                return@withContext
                            }
                            is PairingResult.Timeout -> {
                                logger.warning("Pairing failed: timeout")
                                sessionMachine.cancel("Pairing timeout")
                                return@withContext
                            }
                            is PairingResult.Error -> {
                                logger.warning("Pairing failed: ${result.cause.message}")
                                sessionMachine.cancel("Pairing error: ${result.cause.message}")
                                return@withContext
                            }
                        }
                    } else {
                        logger.info("Peer already trusted — skipping pairing")
                    }

                    // ── 5. Build manifest & send consent request ──────────────────
                    val sessionId = System.currentTimeMillis()
                    val chunkSize = ChunkSizeNegotiator.NORMAL // 256 KB

                    val fileEntries =
                            files.mapIndexed { index, file ->
                                val totalChunks =
                                        ((file.length() + chunkSize - 1) / chunkSize).toInt()
                                FileEntry(
                                        fileId = index,
                                        name = file.name,
                                        sizeBytes = file.length(),
                                        totalChunks = totalChunks,
                                        negotiatedChunkSizeBytes = chunkSize,
                                )
                            }

                    val manifest = FileManifest(sessionId = sessionId, files = fileEntries)
                    val consentRequest =
                            ConsentRequestPacket(sessionId = sessionId, manifest = manifest)
                    ControlPacketIO.writePacket(sslSocket.outputStream, consentRequest)
                    logger.info("Consent request sent: ${files.size} file(s)")

                    // ── 6. Wait for consent response ──────────────────────────────
                    sessionMachine.transition(SessionState.PENDING_CONSENT)
                    transferViewModel.updatePendingConsentInfo(
                            deviceName = peerHandshake.deviceName,
                            fileSummary =
                                    "${files.size} file(s), ${formatBytes(files.sumOf { it.length() })}"
                    )

                    val consentResponse =
                            ControlPacketIO.readTyped<ConsentResponsePacket>(sslSocket.inputStream)
                    if (!consentResponse.accepted) {
                        logger.info("Consent declined by $targetDeviceName")
                        sessionMachine.cancel("Consent declined")
                        return@withContext
                    }
                    logger.info("Consent accepted by $targetDeviceName")

                    // ── 7. Transfer ───────────────────────────────────────────────
                    sessionMachine.transition(SessionState.TRANSFERRING)

                    // Register files with the TransferViewModel for progress tracking
                    transferViewModel.onFilesDropped(files)

                    streamAllFiles(
                            sslSocket = sslSocket,
                            sessionId = sessionId,
                            chunkSize = chunkSize,
                            fileEntries = fileEntries,
                    )

                    // ── 8. Session complete ───────────────────────────────────────
                    ControlPacketIO.writePacket(
                            sslSocket.outputStream,
                            SessionCompletePacket(sessionId)
                    )
                    sessionMachine.complete()
                    logger.info("Transfer session $sessionId completed successfully")
                } catch (e: CancellationException) {
                    logger.info("Session cancelled")
                    trySendCancel()
                    throw e
                } catch (e: Exception) {
                    logger.warning("Session failed: ${e.message}")
                    trySendCancel()
                    try {
                        sessionMachine.cancel("Error: ${e.message}")
                    } catch (_: Exception) {}
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Exception) {}
                }
            }

    fun cancel() {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    private fun trySendCancel() {
        try {
            val s = socket ?: return
            if (!s.isClosed) {
                ControlPacketIO.writePacket(
                        s.outputStream,
                        SessionCancelPacket(sessionId = 0, reason = "Cancelled")
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun streamAllFiles(
            sslSocket: SSLSocket,
            sessionId: Long,
            chunkSize: Int,
            fileEntries: List<FileEntry>,
    ) {
        val out = sslSocket.outputStream
        // Allocate ONE direct buffer for the entire transfer
        val writeBuffer =
                ByteBuffer.allocateDirect(ChunkPacketCodec.HEADER_SIZE + chunkSize)
                        .order(ByteOrder.BIG_ENDIAN)
        val readBuffer = ByteBuffer.allocateDirect(chunkSize)

        for ((index, file) in files.withIndex()) {
            val entry = fileEntries[index]
            logger.info(
                    "Streaming file ${entry.name} (${formatBytes(file.length())}, ${entry.totalChunks} chunks)"
            )

            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
                var bytesSent = 0L
                for (chunkIndex in 0 until entry.totalChunks) {
                    val offset = chunkIndex.toLong() * chunkSize

                    readBuffer.clear()
                    val bytesRead = fc.read(readBuffer, offset)
                    if (bytesRead <= 0) break // End of file or error

                    readBuffer.flip()

                    val payload = ByteArray(bytesRead)
                    readBuffer.get(payload)

                    val checksum = com.fluxsync.core.protocol.CRC32Helper.compute(payload)

                    val chunk =
                            ChunkPacket(
                                    sessionId = sessionId,
                                    fileId = entry.fileId,
                                    chunkIndex = chunkIndex,
                                    offset = offset,
                                    payloadLength = payload.size,
                                    checksum = checksum,
                                    payload = payload,
                            )

                    // Write to wire
                    writeBuffer.clear()
                    chunk.writeTo(writeBuffer)
                    writeBuffer.flip()

                    val wireBytes = ByteArray(writeBuffer.remaining())
                    writeBuffer.get(wireBytes)
                    out.write(wireBytes)

                    bytesSent += payload.size
                    transferViewModel.updateFileProgress(entry.fileId, bytesSent, file.length())
                }

                out.flush()
                transferViewModel.markFileComplete(entry.fileId)
                logger.info("File ${entry.name} sent (${formatBytes(bytesSent)})")
            }
        }
    }

    companion object {
        private val logger = Logger.getLogger("DesktopTransferSession")
        private const val PROTOCOL_VERSION = 1
        private const val CONNECT_TIMEOUT_MS = 10_000

        private fun formatBytes(bytes: Long): String =
                when {
                    bytes < 1024 -> "${bytes}B"
                    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
                    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
                    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))}GB"
                }
    }
}
