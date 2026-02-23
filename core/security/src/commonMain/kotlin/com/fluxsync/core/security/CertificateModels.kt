package com.fluxsync.core.security

import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext

data class DeviceCertificate(
    val alias: String,
    val sha256Fingerprint: String,
    val pemEncoded: String
)

expect class CertificateManager {
    fun getOrCreateCertificate(deviceName: String): DeviceCertificate
    fun buildSslContext(cert: DeviceCertificate, acceptAllPeers: Boolean): SSLContext
    fun computeFingerprint(cert: X509Certificate): String
}

data class TrustedDevice(
    val fingerprint: String,
    val deviceName: String,
    val lastSeenMs: Long,
    val trusted: Boolean = true
)

interface TrustStore {
    fun isTrusted(fingerprint: String): Boolean
    fun save(device: TrustedDevice)
    fun getAll(): List<TrustedDevice>
    fun revoke(fingerprint: String)
}
