package com.fluxsync.app.transfer

import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.transfer.SessionState

data class TransferUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val aggregateSpeedMbs: Float = 0f,
    val etaSeconds: Int = 0,
    val overallProgressFraction: Float = 0f,
    val fileEntries: List<FileUiEntry> = emptyList(),
    val channelStats: List<ChannelTelemetry> = emptyList(),
    val consentSenderDeviceName: String = "Unknown sender",
    val consentFileSummary: String = "",
    val pendingConsentDeviceName: String = "receiver",
    val pendingConsentTimeoutSeconds: Int = 60,
)

data class ChannelTelemetry(
    val type: ChannelType,
    val weightFraction: Float,
    val throughputBytesPerSec: Long,
    val latencyMs: Long,
    val bufferFillPercent: Float,
    val state: ChannelStateUi = ChannelStateUi.ACTIVE,
)

enum class ChannelStateUi {
    ACTIVE,
    DEGRADED,
    OFFLINE,
}

data class FileUiEntry(
    val fileId: Int,
    val name: String,
    val progressFraction: Float,
    val outcome: FileOutcome? = null,
    val hasFluxPart: Boolean,
)

enum class FileOutcome {
    COMPLETED,
    FAILED,
    PARTIAL,
}
