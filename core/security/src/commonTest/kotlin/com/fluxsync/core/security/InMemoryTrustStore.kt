package com.fluxsync.core.security

class InMemoryTrustStore : TrustStore {
    private val devices = HashMap<String, TrustedDevice>()

    override fun isTrusted(fingerprint: String): Boolean = devices[fingerprint]?.trusted == true

    override fun save(device: TrustedDevice) {
        devices[device.fingerprint] = device
    }

    override fun getAll(): List<TrustedDevice> = devices.values.toList()

    override fun revoke(fingerprint: String) {
        devices.remove(fingerprint)
    }
}
