package com.fluxsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fluxsync.app.home.HomeScreen
import com.fluxsync.core.transfer.HomeUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                            uiState = HomeUiState(emptyList()),
                            localIpAddress = "127.0.0.1",
                            localCertFingerprint = "unknown",
                            onSendFilesClick = {},
                            onDeviceSelected = {},
                            onManualIpSubmitted = { _, _ -> }
                    )
                }
            }
        }
    }
}
