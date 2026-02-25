package com.fluxsync.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.transfer.HistoryFileEntry
import com.fluxsync.core.transfer.TransferDirection
import com.fluxsync.core.transfer.TransferHistoryEntry
import com.fluxsync.core.transfer.TransferOutcome
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room entity mirroring [TransferHistoryEntry].
 * Complex fields ([files], [channelsUsed]) are stored as JSON strings via [HistoryTypeConverters].
 */
@Entity(tableName = "history")
@TypeConverters(HistoryTypeConverters::class)
data class TransferHistoryEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: Long,
    val direction: String,
    val peerDeviceName: String,
    val peerCertFingerprint: String,
    val files: String,
    val totalSizeBytes: Long,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val outcome: String,
    val averageSpeedBytesPerSec: Long,
    val channelsUsed: String,
    val failedFileCount: Int,
)

fun TransferHistoryEntry.toEntity(): TransferHistoryEntryEntity = TransferHistoryEntryEntity(
    id = id,
    sessionId = sessionId,
    direction = direction.name,
    peerDeviceName = peerDeviceName,
    peerCertFingerprint = peerCertFingerprint,
    files = HistoryTypeConverters.json.encodeToString(files),
    totalSizeBytes = totalSizeBytes,
    startedAtMs = startedAtMs,
    completedAtMs = completedAtMs,
    outcome = outcome.name,
    averageSpeedBytesPerSec = averageSpeedBytesPerSec,
    channelsUsed = HistoryTypeConverters.json.encodeToString(channelsUsed.map { it.name }),
    failedFileCount = failedFileCount,
)

fun TransferHistoryEntryEntity.toDomain(): TransferHistoryEntry = TransferHistoryEntry(
    id = id,
    sessionId = sessionId,
    direction = TransferDirection.valueOf(direction),
    peerDeviceName = peerDeviceName,
    peerCertFingerprint = peerCertFingerprint,
    files = HistoryTypeConverters.json.decodeFromString<List<HistoryFileEntry>>(files),
    totalSizeBytes = totalSizeBytes,
    startedAtMs = startedAtMs,
    completedAtMs = completedAtMs,
    outcome = TransferOutcome.valueOf(outcome),
    averageSpeedBytesPerSec = averageSpeedBytesPerSec,
    channelsUsed = HistoryTypeConverters.json.decodeFromString<List<String>>(channelsUsed).map {
        ChannelType.valueOf(it)
    },
    failedFileCount = failedFileCount,
)

class HistoryTypeConverters {
    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    @TypeConverter
    fun fromStringList(value: String): List<String> = json.decodeFromString(value)

    @TypeConverter
    fun toStringList(list: List<String>): String = json.encodeToString(list)
}
