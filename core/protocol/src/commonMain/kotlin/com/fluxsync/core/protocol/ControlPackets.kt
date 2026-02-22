package com.fluxsync.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class HandshakePacket(
    val protocolVersion: Int,
    val deviceName: String,
    val certFingerprint: String,
    val maxChunkSizeBytes: Int,
    val availableMemoryMb: Int,
)

@Serializable
data class ResumeValidation(
    val fileId: String,
    val expectedSizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val firstChunkChecksum: Int,
)

@Serializable
data class FileEntry(
    val fileId: Int,
    val name: String,
    val sizeBytes: Long,
    val totalChunks: Int,
    val negotiatedChunkSizeBytes: Int,
    val resumeValidation: ResumeValidation? = null,
)

@Serializable
data class FileManifest(
    val sessionId: Long,
    val files: List<FileEntry>,
)

@Serializable
data class RetryRequestPacket(
    val sessionId: Long,
    val fileId: Int,
    val failedChunkIndices: List<Int>,
)

@Serializable
data class ConsentRequestPacket(
    val sessionId: Long,
    val manifest: FileManifest,
)

@Serializable
data class ConsentResponsePacket(
    val sessionId: Long,
    val accepted: Boolean,
)

@Serializable
data class SessionCompletePacket(
    val sessionId: Long,
)

@Serializable
data class SessionCancelPacket(
    val sessionId: Long,
    val reason: String,
)

enum class ControlPacketType {
    HANDSHAKE,
    RESUME_VALIDATION,
    FILE_ENTRY,
    FILE_MANIFEST,
    RETRY_REQUEST,
    CONSENT_REQUEST,
    CONSENT_RESPONSE,
    SESSION_COMPLETE,
    SESSION_CANCEL,
}

object ControlPacketSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun encodeToBytes(packet: Any): ByteArray {
        val (type, payload) = when (packet) {
            is HandshakePacket -> ControlPacketType.HANDSHAKE to json.encodeToString(packet)
            is ResumeValidation -> ControlPacketType.RESUME_VALIDATION to json.encodeToString(packet)
            is FileEntry -> ControlPacketType.FILE_ENTRY to json.encodeToString(packet)
            is FileManifest -> ControlPacketType.FILE_MANIFEST to json.encodeToString(packet)
            is RetryRequestPacket -> ControlPacketType.RETRY_REQUEST to json.encodeToString(packet)
            is ConsentRequestPacket -> ControlPacketType.CONSENT_REQUEST to json.encodeToString(packet)
            is ConsentResponsePacket -> ControlPacketType.CONSENT_RESPONSE to json.encodeToString(packet)
            is SessionCompletePacket -> ControlPacketType.SESSION_COMPLETE to json.encodeToString(packet)
            is SessionCancelPacket -> ControlPacketType.SESSION_CANCEL to json.encodeToString(packet)
            else -> throw IllegalArgumentException("Unsupported control packet type: ${packet::class}")
        }

        val jsonBytes = payload.encodeToByteArray()
        return ByteArray(jsonBytes.size + 1).also { bytes ->
            bytes[0] = type.ordinal.toByte()
            jsonBytes.copyInto(bytes, destinationOffset = 1)
        }
    }

    fun decodeFromBytes(bytes: ByteArray): Any {
        require(bytes.isNotEmpty()) { "Cannot decode control packet from empty byte array" }

        val typeOrdinal = bytes[0].toInt() and 0xFF
        val type = ControlPacketType.values().getOrNull(typeOrdinal)
            ?: throw IllegalArgumentException("Unknown control packet type tag: $typeOrdinal")

        val payload = bytes.decodeToString(startIndex = 1, endIndex = bytes.size)
        return when (type) {
            ControlPacketType.HANDSHAKE -> json.decodeFromString<HandshakePacket>(payload)
            ControlPacketType.RESUME_VALIDATION -> json.decodeFromString<ResumeValidation>(payload)
            ControlPacketType.FILE_ENTRY -> json.decodeFromString<FileEntry>(payload)
            ControlPacketType.FILE_MANIFEST -> json.decodeFromString<FileManifest>(payload)
            ControlPacketType.RETRY_REQUEST -> json.decodeFromString<RetryRequestPacket>(payload)
            ControlPacketType.CONSENT_REQUEST -> json.decodeFromString<ConsentRequestPacket>(payload)
            ControlPacketType.CONSENT_RESPONSE -> json.decodeFromString<ConsentResponsePacket>(payload)
            ControlPacketType.SESSION_COMPLETE -> json.decodeFromString<SessionCompletePacket>(payload)
            ControlPacketType.SESSION_CANCEL -> json.decodeFromString<SessionCancelPacket>(payload)
        }
    }
}
