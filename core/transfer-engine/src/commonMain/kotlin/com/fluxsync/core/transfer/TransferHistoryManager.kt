package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelType

class TransferHistoryManager(
    private val repository: TransferHistoryRepository,
    private val nowMs: () -> Long = { epochMillisecondsNow() },
) {
    suspend fun recordSessionEnd(
        snapshot: TransferHistorySessionSnapshot,
        outcome: TransferOutcome,
    ) {
        repository.insert(
            TransferHistoryEntry(
                sessionId = snapshot.sessionId,
                direction = snapshot.direction,
                peerDeviceName = snapshot.peerDeviceName,
                peerCertFingerprint = snapshot.peerCertFingerprint,
                files = snapshot.files,
                totalSizeBytes = snapshot.totalSizeBytes,
                startedAtMs = snapshot.startedAtMs,
                completedAtMs = nowMs(),
                outcome = outcome,
                averageSpeedBytesPerSec = snapshot.averageSpeedBytesPerSec,
                channelsUsed = snapshot.channelsUsed,
                failedFileCount = snapshot.failedFileCount,
            ),
        )
    }
}

data class TransferHistorySessionSnapshot(
    val sessionId: Long,
    val direction: TransferDirection,
    val peerDeviceName: String,
    val peerCertFingerprint: String,
    val files: List<HistoryFileEntry>,
    val totalSizeBytes: Long,
    val startedAtMs: Long,
    val averageSpeedBytesPerSec: Long,
    val channelsUsed: List<ChannelType>,
    val failedFileCount: Int,
)

expect fun epochMillisecondsNow(): Long
