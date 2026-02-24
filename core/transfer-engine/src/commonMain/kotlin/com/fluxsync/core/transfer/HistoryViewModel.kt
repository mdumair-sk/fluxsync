package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryFilter {
    ALL,
    SENT,
    RECEIVED,
    FAILED,
}

data class HistoryUiState(
    val entries: List<TransferHistoryEntry> = emptyList(),
    val activeFilter: HistoryFilter = HistoryFilter.ALL,
    val expandedEntryId: String? = null,
    val isLoading: Boolean = true,
)

class HistoryViewModel(
    private val repository: TransferHistoryRepository,
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    private val _retryFailed = MutableSharedFlow<TransferHistoryEntry>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val retryFailed: SharedFlow<TransferHistoryEntry> = _retryFailed.asSharedFlow()

    private var allEntries: List<TransferHistoryEntry> = emptyList()

    init {
        refresh()
    }

    fun onFilterChanged(filter: HistoryFilter) {
        _uiState.update { current ->
            current.copy(
                activeFilter = filter,
                entries = applyFilter(allEntries, filter),
                expandedEntryId = null,
            )
        }
    }

    fun onEntryTapped(id: String) {
        _uiState.update { current ->
            current.copy(
                expandedEntryId = if (current.expandedEntryId == id) null else id,
            )
        }
    }

    fun onDeleteEntry(id: String) {
        scope.launch {
            repository.delete(id)
            val updatedEntries = allEntries.filterNot { it.id == id }
            allEntries = updatedEntries
            _uiState.update { current ->
                current.copy(
                    entries = applyFilter(updatedEntries, current.activeFilter),
                    expandedEntryId = if (current.expandedEntryId == id) null else current.expandedEntryId,
                )
            }
        }
    }

    fun onRetryFailed(entry: TransferHistoryEntry) {
        _retryFailed.tryEmit(entry)
    }

    fun onCopyDetails(entry: TransferHistoryEntry): String {
        val date = formatDate(entry.completedAtMs)
        val directionLabel = if (entry.direction == TransferDirection.SENT) "Sent" else "Received"
        val duration = formatDuration(entry.completedAtMs - entry.startedAtMs)
        val sizeLabel = formatSize(entry.totalSizeBytes)
        val peakSpeedMb = formatMegabytesPerSecond(entry.averageSpeedBytesPerSec)
        val (wifiShare, usbShare) = channelSplit(entry.channelsUsed)

        return buildString {
            appendLine("FluxSync Transfer — $date")
            appendLine("Direction: $directionLabel")
            appendLine("Peer: ${entry.peerDeviceName} (${entry.peerCertFingerprint})")
            appendLine("Files: ${entry.files.size} · $sizeLabel · ${entry.outcome.name}")
            appendLine("Duration: $duration · Peak: $peakSpeedMb MB/s")
            append("Channels: Wi-Fi $wifiShare% + USB $usbShare%")
        }
    }

    fun refresh() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = repository.getAll().sortedByDescending { it.completedAtMs }
            allEntries = entries
            _uiState.update { current ->
                current.copy(
                    entries = applyFilter(entries, current.activeFilter),
                    expandedEntryId = current.expandedEntryId?.takeIf { id -> entries.any { it.id == id } },
                    isLoading = false,
                )
            }
        }
    }

    private fun applyFilter(
        entries: List<TransferHistoryEntry>,
        filter: HistoryFilter,
    ): List<TransferHistoryEntry> = when (filter) {
        HistoryFilter.ALL -> entries
        HistoryFilter.SENT -> entries.filter { it.direction == TransferDirection.SENT }
        HistoryFilter.RECEIVED -> entries.filter { it.direction == TransferDirection.RECEIVED }
        HistoryFilter.FAILED -> entries.filter { it.outcome == TransferOutcome.FAILED }
    }

    private fun formatDate(epochMs: Long): String = epochMs.toString()

    private fun formatDuration(durationMs: Long): String {
        val clampedMs = durationMs.coerceAtLeast(0)
        val totalSeconds = clampedMs / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds}s"
    }

    private fun formatSize(sizeBytes: Long): String {
        if (sizeBytes <= 0L) {
            return "0 B"
        }

        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = sizeBytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }

        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${formatWithSingleDecimal(value)} ${units[unitIndex]}"
        }
    }

    private fun formatMegabytesPerSecond(bytesPerSecond: Long): String {
        val mbPerSecond = bytesPerSecond.toDouble() / (1024.0 * 1024.0)
        return formatWithTwoDecimals(mbPerSecond)
    }



    private fun formatWithSingleDecimal(value: Double): String {
        val scaled = (value * 10).toLong()
        val whole = scaled / 10
        val fractional = (scaled % 10).toInt()
        return "$whole.$fractional"
    }

    private fun formatWithTwoDecimals(value: Double): String {
        val scaled = (value * 100).toLong()
        val whole = scaled / 100
        val fractional = (scaled % 100).toInt().toString().padStart(2, '0')
        return "$whole.$fractional"
    }

    private fun channelSplit(channels: List<ChannelType>): Pair<Int, Int> {
        val hasWifi = channels.any { it == ChannelType.WIFI }
        val hasUsb = channels.any { it == ChannelType.USB_ADB }

        return when {
            hasWifi && hasUsb -> 50 to 50
            hasWifi -> 100 to 0
            hasUsb -> 0 to 100
            else -> 0 to 0
        }
    }
}
