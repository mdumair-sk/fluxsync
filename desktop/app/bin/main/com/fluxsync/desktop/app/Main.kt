package com.fluxsync.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fluxsync.core.transfer.DRTLB
import com.fluxsync.core.transfer.SessionState
import com.fluxsync.core.transfer.SessionStateMachine
import com.fluxsync.core.transfer.TransferUiState
import com.fluxsync.core.transfer.TransferViewModel
import com.fluxsync.desktop.app.tray.DesktopTrayManager
import com.fluxsync.desktop.app.tray.TrayUiState
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface DesktopTransferViewModel {
    val uiState: StateFlow<TransferUiState>
    val trayUiState: StateFlow<DesktopTrayUiState>
    fun onFilesDropped(files: List<File>)
    fun onPauseResume()
    fun onCancel()
    fun declineConsent()
}

data class DesktopTrayUiState(
        override val sessionState: SessionState,
        override val aggregateSpeedMbs: Float,
        override val overallProgressFraction: Float,
        override val isPaused: Boolean,
) : TrayUiState

fun main() = application {
    val viewModel = provideTransferViewModel()
    val uiState by viewModel.uiState.collectAsState()

    val windowState = rememberWindowState()
    var showGnomeAppIndicatorDialog by remember { mutableStateOf(isGnomeDesktop()) }
    val scope = rememberCoroutineScope()

    val trayManager = remember {
        DesktopTrayManager(
                uiState = viewModel.trayUiState,
                scope = scope,
                onOpenFluxSync = { windowState.isMinimized = false },
                onSendFile = { windowState.isMinimized = false },
                onPause = viewModel::onPauseResume,
                onQuit = ::exitApplication,
        )
    }

    DisposableEffect(Unit) { onDispose { trayManager.dispose() } }

    Window(
            title = "FluxSync",
            onCloseRequest = { windowState.isMinimized = true },
            state = windowState,
    ) {
        MaterialTheme {
            FluxSyncMainWindowContent(
                    state = uiState,
                    onFilesDropped = viewModel::onFilesDropped,
                    onPauseResume = viewModel::onPauseResume,
                    onCancel = viewModel::onCancel,
            )

            if (showGnomeAppIndicatorDialog) {
                GnomeAppIndicatorDialog(
                        onDismiss = { showGnomeAppIndicatorDialog = false },
                        onOpenExtensionPage = {
                            Desktop.getDesktop().browse(URI(APP_INDICATOR_EXTENSION_URL))
                            showGnomeAppIndicatorDialog = false
                        },
                )
            }

            if (uiState.sessionState == SessionState.AWAITING_CONSENT && !windowState.isMinimized) {
                Dialog(onCloseRequest = viewModel::declineConsent) {
                    ConsentContent(
                            onAccept = { /* wired in phase 11 */},
                            onDecline = viewModel::declineConsent,
                    )
                }
            }
        }
    }

    if (uiState.sessionState == SessionState.AWAITING_CONSENT && windowState.isMinimized) {
        Window(
                title = "Incoming consent",
                onCloseRequest = viewModel::declineConsent,
                alwaysOnTop = true,
                state =
                        WindowState(
                                position = WindowPosition(Alignment.BottomEnd),
                                size = DpSize(380.dp, 200.dp),
                        ),
        ) {
            MaterialTheme {
                ConsentContent(
                        onAccept = { /* wired in phase 11 */},
                        onDecline = viewModel::declineConsent,
                )
            }
        }
    }
}

@Composable
private fun FluxSyncMainWindowContent(
        state: TransferUiState,
        onFilesDropped: (List<File>) -> Unit,
        onPauseResume: () -> Unit,
        onCancel: () -> Unit,
) {
    Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFD)),
    ) {
        DesktopCockpitScreen(
                state = state,
                onPauseResume = onPauseResume,
                onCancel = onCancel,
                modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ConsentContent(
        onAccept: () -> Unit,
        onDecline: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onAccept) { Text("Accept") }
        Button(onClick = onDecline, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text("Decline")
        }
    }
}

@Composable
private fun GnomeAppIndicatorDialog(
        onDismiss: () -> Unit,
        onOpenExtensionPage: () -> Unit,
) {
    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Enable GNOME tray support") },
            text = {
                Text(
                        "FluxSync needs AppIndicator support on GNOME for a reliable system tray. " +
                                "Install the AppIndicator extension to ensure tray controls stay accessible.",
                )
            },
            confirmButton = {
                Button(onClick = onOpenExtensionPage) { Text("Open extension page") }
            },
            dismissButton = { Button(onClick = onDismiss) { Text("Later") } },
    )
}

private fun isGnomeDesktop(): Boolean {
    val currentDesktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
    return currentDesktop.contains("GNOME", ignoreCase = true)
}

private fun provideTransferViewModel(): DesktopTransferViewModel {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val chunkSource = kotlinx.coroutines.channels.Channel<com.fluxsync.core.protocol.ChunkPacket>()
    val drtlb = DRTLB(chunkSource)
    val sessionMachine =
            SessionStateMachine(sessionId = 1L, scope = scope, onCancel = {}, onComplete = {})
    val coreViewModel = TransferViewModel(drtlb, sessionMachine, scope)

    val trayStateFlow =
            MutableStateFlow(
                    DesktopTrayUiState(
                            sessionState = SessionState.IDLE,
                            aggregateSpeedMbs = 0f,
                            overallProgressFraction = 0f,
                            isPaused = false,
                    ),
            )

    scope.launch {
        coreViewModel.uiState.collect { ui ->
            trayStateFlow.value =
                    DesktopTrayUiState(
                            sessionState = ui.sessionState,
                            aggregateSpeedMbs = ui.aggregateSpeedMbs,
                            overallProgressFraction = ui.overallProgressFraction,
                            isPaused = ui.sessionState == SessionState.RETRYING,
                    )
        }
    }

    return object : DesktopTransferViewModel {
        override val uiState: StateFlow<TransferUiState> = coreViewModel.uiState
        override val trayUiState: StateFlow<DesktopTrayUiState> = trayStateFlow

        override fun onFilesDropped(files: List<File>) = coreViewModel.onFilesDropped(files)
        override fun onPauseResume() = coreViewModel.onPauseResume()
        override fun onCancel() = coreViewModel.onCancel()
        override fun declineConsent() = coreViewModel.onConsentDeclined()
    }
}

private const val APP_INDICATOR_EXTENSION_URL =
        "https://extensions.gnome.org/extension/615/appindicator-support/"
