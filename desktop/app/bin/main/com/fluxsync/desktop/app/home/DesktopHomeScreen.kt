package com.fluxsync.desktop.app.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxsync.core.transfer.DeviceUiEntry
import com.fluxsync.core.transfer.HomeUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import kotlinx.coroutines.delay

private val WifiBlue = Color(0xFF3D85F0)
private val UsbAmber = Color(0xFFF09D3D)

@Composable
fun DesktopHomeScreen(
    uiState: HomeUiState,
    localIpAddress: String,
    localPort: Int = 5001,
    localCertFingerprint: String = "unknown",
    onFilesDropped: (List<File>) -> Unit,
    onDeviceSelected: (DeviceUiEntry) -> Unit,
    onManualIpSubmitted: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showManualIpDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFD))
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DropZone(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onFilesDropped = onFilesDropped,
        )

        Column(
            modifier = Modifier.width(360.dp).fillMaxHeight(),
        ) {
            Text(
                text = if (uiState.isDiscovering) "Discovering devices…" else "Discovered devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(uiState.discoveredDevices) { device ->
                    DesktopDeviceCard(
                        entry = device,
                        onClick = { onDeviceSelected(device) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showManualIpDialog = true }) {
                    Text("Manual IP")
                }
                OutlinedButton(onClick = { showQrDialog = true }) {
                    Text("Show QR Code")
                }
            }
        }
    }

    if (showManualIpDialog) {
        ManualIpDialog(
            onDismiss = { showManualIpDialog = false },
            onSubmit = { ip, port ->
                onManualIpSubmitted(ip, port)
                showManualIpDialog = false
            },
        )
    }

    if (showQrDialog) {
        val qrPayload = "fluxsync://$localIpAddress:$localPort/$localCertFingerprint"
        QrCodeDialog(
            payload = qrPayload,
            onDismiss = { showQrDialog = false },
        )
    }
}

@Composable
private fun DropZone(
    onFilesDropped: (List<File>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isHovering by remember { mutableStateOf(false) }
    var pulseTarget by remember { mutableStateOf(0.25f) }

    val glowOpacity by animateFloatAsState(
        targetValue = if (isHovering) pulseTarget else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "dropZoneGlow",
    )

    LaunchedEffect(isHovering) {
        if (!isHovering) return@LaunchedEffect
        while (isHovering) {
            pulseTarget = if (pulseTarget < 0.4f) 0.78f else 0.24f
            delay(560)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF7AA8F8).copy(alpha = 0.55f + glowOpacity * 0.45f),
                        Color(0xFF7AA8F8).copy(alpha = 0.2f + glowOpacity * 0.45f),
                    ),
                ),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Text(
            text = "Drop files here to send",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun DesktopDeviceCard(
    entry: DeviceUiEntry,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.deviceName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.ipAddress}:${entry.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.hasWifi) BadgeChip(label = "WiFi", background = WifiBlue)
                if (entry.hasUsb) BadgeChip(label = "USB", background = UsbAmber)
                if (entry.isTrusted) {
                    BadgeChip(
                        label = "★",
                        background = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(
    label: String,
    background: Color,
) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun ManualIpDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, Int) -> Unit,
) {
    var ipAddress by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("5001") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("IP address") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter(Char::isDigit) },
                    label = { Text("Port") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portInput.toIntOrNull() ?: 5001
                    onSubmit(ipAddress.trim(), port)
                },
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun QrCodeDialog(
    payload: String,
    onDismiss: () -> Unit,
) {
    val matrix = remember(payload) {
        MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to pair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QrCanvas(matrix = matrix, modifier = Modifier.fillMaxWidth().height(260.dp))
                Text(text = payload, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun QrCanvas(
    matrix: BitMatrix,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.background(Color.White)) {
        val cols = matrix.width
        val rows = matrix.height
        val cellWidth = size.width / cols
        val cellHeight = size.height / rows

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * cellWidth, y * cellHeight),
                        size = Size(cellWidth, cellHeight),
                    )
                }
            }
        }
    }
}
