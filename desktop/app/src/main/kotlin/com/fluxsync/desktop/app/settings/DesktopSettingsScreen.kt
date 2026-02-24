package com.fluxsync.desktop.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fluxsync.desktop.app.DesktopPlatformSetup
import com.fluxsync.desktop.app.PlatformSetupResult

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

@Composable
fun DesktopSettingsScreen(
    state: DesktopSettingsUiState,
    onBackClick: () -> Unit,
    onChangeDownloadDirectoryClick: () -> Unit,
    onViewAndCleanOrphansClick: () -> Unit,
    onRoutingModeChanged: (RoutingMode) -> Unit,
    onPortOverrideChanged: (String) -> Unit,
    onPortOverrideCommitted: (Int) -> Unit,
    onCopyFingerprintClick: (String) -> Unit,
    onManageTrustedDevicesClick: () -> Unit,
    onViewDebugLogClick: () -> Unit,
    onExportDebugLogClick: () -> Unit,
    onNetworkInterfaceSelected: (NetworkInterfaceOption) -> Unit,
    onAutoConfigureFirewallClick: () -> PlatformSetupResult,
    onLaunchAtLoginChanged: (Boolean) -> Unit,
    onBrowseAdbBinaryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var portFieldValue by remember(state.portOverride) { mutableStateOf(state.portOverride.toString()) }
    var isInterfaceMenuExpanded by remember { mutableStateOf(false) }
    var firewallResult by remember { mutableStateOf<PlatformSetupResult?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Desktop Settings") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) { Text("Back") }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Storage") {
                LabeledValueRow(label = "Download directory", value = state.downloadDirectoryPath)
                Button(onClick = onChangeDownloadDirectoryClick) { Text("Change") }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                LabeledValueRow(
                    label = "Orphaned .fluxpart files",
                    value = "${state.orphanedFluxpartCount} • ${state.orphanedFluxpartTotalSizeLabel}",
                )
                Button(onClick = onViewAndCleanOrphansClick) { Text("View & Clean") }
            }

            SectionCard(title = "Network") {
                Text(text = "Routing mode", style = MaterialTheme.typography.titleSmall)
                RoutingModeRow(
                    text = "Opportunistic (Auto)",
                    selected = state.routingMode == RoutingMode.OPPORTUNISTIC,
                    onSelect = { onRoutingModeChanged(RoutingMode.OPPORTUNISTIC) },
                )
                RoutingModeRow(
                    text = "Force Wi-Fi Only",
                    selected = state.routingMode == RoutingMode.FORCE_WIFI_ONLY,
                    onSelect = { onRoutingModeChanged(RoutingMode.FORCE_WIFI_ONLY) },
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = portFieldValue,
                    onValueChange = {
                        val onlyDigits = it.filter(Char::isDigit)
                        portFieldValue = onlyDigits
                        onPortOverrideChanged(onlyDigits)
                    },
                    label = { Text("Port override") },
                    singleLine = true,
                )
                TextButton(onClick = {
                    val port = portFieldValue.toIntOrNull() ?: DEFAULT_TRANSFER_PORT
                    onPortOverrideCommitted(port)
                }) {
                    Text("Apply")
                }

                Text(text = "Interface selector", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { isInterfaceMenuExpanded = true }) {
                    Text(state.selectedNetworkInterface?.label ?: "Select interface")
                }
                DropdownMenu(
                    expanded = isInterfaceMenuExpanded,
                    onDismissRequest = { isInterfaceMenuExpanded = false },
                ) {
                    state.networkInterfaces.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                onNetworkInterfaceSelected(option)
                                isInterfaceMenuExpanded = false
                            },
                        )
                    }
                }
            }

            SectionCard(title = "Discovery") {
                LabeledValueRow(label = "My fingerprint", value = state.formattedFingerprint)
                IconButton(onClick = { onCopyFingerprintClick(state.formattedFingerprint) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy fingerprint")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Trusted devices")
                    TextButton(onClick = onManageTrustedDevicesClick) { Text("Manage →") }
                }
            }

            SectionCard(title = "Advanced") {
                LabeledValueRow(label = "Hardware AES chip", value = state.cipherSuiteLabel)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onViewDebugLogClick) { Text("View →") }
                    TextButton(onClick = onExportDebugLogClick) { Text("Export") }
                }
                LabeledValueRow(label = "Protocol version", value = state.protocolVersionLabel)
            }

            SectionCard(title = "Platform") {
                val isWindows = DesktopPlatformSetup.isWindows()
                Button(
                    enabled = isWindows,
                    onClick = { firewallResult = onAutoConfigureFirewallClick() },
                ) {
                    Text("Auto-Configure Firewall")
                }
                if (!isWindows) {
                    Text("Firewall auto-configuration is only available on Windows.")
                }
                val result = firewallResult
                if (result != null) {
                    val color = if (result.success) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                    Text(
                        text = "Firewall: ${if (result.success) "Success" else "Failed"} (exit=${result.exitCode})\n${result.output}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Launch at Login")
                    Switch(
                        checked = state.launchAtLoginEnabled,
                        onCheckedChange = onLaunchAtLoginChanged,
                    )
                }

                LabeledValueRow(label = "ADB binary", value = state.adbBinaryPath)
                Button(onClick = onBrowseAdbBinaryClick) {
                    Text("Browse")
                }
            }

            SectionCard(title = "USB Status") {
                val unauthorised = state.adbDevices.filter { it.state == UsbDeviceStatus.UNAUTHORISED }
                unauthorised.forEach { device ->
                    WarningBanner(
                        text = "${device.serial} needs USB debugging trust. Follow the prompt on your phone.",
                    )
                }

                val authorisedCount = state.adbDevices.count {
                    it.state == UsbDeviceStatus.AUTHORISED || it.state == UsbDeviceStatus.TUNNEL_ACTIVE
                }
                if (authorisedCount > 1) {
                    WarningBanner(text = "Multiple devices connected — select a device before transferring.")
                }

                if (state.adbDevices.isEmpty()) {
                    Text("No ADB devices connected.")
                } else {
                    state.adbDevices.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(device.serial)
                            Text(device.state.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3CD))
            .padding(8.dp),
        color = Color(0xFF664D03),
    )
}

