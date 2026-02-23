package com.fluxsync.core.security

import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo

actual class CertificateManager {
    actual fun getOrCreateCertificate(deviceName: String): DeviceCertificate {
        val certPath = DesktopConfigPaths.getCertPath()
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)

        if (certPath.exists()) {
            FileInputStream(certPath).use { input ->
                keyStore.load(input, STORE_PASSWORD)
            }
            val alias = keyStore.aliases().toList().firstOrNull()
                ?: error("PKCS12 keystore exists at ${certPath.absolutePath} but contains no aliases")
            val cert = keyStore.getCertificate(alias) as? X509Certificate
                ?: error("Alias '$alias' in ${certPath.absolutePath} is not an X509 certificate")
            return DeviceCertificate(alias, computeFingerprint(cert), cert.toPemEncoded())
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val certificate = generateSelfSignedCertificate(keyPair, deviceName)

        keyStore.load(null, STORE_PASSWORD)
        keyStore.setKeyEntry(DEFAULT_ALIAS, keyPair.private, STORE_PASSWORD, arrayOf(certificate))

        certPath.parentFile?.mkdirs()
        FileOutputStream(certPath).use { output ->
            keyStore.store(output, STORE_PASSWORD)
        }

        return DeviceCertificate(DEFAULT_ALIAS, computeFingerprint(certificate), certificate.toPemEncoded())
    }

    actual fun buildSslContext(cert: DeviceCertificate, acceptAllPeers: Boolean): SSLContext {
        val certPath = DesktopConfigPaths.getCertPath()
        require(certPath.exists()) {
            "Device certificate keystore not found at ${certPath.absolutePath}. Call getOrCreateCertificate() first."
        }

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        FileInputStream(certPath).use { input ->
            keyStore.load(input, STORE_PASSWORD)
        }

        require(keyStore.containsAlias(cert.alias)) {
            "Alias '${cert.alias}' not found in device keystore at ${certPath.absolutePath}."
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, STORE_PASSWORD)
        }

        val trustManagers: Array<TrustManager> = if (acceptAllPeers) {
            arrayOf(TRUST_ALL_MANAGER)
        } else {
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(null as KeyStore?)
            trustManagerFactory.trustManagers
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagers, SecureRandom())
        }
    }

    actual fun computeFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair, deviceName: String): X509Certificate {
        val now = Date()
        val expiry = Date(now.time + VALIDITY_WINDOW_MS)
        val certInfo = X509CertInfo().apply {
            val owner = X500Name("CN=${sanitizeCommonName(deviceName)}, OU=FluxSync, O=FluxSync")
            set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
            set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(BigInteger(64, SecureRandom())))
            set(X509CertInfo.SUBJECT, owner)
            set(X509CertInfo.ISSUER, owner)
            set(X509CertInfo.VALIDITY, CertificateValidity(now, expiry))
            set(X509CertInfo.KEY, CertificateX509Key(keyPair.public))
            set(
                X509CertInfo.ALGORITHM_ID,
                CertificateAlgorithmId(AlgorithmId.get(SIGNATURE_ALGORITHM))
            )
        }

        val certificate = X509CertImpl(certInfo)
        certificate.sign(keyPair.private, SIGNATURE_ALGORITHM)

        certInfo.set(
            CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM,
            certificate.get(X509CertImpl.SIG_ALG)
        )

        return X509CertImpl(certInfo).apply {
            sign(keyPair.private, SIGNATURE_ALGORITHM)
        }
    }

    private fun sanitizeCommonName(deviceName: String): String =
        deviceName.replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(64).ifBlank { "FluxSync Device" }

    private fun X509Certificate.toPemEncoded(): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(encoded)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64)
            append("\n-----END CERTIFICATE-----")
        }
    }

    private companion object {
        const val KEYSTORE_TYPE = "PKCS12"
        const val DEFAULT_ALIAS = "device"
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val VALIDITY_WINDOW_MS = 10L * 365L * 24L * 60L * 60L * 1000L
        val STORE_PASSWORD = "fluxsync-device".toCharArray()

        val TRUST_ALL_MANAGER = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
