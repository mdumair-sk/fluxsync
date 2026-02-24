package com.fluxsync.app.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxsync.core.transfer.DeviceUiEntry
import com.fluxsync.core.transfer.HomeUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

private val WifiBlue = Color(0xFF3D85F0)
private val UsbAmber = Color(0xFFF09D3D)

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    localIpAddress: String,
    localPort: Int = 5001,
    localCertFingerprint: String = "unknown",
    onSendFilesClick: () -> Unit,
    onDeviceSelected: (DeviceUiEntry) -> Unit,
    onManualIpSubmitted: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showManualIpDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = onSendFilesClick) {
                Text(text = "Send Files")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "FluxSync",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.discoveredDevices) { device ->
                    DeviceCard(
                        entry = device,
                        onClick = { onDeviceSelected(device) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Device not appearing?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showManualIpDialog = true }) {
                        Text(text = "Manual IP")
                    }
                    TextButton(onClick = { showQrDialog = true }) {
                        Text(text = "Show QR Code")
                    }
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
fun DeviceCard(
    entry: DeviceUiEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (entry.isTrusted) BadgeChip(label = "★", background = MaterialTheme.colorScheme.tertiary)
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
            .background(color = background, shape = MaterialTheme.shapes.small)
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
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val port = portInput.toIntOrNull() ?: 5001
                onSubmit(ipAddress.trim(), port)
            }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun QrCodeDialog(
    payload: String,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(payload) { generateQrBitmap(payload) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to pair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "FluxSync QR code",
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(text = payload, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun generateQrBitmap(payload: String, sizePx: Int = 768): Bitmap {
    val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
    return matrix.toBitmap()
}

private fun BitMatrix.toBitmap(): Bitmap {
    val width = width
    val height = height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    HomeScreen(
        uiState = HomeUiState(
            discoveredDevices = listOf(
                DeviceUiEntry("Pixel 8", "192.168.1.40", 5001, "AB:CD", isTrusted = true, hasWifi = true, hasUsb = false),
                DeviceUiEntry("Desktop", "192.168.1.2", 5001, null, isTrusted = false, hasWifi = true, hasUsb = true),
            ),
        ),
        localIpAddress = "192.168.1.100",
        localCertFingerprint = "AA:BB:CC",
        onSendFilesClick = {},
        onDeviceSelected = {},
        onManualIpSubmitted = { _, _ -> },
    )
}
