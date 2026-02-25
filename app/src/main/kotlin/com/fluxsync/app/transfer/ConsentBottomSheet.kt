package com.fluxsync.app.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxsync.core.transfer.TransferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentBottomSheet(viewModel: TransferViewModel) {
    val state by viewModel.uiState.collectAsState()

    ModalBottomSheet(onDismissRequest = { viewModel.onConsentDeclined() }) {
        Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                    text = state.consentSenderDeviceName,
                    style = MaterialTheme.typography.titleLarge,
            )
            Text(
                    text = state.consentFileSummary,
                    style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                    onClick = { viewModel.onConsentDeclined() },
                    modifier = Modifier.fillMaxWidth()
            ) { Text("DECLINE") }
            Button(
                    onClick = { viewModel.onConsentAccepted() },
                    modifier = Modifier.fillMaxWidth()
            ) { Text("ACCEPT") }
        }
    }
}
