package com.fluxsync.app.di

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.fluxsync.core.security.TrustStore
import com.fluxsync.core.security.TrustedDevice
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [TrustStore] implementation backed by [EncryptedSharedPreferences] (Jetpack Security).
 *
 * Each [TrustedDevice] is stored as a JSON value keyed by its fingerprint. All data is encrypted at
 * rest via AES-256 (AES256_SIV for keys, AES256_GCM for values).
 */
class AndroidTrustStore(context: Context) : TrustStore {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences =
            EncryptedSharedPreferences.create(
                    PREFS_FILE_NAME,
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

    override fun isTrusted(fingerprint: String): Boolean {
        val stored = prefs.getString(fingerprint, null) ?: return false
        return try {
            json.decodeFromString<TrustedDeviceDto>(stored).trusted
        } catch (_: Exception) {
            false
        }
    }

    override fun save(device: TrustedDevice) {
        val dto =
                TrustedDeviceDto(
                        fingerprint = device.fingerprint,
                        deviceName = device.deviceName,
                        lastSeenMs = device.lastSeenMs,
                        trusted = device.trusted,
                )
        prefs.edit().putString(device.fingerprint, json.encodeToString(dto)).apply()
    }

    override fun getAll(): List<TrustedDevice> {
        return prefs.all.values.mapNotNull { value ->
            try {
                val dto = json.decodeFromString<TrustedDeviceDto>(value as String)
                TrustedDevice(
                        fingerprint = dto.fingerprint,
                        deviceName = dto.deviceName,
                        lastSeenMs = dto.lastSeenMs,
                        trusted = dto.trusted,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun revoke(fingerprint: String) {
        prefs.edit().remove(fingerprint).apply()
    }

    /**
     * Internal DTO to avoid directly serialising [TrustedDevice] (which lives in :core:security and
     * uses kotlinx.serialization only for the wire format, not Android persistence).
     */
    @kotlinx.serialization.Serializable
    private data class TrustedDeviceDto(
            val fingerprint: String,
            val deviceName: String,
            val lastSeenMs: Long,
            val trusted: Boolean,
    )

    private companion object {
        const val PREFS_FILE_NAME = "fluxsync_trusted_devices"
    }
}
