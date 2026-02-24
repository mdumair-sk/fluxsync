package com.fluxsync.desktop.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.ChannelTelemetry
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.transfer.FileOutcome
import com.fluxsync.core.transfer.FileUiEntry
import com.fluxsync.core.transfer.SessionState
import com.fluxsync.core.transfer.TransferUiState
import kotlin.math.roundToInt

private const val TWEEN_400 = 400

@Composable
fun DesktopCockpitScreen(
    state: TransferUiState,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpeedGauge(speedMbs = state.aggregateSpeedMbs)
                // Desktop duplicates Android Canvas splitter implementation until shared KMP UI module is introduced.
                SplitterBar(channelStats = state.channelStats)
            }
        }

        ChannelChipsRow(channelStats = state.channelStats)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                FileTransferList(
                    files = state.fileEntries,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }

            Card(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                EtaActionsPane(
                    etaSeconds = state.etaSeconds,
                    isPaused = state.sessionState == SessionState.RETRYING,
                    onPauseResume = onPauseResume,
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedGauge(speedMbs: Float) {
    val animatedSpeed by animateFloatAsState(
        targetValue = speedMbs.coerceAtLeast(0f),
        animationSpec = tween(durationMillis = TWEEN_400),
        label = "desktopSpeedGauge",
    )

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = String.format("%.1f", animatedSpeed),
            fontSize = 52.sp,
            lineHeight = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "MB/s",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun SplitterBar(channelStats: List<ChannelTelemetry>, modifier: Modifier = Modifier) {
    val wifiFraction = channelStats.firstOrNull { it.type == ChannelType.WIFI }?.weightFraction ?: 0f
    val usbFraction = channelStats.firstOrNull { it.type == ChannelType.USB_ADB }?.weightFraction ?: 0f
    val degradedFraction = channelStats
        .filter { it.state == ChannelState.DEGRADED || it.state == ChannelState.OFFLINE }
        .sumOf { it.weightFraction.toDouble() }
        .toFloat()

    val animatedWifi by animateFloatAsState(
        targetValue = wifiFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = TWEEN_400),
        label = "desktopSplitterWifi",
    )
    val animatedUsb by animateFloatAsState(
        targetValue = usbFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = TWEEN_400),
        label = "desktopSplitterUsb",
    )
    val animatedDegraded by animateFloatAsState(
        targetValue = degradedFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = TWEEN_400),
        label = "desktopSplitterDegraded",
    )

    val (wifi, usb, degraded) = normalize(animatedWifi, animatedUsb, animatedDegraded)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            val wifiWidth = size.width * wifi
            val usbWidth = size.width * usb
            val degradedWidth = size.width * degraded

            drawRoundRect(
                color = Color(0xFF2196F3),
                topLeft = Offset(0f, 0f),
                size = Size(wifiWidth, size.height),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = Color(0xFFFFB300),
                topLeft = Offset(wifiWidth, 0f),
                size = Size(usbWidth, size.height),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = Color(0xFF9E9E9E),
                topLeft = Offset(wifiWidth + usbWidth, 0f),
                size = Size(degradedWidth, size.height),
                cornerRadius = radius,
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            SegmentLabel("Wi-Fi", wifi, Modifier.weight(wifi.coerceAtLeast(0.001f)))
            SegmentLabel("USB", usb, Modifier.weight(usb.coerceAtLeast(0.001f)))
            SegmentLabel("Degraded", degraded, Modifier.weight(degraded.coerceAtLeast(0.001f)))
        }
    }
}

@Composable
private fun SegmentLabel(name: String, fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (fraction > 0.2f) {
            Text(
                text = "$name ${(fraction * 100).roundToInt()}%",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ChannelChipsRow(channelStats: List<ChannelTelemetry>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        channelStats.forEach { telemetry ->
            val label = when (telemetry.type) {
                ChannelType.WIFI -> "WiFi: ${telemetry.latencyMs}ms"
                ChannelType.USB_ADB -> "USB: ${telemetry.latencyMs}ms"
            }
            AssistChip(onClick = {}, label = { Text(label) })
        }
    }
}

@Composable
private fun FileTransferList(files: List<FileUiEntry>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = files, key = { entry -> entry.fileId }) { entry ->
            FileRow(fileEntry = entry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun FileRow(fileEntry: FileUiEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = fileEntry.name, style = MaterialTheme.typography.bodyLarge)
                if (fileEntry.hasFluxPart) {
                    Text(
                        text = " ↺",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(fileEntry.progressFraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (fileEntry.outcome == FileOutcome.FAILED) {
                    Text(
                        text = " ✕",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { fileEntry.progressFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun EtaActionsPane(
    etaSeconds: Int,
    isPaused: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "ETA", style = MaterialTheme.typography.titleLarge)
        Text(text = formatEta(etaSeconds), style = MaterialTheme.typography.displaySmall)
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onPauseResume, modifier = Modifier.fillMaxWidth()) {
            Text(if (isPaused) "Resume" else "Pause")
        }
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

private fun normalize(wifi: Float, usb: Float, degraded: Float): Triple<Float, Float, Float> {
    val total = wifi + usb + degraded
    if (total <= 0f) return Triple(0f, 0f, 0f)
    return Triple(wifi / total, usb / total, degraded / total)
}

private fun formatEta(etaSeconds: Int): String {
    if (etaSeconds <= 0) return "—"
    val hours = etaSeconds / 3600
    val minutes = (etaSeconds % 3600) / 60
    val seconds = etaSeconds % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}