@Composable
private fun RoutingModeRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text)
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title.uppercase(), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun LabeledValueRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

data class DesktopSettingsUiState(
    val downloadDirectoryPath: String,
    val orphanedFluxpartCount: Int,
    val orphanedFluxpartTotalSizeLabel: String,
    val routingMode: RoutingMode,
    val portOverride: Int,
    val formattedFingerprint: String,
    val cipherSuiteLabel: String,
    val protocolVersionLabel: String,
    val networkInterfaces: List<NetworkInterfaceOption>,
    val selectedNetworkInterface: NetworkInterfaceOption?,
    val launchAtLoginEnabled: Boolean,
    val adbBinaryPath: String,
    val adbDevices: List<UsbDeviceStatusEntry>,
)

data class NetworkInterfaceOption(
    val name: String,
    val displayName: String,
    val ipAddresses: List<String>,
) {
    val label: String
        get() = "$displayName (${name}) - ${ipAddresses.joinToString()}"
}

data class UsbDeviceStatusEntry(
    val serial: String,
    val state: UsbDeviceStatus,
)

enum class UsbDeviceStatus {
    AUTHORISED,
    UNAUTHORISED,
    TUNNEL_ACTIVE,
}


const val DEFAULT_TRANSFER_PORT: Int = 5001

enum class RoutingMode {
    OPPORTUNISTIC,
    FORCE_WIFI_ONLY,
}


fun listDesktopNetworkInterfaces(): List<NetworkInterfaceOption> {
    return Collections.list(NetworkInterface.getNetworkInterfaces())
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .mapNotNull { networkInterface ->
            val ipv4Addresses = Collections.list(networkInterface.inetAddresses)
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
            if (ipv4Addresses.isEmpty()) {
                null
            } else {
                NetworkInterfaceOption(
                    name = networkInterface.name,
                    displayName = networkInterface.displayName ?: networkInterface.name,
                    ipAddresses = ipv4Addresses,
                )
            }
        }
        .sortedBy { it.displayName.lowercase() }
        .toList()
}
