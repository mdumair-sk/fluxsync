package com.fluxsync.core.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class JsonFileTrustStore(
    private val file: File = DesktopConfigPaths.getTrustStorePath(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
) : TrustStore {

    private val lock = ReentrantReadWriteLock()

    override fun isTrusted(fingerprint: String): Boolean = lock.read {
        readDevices().firstOrNull { it.fingerprint == fingerprint }?.trusted == true
    }

    override fun save(device: TrustedDevice) {
        lock.write {
            val updatedDevices = readDevices()
                .filterNot { it.fingerprint == device.fingerprint }
                .plus(device)
            writeDevices(updatedDevices)
        }
    }

    override fun getAll(): List<TrustedDevice> = lock.read {
        readDevices()
    }

    override fun revoke(fingerprint: String) {
        lock.write {
            val updatedDevices = readDevices().filterNot { it.fingerprint == fingerprint }
            writeDevices(updatedDevices)
        }
    }

    private fun readDevices(): List<TrustedDevice> {
        if (!file.exists()) {
            return emptyList()
        }

        val root = try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (error: Exception) {
            throw IllegalStateException("Failed to read trust store at ${file.absolutePath}", error)
        }

        return root[DEVICES_KEY]
            ?.jsonArray
            ?.mapIndexed { index, element ->
                element.jsonObject.toTrustedDevice(index)
            }
            ?: emptyList()
    }

    private fun writeDevices(devices: List<TrustedDevice>) {
        file.parentFile?.mkdirs()

        val root = JsonObject(
            mapOf(
                DEVICES_KEY to JsonArray(
                    devices.map { trustedDevice ->
                        JsonObject(
                            mapOf(
                                FINGERPRINT_KEY to JsonPrimitive(trustedDevice.fingerprint),
                                DEVICE_NAME_KEY to JsonPrimitive(trustedDevice.deviceName),
                                LAST_SEEN_MS_KEY to JsonPrimitive(trustedDevice.lastSeenMs),
                                TRUSTED_KEY to JsonPrimitive(trustedDevice.trusted)
                            )
                        )
                    }
                )
            )
        )

        file.writeText(json.encodeToString(JsonObject.serializer(), root))
    }

    private fun JsonObject.toTrustedDevice(index: Int): TrustedDevice {
        val fingerprint = getRequiredString(FINGERPRINT_KEY, index)
        val deviceName = getRequiredString(DEVICE_NAME_KEY, index)
        val lastSeenMs = this[LAST_SEEN_MS_KEY]?.jsonPrimitive?.longOrNull
            ?: throw IllegalStateException("Missing or invalid '$LAST_SEEN_MS_KEY' for trust store device index $index")
        val trusted = this[TRUSTED_KEY]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalStateException("Missing or invalid '$TRUSTED_KEY' for trust store device index $index")

        return TrustedDevice(
            fingerprint = fingerprint,
            deviceName = deviceName,
            lastSeenMs = lastSeenMs,
            trusted = trusted
        )
    }

    private fun JsonObject.getRequiredString(key: String, index: Int): String =
        this[key]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Missing or invalid '$key' for trust store device index $index")

    private companion object {
        const val DEVICES_KEY = "devices"
        const val FINGERPRINT_KEY = "fingerprint"
        const val DEVICE_NAME_KEY = "deviceName"
        const val LAST_SEEN_MS_KEY = "lastSeenMs"
        const val TRUSTED_KEY = "trusted"
    }
}
