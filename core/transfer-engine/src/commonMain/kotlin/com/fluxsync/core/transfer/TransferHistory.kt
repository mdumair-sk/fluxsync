package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelType
import kotlinx.serialization.Serializable

@Serializable
enum class TransferDirection {
    SENT,
    RECEIVED,
}

@Serializable
enum class TransferOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
    PARTIAL,
}

@Serializable
data class HistoryFileEntry(
    val name: String,
    val sizeBytes: Long,
    val outcome: TransferOutcome,
)

@Serializable
data class TransferHistoryEntry(
    val id: String = generateTransferHistoryId(),
    val sessionId: Long,
    val direction: TransferDirection,
    val peerDeviceName: String,
    val peerCertFingerprint: String,
    val files: List<HistoryFileEntry>,
    val totalSizeBytes: Long,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val outcome: TransferOutcome,
    val averageSpeedBytesPerSec: Long,
    val channelsUsed: List<ChannelType>,
    val failedFileCount: Int,
)

interface TransferHistoryRepository {
    suspend fun insert(entry: TransferHistoryEntry)
    suspend fun getAll(): List<TransferHistoryEntry>
    suspend fun getFiltered(direction: TransferDirection): List<TransferHistoryEntry>
    suspend fun delete(id: String)
    suspend fun getById(id: String): TransferHistoryEntry?
}

expect fun generateTransferHistoryId(): String
