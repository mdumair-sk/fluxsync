package com.fluxsync.core.security

import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

sealed class PairingResult {
    data class Success(val peerFingerprint: String) : PairingResult()
    data object PinMismatch : PairingResult()
    data object Timeout : PairingResult()
    data class Error(val cause: Exception) : PairingResult()
}

class TofuPairingCoordinator(
    private val trustStore: TrustStore,
    private val onDisplayPin: (pin: String) -> Unit,
    private val onRequestPin: suspend () -> String,
    private val onSoftwareCipherWarning: () -> Unit
) {
    private val certificateManager = CertificateManager()

    suspend fun runServerPairing(sslSocket: SSLSocket): PairingResult {
        maybeWarnForSoftwareCipher(sslSocket)
        val expectedPin = generatePin()
        onDisplayPin(expectedPin)

        return try {
            val receivedPin = withTimeout(PAIRING_TIMEOUT_MS) {
                readUtf8Line(sslSocket).orEmpty().trim()
            }

            if (receivedPin != expectedPin) {
                PairingResult.PinMismatch
            } else {
                val fingerprint = persistTrustedPeer(sslSocket)
                PairingResult.Success(fingerprint)
            }
        } catch (_: TimeoutCancellationException) {
            PairingResult.Timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            PairingResult.Error(e)
        }
    }

    suspend fun runClientPairing(sslSocket: SSLSocket): PairingResult {
        maybeWarnForSoftwareCipher(sslSocket)

        return try {
            val pin = withTimeout(PAIRING_TIMEOUT_MS) {
                onRequestPin().trim()
            }

            withTimeout(PAIRING_TIMEOUT_MS) {
                writeUtf8Line(sslSocket, pin)
            }

            val fingerprint = persistTrustedPeer(sslSocket)
            PairingResult.Success(fingerprint)
        } catch (_: TimeoutCancellationException) {
            PairingResult.Timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            PairingResult.Error(e)
        }
    }

    fun isKnownPeer(sslSocket: SSLSocket): Boolean {
        val fingerprint = peerFingerprint(sslSocket)
        return trustStore.isTrusted(fingerprint)
    }

    private fun persistTrustedPeer(sslSocket: SSLSocket): String {
        val fingerprint = peerFingerprint(sslSocket)
        trustStore.save(
            TrustedDevice(
                fingerprint = fingerprint,
                deviceName = sslSocket.session.peerPrincipal?.name ?: "Unknown Device",
                lastSeenMs = System.currentTimeMillis(),
            )
        )
        return fingerprint
    }

    private fun peerFingerprint(sslSocket: SSLSocket): String {
        val peerCert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: error("Peer certificate is missing or not an X509 certificate")
        return certificateManager.computeFingerprint(peerCert)
    }

    private fun maybeWarnForSoftwareCipher(sslSocket: SSLSocket) {
        val cipher = sslSocket.session.cipherSuite.orEmpty()
        if (cipher.contains("CHACHA20", ignoreCase = true)) {
            onSoftwareCipherWarning()
        }
    }

    private suspend fun readUtf8Line(sslSocket: SSLSocket): String? = withContext(Dispatchers.IO) {
        sslSocket.inputStream.bufferedReader(StandardCharsets.UTF_8).readLine()
    }

    private suspend fun writeUtf8Line(sslSocket: SSLSocket, line: String) = withContext(Dispatchers.IO) {
        val writer = sslSocket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        writer.write(line)
        writer.newLine()
        writer.flush()
    }

    private fun generatePin(): String = Random.nextInt(PIN_RANGE_UPPER_BOUND).toString().padStart(PIN_LENGTH, '0')

    private companion object {
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val PIN_LENGTH = 4
        const val PIN_RANGE_UPPER_BOUND = 10_000
    }
}
