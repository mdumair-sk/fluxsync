package com.fluxsync.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fluxsync.core.security.TrustedDevice
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrustedDevicesScreen(
    trustedDevices: List<TrustedDevice>,
    onBackClick: () -> Unit,
    onRevokeClick: (TrustedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Trusted Devices") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        if (trustedDevices.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No trusted devices", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trustedDevices, key = { it.fingerprint }) { device ->
                    TrustedDeviceRow(device = device, onRevokeClick = { onRevokeClick(device) })
                }
            }
        }
    }
}

@Composable
private fun TrustedDeviceRow(
    device: TrustedDevice,
    onRevokeClick: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = truncateFingerprint(device.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Last seen: ${formatLastSeenDate(device.lastSeenMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRevokeClick) { Text("Revoke") }
        }
    }
}

private fun truncateFingerprint(fingerprint: String): String {
    if (fingerprint.length <= 14) return fingerprint
    return "${fingerprint.take(8)}...${fingerprint.takeLast(6)}"
}

private fun formatLastSeenDate(lastSeenMs: Long): String {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
    return formatter.format(Date(lastSeenMs))
}

@Preview
@Composable
private fun TrustedDevicesScreenPreview() {
    TrustedDevicesScreen(
        trustedDevices = listOf(
            TrustedDevice(
                fingerprint = "AB:CD:EF:01:23:45:67:89:AA:BB:CC:DD",
                deviceName = "Pixel 8",
                lastSeenMs = System.currentTimeMillis() - 86_400_000,
            ),
            TrustedDevice(
                fingerprint = "11:22:33:44:55:66:77:88:99:AA:BB:CC",
                deviceName = "Workstation",
                lastSeenMs = System.currentTimeMillis() - 3_600_000,
            ),
        ),
        onBackClick = {},
        onRevokeClick = {},
    )
}
