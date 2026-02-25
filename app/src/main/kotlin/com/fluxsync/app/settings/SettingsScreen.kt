package com.fluxsync.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
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
    modifier: Modifier = Modifier,
) {
    var portFieldValue by remember(state.portOverride) { mutableStateOf(state.portOverride.toString()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("Back")
                    }
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
            }

            SectionCard(title = "Discovery") {
                LabeledValueRow(label = "My fingerprint", value = state.formattedFingerprint)
                IconButton(onClick = { onCopyFingerprintClick(state.formattedFingerprint) }) {
                    Text("Copy")
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
        }
    }
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

enum class RoutingMode {
    OPPORTUNISTIC,
    FORCE_WIFI_ONLY,
}

data class SettingsUiState(
    val downloadDirectoryPath: String,
    val orphanedFluxpartCount: Int,
    val orphanedFluxpartTotalSizeLabel: String,
    val routingMode: RoutingMode,
    val portOverride: Int,
    val formattedFingerprint: String,
    val cipherSuiteLabel: String,
    val protocolVersionLabel: String,
)

const val DEFAULT_TRANSFER_PORT: Int = 5001

@Preview
@Composable
private fun SettingsScreenPreview() {
    SettingsScreen(
        state = SettingsUiState(
            downloadDirectoryPath = "/storage/emulated/0/Download/FluxSync",
            orphanedFluxpartCount = 3,
            orphanedFluxpartTotalSizeLabel = "1.4 GB",
            routingMode = RoutingMode.OPPORTUNISTIC,
            portOverride = DEFAULT_TRANSFER_PORT,
            formattedFingerprint = "AB:CD:EF:00:12:34:56:78",
            cipherSuiteLabel = "TLS_AES_256_GCM_SHA384",
            protocolVersionLabel = "v1",
        ),
        onBackClick = {},
        onChangeDownloadDirectoryClick = {},
        onViewAndCleanOrphansClick = {},
        onRoutingModeChanged = {},
        onPortOverrideChanged = {},
        onPortOverrideCommitted = {},
        onCopyFingerprintClick = {},
        onManageTrustedDevicesClick = {},
        onViewDebugLogClick = {},
        onExportDebugLogClick = {},
    )
}
