package com.fluxsync.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

private const val TAG = "CertificateManager"
private const val KEYSTORE_TYPE = "AndroidKeyStore"
private const val DEFAULT_ALIAS_PREFIX = "fluxsync_device_"

actual class CertificateManager actual constructor() {

    private var onSoftwareCipherDetected: () -> Unit = {}

    constructor(onSoftwareCipherDetected: () -> Unit) : this() {
        this.onSoftwareCipherDetected = onSoftwareCipherDetected
    }

    actual fun getOrCreateCertificate(deviceName: String): DeviceCertificate {
        val alias = buildAlias(deviceName)
        val keyStore = loadAndroidKeyStore()

        if (!keyStore.containsAlias(alias)) {
            generateKeyPair(alias)
        }

        val cert = keyStore.getCertificate(alias) as? X509Certificate
            ?: error("Android Keystore alias '$alias' exists but has no X509Certificate")

        return DeviceCertificate(
            alias = alias,
            sha256Fingerprint = computeFingerprint(cert),
            pemEncoded = cert.toPem()
        )
    }

    actual fun buildSslContext(cert: DeviceCertificate, acceptAllPeers: Boolean): SSLContext {
        val keyStore = loadAndroidKeyStore()
        val keyEntry = keyStore.getEntry(cert.alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("No private key entry found in Android Keystore for alias '${cert.alias}'")

        val identityStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry(
                cert.alias,
                keyEntry.privateKey,
                CharArray(0),
                keyEntry.certificateChain
            )
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(identityStore, CharArray(0))

        val trustManagers = if (acceptAllPeers) {
            arrayOf(buildTrustAllManager())
        } else {
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(null as KeyStore?)
            wrapWithCipherInspection(trustManagerFactory.trustManagers)
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagers, SecureRandom())
        }
    }

    actual fun computeFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun generateKeyPair(alias: String) {
        val now = System.currentTimeMillis()
        val validityStart = Date(now)
        val validityEnd = Date(now + TEN_YEARS_MS)

        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY or KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setAlgorithmParameterSpec(java.security.spec.RSAKeyGenParameterSpec(2048, java.security.spec.RSAKeyGenParameterSpec.F4))
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512
            )
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                KeyProperties.ENCRYPTION_PADDING_RSA_OAEP
            )
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS
            )
            .setCertificateSubject(X500Principal("CN=$alias"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(validityStart)
            .setCertificateNotAfter(validityEnd)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_TYPE).apply {
            initialize(parameterSpec)
            generateKeyPair()
        }
    }

    private fun loadAndroidKeyStore(): KeyStore {
        return KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
    }

    private fun buildAlias(deviceName: String): String {
        val normalized = deviceName
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
            .ifEmpty { "unknown_device" }
        return "$DEFAULT_ALIAS_PREFIX$normalized"
    }

    private fun buildTrustAllManager(): X509ExtendedTrustManager {
        return object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: SSLSocket) =
                inspectCipher(socket.session)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: SSLSocket) =
                inspectCipher(socket.session)

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
                inspectCipher(engine.session)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) =
                inspectCipher(engine.session)
        }
    }

    private fun wrapWithCipherInspection(trustManagers: Array<TrustManager>): Array<TrustManager> {
        val delegate = trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            ?: error("Default X509TrustManager is unavailable")

        val wrapped = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: SSLSocket) {
                if (delegate is X509ExtendedTrustManager) {
                    delegate.checkClientTrusted(chain, authType, socket)
                } else {
                    delegate.checkClientTrusted(chain, authType)
                }
                inspectCipher(socket.handshakeSession ?: socket.session)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: SSLSocket) {
                if (delegate is X509ExtendedTrustManager) {
                    delegate.checkServerTrusted(chain, authType, socket)
                } else {
                    delegate.checkServerTrusted(chain, authType)
                }
                inspectCipher(socket.handshakeSession ?: socket.session)
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
                if (delegate is X509ExtendedTrustManager) {
                    delegate.checkClientTrusted(chain, authType, engine)
                } else {
                    delegate.checkClientTrusted(chain, authType)
                }
                inspectCipher(engine.handshakeSession ?: engine.session)
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
                if (delegate is X509ExtendedTrustManager) {
                    delegate.checkServerTrusted(chain, authType, engine)
                } else {
                    delegate.checkServerTrusted(chain, authType)
                }
                inspectCipher(engine.handshakeSession ?: engine.session)
            }
        }

        return arrayOf(wrapped)
    }

    private fun inspectCipher(session: SSLSession?) {
        val cipherSuite = session?.cipherSuite ?: return
        Log.i(TAG, "Negotiated TLS cipher suite: $cipherSuite")
        if (cipherSuite.contains("CHACHA20", ignoreCase = true)) {
            onSoftwareCipherDetected()
        }
    }

    private fun X509Certificate.toPem(): String {
        val encodedBody = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return buildString {
            appendLine("-----BEGIN CERTIFICATE-----")
            encodedBody.chunked(64).forEach { appendLine(it) }
            appendLine("-----END CERTIFICATE-----")
        }
    }

    private companion object {
        private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000
    }
}
