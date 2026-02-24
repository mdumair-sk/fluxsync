package com.fluxsync.app.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.transfer.HistoryFileEntry
import com.fluxsync.core.transfer.HistoryFilter
import com.fluxsync.core.transfer.HistoryUiState
import com.fluxsync.core.transfer.TransferDirection
import com.fluxsync.core.transfer.TransferHistoryEntry
import com.fluxsync.core.transfer.TransferOutcome

@Composable
fun HistoryScreen(
    uiState: HistoryUiState,
    onFilterChanged: (HistoryFilter) -> Unit,
    onEntryTapped: (String) -> Unit,
    onRetryFailedFiles: (TransferHistoryEntry) -> Unit,
    onCopyDetails: (TransferHistoryEntry) -> Unit,
    onDeleteEntry: (String) -> Unit,
    isPeerOnline: (TransferHistoryEntry) -> Boolean,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterRow(
                activeFilter = uiState.activeFilter,
                onFilterChanged = onFilterChanged,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = uiState.entries, key = { entry -> entry.id }) { entry ->
                    HistoryRow(
                        entry = entry,
                        expanded = uiState.expandedEntryId == entry.id,
                        onTap = { onEntryTapped(entry.id) },
                        onRetryFailedFiles = { onRetryFailedFiles(entry) },
                        onCopyDetails = { onCopyDetails(entry) },
                        onDelete = { onDeleteEntry(entry.id) },
                        peerOnline = isPeerOnline(entry),
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    activeFilter: HistoryFilter,
    onFilterChanged: (HistoryFilter) -> Unit,
) {
    val filters = listOf(
        HistoryFilter.ALL to "All",
        HistoryFilter.SENT to "Sent",
        HistoryFilter.RECEIVED to "Received",
        HistoryFilter.FAILED to "Failed",
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { (filter, label) ->
            val selected = activeFilter == filter
            AssistChip(
                onClick = { onFilterChanged(filter) },
                label = { Text(text = label) },
                colors = if (selected) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
        }
    }
}

@Composable
private fun HistoryRow(
    entry: TransferHistoryEntry,
    expanded: Boolean,
    onTap: () -> Unit,
    onRetryFailedFiles: () -> Unit,
    onCopyDetails: () -> Unit,
    onDelete: () -> Unit,
    peerOnline: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val directionArrow = if (entry.direction == TransferDirection.SENT) "↑" else "↓"
            Text(
                text = "$directionArrow ${entry.peerDeviceName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = "${entry.files.size} files • ${formatBytes(entry.totalSizeBytes)} • ${formatSpeed(entry.averageSpeedBytesPerSec)} avg",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val channelIcons = entry.channelsUsed.joinToString(" ") { channel ->
                    when (channel) {
                        ChannelType.WIFI -> "📶"
                        ChannelType.USB_ADB -> "🔌"
                    }
                }
                val outcomeIcon = if (entry.outcome == TransferOutcome.COMPLETED) "✓" else "✗"
                val outcomeColor = if (entry.outcome == TransferOutcome.COMPLETED) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }

                Text(
                    text = "$channelIcons • ${formatTimestamp(entry.completedAtMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = outcomeIcon,
                    style = MaterialTheme.typography.titleMedium,
                    color = outcomeColor,
                    fontWeight = FontWeight.Bold,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))

                    entry.files.forEach { file ->
                        val fileOutcome = if (file.outcome == TransferOutcome.COMPLETED) "✓" else "✗"
                        Text(
                            text = "$fileOutcome ${file.name} • ${formatBytes(file.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Text(
                        text = "Duration: ${formatDuration(entry.completedAtMs - entry.startedAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Peak speed: ${formatSpeed(entry.averageSpeedBytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Device fingerprint: ${entry.peerCertFingerprint}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (peerOnline) {
                            OutlinedButton(onClick = onRetryFailedFiles) {
                                Text("Retry failed files")
                            }
                        }
                        OutlinedButton(onClick = onCopyDetails) {
                            Text("Copy details")
                        }
                        OutlinedButton(onClick = onDelete) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) "${value.toLong()} ${units[idx]}" else "${"%.1f".format(value)} ${units[idx]}"
}

private fun formatSpeed(bytesPerSec: Long): String {
    val mbps = bytesPerSec.toDouble() / (1024.0 * 1024.0)
    return "${"%.2f".format(mbps)} MB/s"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes}m ${seconds}s"
}

private fun formatTimestamp(epochMs: Long): String {
    return epochMs.toString()
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    HistoryScreen(
        uiState = HistoryUiState(
            entries = listOf(
                TransferHistoryEntry(
                    id = "1",
                    sessionId = 10,
                    direction = TransferDirection.SENT,
                    peerDeviceName = "Pixel 8",
                    peerCertFingerprint = "AB:CD:EF:12:34",
                    files = listOf(
                        HistoryFileEntry("photo.jpg", 3_200_000, TransferOutcome.COMPLETED),
                        HistoryFileEntry("notes.txt", 2_000, TransferOutcome.FAILED),
                    ),
                    totalSizeBytes = 3_202_000,
                    startedAtMs = 1_700_000_000_000,
                    completedAtMs = 1_700_000_045_000,
                    outcome = TransferOutcome.FAILED,
                    averageSpeedBytesPerSec = 2_000_000,
                    channelsUsed = listOf(ChannelType.WIFI, ChannelType.USB_ADB),
                    failedFileCount = 1,
                ),
            ),
            activeFilter = HistoryFilter.ALL,
            expandedEntryId = "1",
            isLoading = false,
        ),
        onFilterChanged = {},
        onEntryTapped = {},
        onRetryFailedFiles = {},
        onCopyDetails = {},
        onDeleteEntry = {},
        isPeerOnline = { true },
    )
}
