package com.fluxsync.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.SocketAddress
import java.security.Principal
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TofuPairingCoordinatorTest {

    @Test
    fun `server pairing succeeds and persists trusted peer`() = runTest {
        val trustStore = InMemoryTrustStore()
        var displayedPin = ""
        val warnings = mutableListOf<Unit>()
        val cert = FakeX509Certificate(byteArrayOf(0x01, 0x02, 0x03))

        val coordinator = TofuPairingCoordinator(
            trustStore = trustStore,
            onDisplayPin = { displayedPin = it },
            onRequestPin = { "unused" },
            onSoftwareCipherWarning = { warnings += Unit }
        )

        val socket = FakeSSLSocket(
            input = PinInputStream { "$displayedPin\n" },
            session = FakeSSLSession(
                cipherSuite = "TLS_AES_128_GCM_SHA256",
                peerCertificates = arrayOf(cert),
                peerPrincipal = Principal { "CN=Desktop Peer" }
            )
        )

        val result = coordinator.runServerPairing(socket)

        val success = assertIs<PairingResult.Success>(result)
        assertEquals(1, trustStore.getAll().size)
        assertEquals(success.peerFingerprint, trustStore.getAll().first().fingerprint)
        assertEquals("CN=Desktop Peer", trustStore.getAll().first().deviceName)
        assertEquals(4, displayedPin.length)
        assertTrue(displayedPin.all(Char::isDigit))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `server pairing returns PinMismatch for wrong pin`() = runTest {
        val trustStore = InMemoryTrustStore()
        var displayedPin = ""
        val coordinator = TofuPairingCoordinator(
            trustStore = trustStore,
            onDisplayPin = { displayedPin = it },
            onRequestPin = { "unused" },
            onSoftwareCipherWarning = {}
        )

        val socket = FakeSSLSocket(
            input = ByteArrayInputStream("9999\n".toByteArray()),
            session = FakeSSLSession(peerCertificates = arrayOf(FakeX509Certificate(byteArrayOf(0x05))))
        )

        val result = coordinator.runServerPairing(socket)

        assertEquals(PairingResult.PinMismatch, result)
        assertTrue(displayedPin.length == 4)
        assertTrue(trustStore.getAll().isEmpty())
    }

    @Test
    fun `client pairing sends entered pin and saves peer fingerprint`() = runTest {
        val trustStore = InMemoryTrustStore()
        val output = ByteArrayOutputStream()
        var warningCount = 0
        val coordinator = TofuPairingCoordinator(
            trustStore = trustStore,
            onDisplayPin = {},
            onRequestPin = { "1234" },
            onSoftwareCipherWarning = { warningCount++ }
        )

        val socket = FakeSSLSocket(
            output = output,
            session = FakeSSLSession(
                cipherSuite = "TLS_CHACHA20_POLY1305_SHA256",
                peerCertificates = arrayOf(FakeX509Certificate(byteArrayOf(0x0A, 0x0B))),
                peerPrincipal = Principal { "CN=Android Peer" }
            )
        )

        val result = coordinator.runClientPairing(socket)

        assertIs<PairingResult.Success>(result)
        assertEquals("1234\n", output.toString())
        assertEquals(1, trustStore.getAll().size)
        assertEquals("CN=Android Peer", trustStore.getAll().first().deviceName)
        assertEquals(1, warningCount)
    }

    @Test
    fun `client pairing times out when user does not provide pin`() = runTest {
        val coordinator = TofuPairingCoordinator(
            trustStore = InMemoryTrustStore(),
            onDisplayPin = {},
            onRequestPin = {
                delay(61_000)
                "0000"
            },
            onSoftwareCipherWarning = {}
        )

        val socket = FakeSSLSocket(
            session = FakeSSLSession(peerCertificates = arrayOf(FakeX509Certificate(byteArrayOf(0x01))))
        )

        val result = coordinator.runClientPairing(socket)

        assertEquals(PairingResult.Timeout, result)
    }

    @Test
    fun `isKnownPeer returns trust status from trust store`() {
        val trustStore = InMemoryTrustStore()
        val cert = FakeX509Certificate(byteArrayOf(0x55))
        val socket = FakeSSLSocket(
            session = FakeSSLSession(peerCertificates = arrayOf(cert))
        )

        val coordinator = TofuPairingCoordinator(
            trustStore = trustStore,
            onDisplayPin = {},
            onRequestPin = { "0000" },
            onSoftwareCipherWarning = {}
        )

        assertFalse(coordinator.isKnownPeer(socket))

        val fingerprint = CertificateManager().computeFingerprint(cert)
        trustStore.save(TrustedDevice(fingerprint = fingerprint, deviceName = "known", lastSeenMs = 1L))

        assertTrue(coordinator.isKnownPeer(socket))
    }


    private class InMemoryTrustStore : TrustStore {
        private val devices = mutableMapOf<String, TrustedDevice>()

        override fun isTrusted(fingerprint: String): Boolean = devices[fingerprint]?.trusted == true

        override fun save(device: TrustedDevice) {
            devices[device.fingerprint] = device
        }

        override fun getAll(): List<TrustedDevice> = devices.values.toList()

        override fun revoke(fingerprint: String) {
            devices.remove(fingerprint)
        }
    }

    private class FakeSSLSocket(
        input: InputStream = ByteArrayInputStream(ByteArray(0)),
        private var output: OutputStream = ByteArrayOutputStream(),
        private val session: SSLSession,
    ) : SSLSocket() {
        private var input: InputStream = input

        override fun getSession(): SSLSession = session
        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun startHandshake() = Unit
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation(): Boolean = true
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun bind(bindpoint: SocketAddress?) = Unit
        override fun connect(endpoint: SocketAddress?) = Unit
        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun getInetAddress(): InetAddress? = null
        override fun getLocalAddress(): InetAddress? = null
        override fun getPort(): Int = 0
        override fun getLocalPort(): Int = 0
        override fun getRemoteSocketAddress(): SocketAddress? = null
        override fun getLocalSocketAddress(): SocketAddress? = null
        override fun isConnected(): Boolean = true
        override fun isBound(): Boolean = true
        override fun isClosed(): Boolean = false
        override fun isInputShutdown(): Boolean = false
        override fun isOutputShutdown(): Boolean = false
        override fun shutdownInput() = Unit
        override fun shutdownOutput() = Unit
        override fun close() = Unit
    }

    private class PinInputStream(private val pinProvider: () -> String) : InputStream() {
        private var delegate: ByteArrayInputStream? = null

        override fun read(): Int {
            if (delegate == null) {
                delegate = ByteArrayInputStream(pinProvider().toByteArray())
            }
            return delegate!!.read()
        }
    }

    private class FakeSSLSession(
        private val cipherSuite: String = "TLS_AES_128_GCM_SHA256",
        private val peerCertificates: Array<Certificate>,
        private val peerPrincipal: Principal? = null,
    ) : SSLSession {
        override fun getCipherSuite(): String = cipherSuite
        override fun getPeerCertificates(): Array<Certificate> = peerCertificates
        override fun getPeerPrincipal(): Principal = peerPrincipal ?: Principal { "CN=Unknown" }

        override fun getApplicationBufferSize(): Int = 0
        override fun getCreationTime(): Long = 0
        override fun getId(): ByteArray = byteArrayOf()
        override fun getLastAccessedTime(): Long = 0
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getLocalPrincipal(): Principal? = null
        override fun getPacketBufferSize(): Int = 0
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> = emptyArray()
        override fun getPeerHost(): String = ""
        override fun getPeerPort(): Int = 0
        override fun getProtocol(): String = "TLS"
        override fun getSessionContext(): SSLSessionContext? = null
        override fun getValue(name: String?) = null
        override fun getValueNames(): Array<String> = emptyArray()
        override fun invalidate() = Unit
        override fun isValid(): Boolean = true
        override fun putValue(name: String?, value: Any?) = Unit
        override fun removeValue(name: String?) = Unit
    }

    private class FakeX509Certificate(private val certBytes: ByteArray) : X509Certificate() {
        override fun getEncoded(): ByteArray = certBytes
        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion(): Int = 3
        override fun getSerialNumber(): BigInteger = BigInteger.ONE
        override fun getIssuerDN(): Principal = Principal { "CN=issuer" }
        override fun getSubjectDN(): Principal = Principal { "CN=subject" }
        override fun getNotBefore(): Date = Date()
        override fun getNotAfter(): Date = Date()
        override fun getTBSCertificate(): ByteArray = certBytes
        override fun getSignature(): ByteArray = byteArrayOf()
        override fun getSigAlgName(): String = ""
        override fun getSigAlgOID(): String = ""
        override fun getSigAlgParams(): ByteArray = byteArrayOf()
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints(): Int = -1
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString(): String = "FakeX509Certificate"
        override fun getPublicKey(): PublicKey = object : PublicKey {
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String = "X.509"
            override fun getEncoded(): ByteArray = byteArrayOf()
        }

        override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
        override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
        override fun hasUnsupportedCriticalExtension(): Boolean = false
    }
}
