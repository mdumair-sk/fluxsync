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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.fluxsync.core.security.CertificateManager
import com.fluxsync.core.security.DeviceCertificate
import com.fluxsync.core.security.JsonFileTrustStore
import com.fluxsync.core.security.TofuPairingCoordinator
import com.fluxsync.core.security.TrustStore
import com.fluxsync.core.transfer.DRTLB
import com.fluxsync.core.transfer.HomeViewModel
import com.fluxsync.core.transfer.SessionState
import com.fluxsync.core.transfer.SessionStateMachine
import com.fluxsync.core.transfer.TransferUiState
import com.fluxsync.core.transfer.TransferViewModel
import com.fluxsync.desktop.app.home.DesktopHomeScreen
import com.fluxsync.desktop.app.tray.DesktopTrayManager
import com.fluxsync.desktop.app.tray.TrayUiState
import com.fluxsync.desktop.data.network.DesktopMdnsDiscovery
import com.fluxsync.desktop.data.network.DesktopTransferSession
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

enum class AppScreen {
    HOME,
    TRANSFER
}

data class DesktopViewModels(
        val homeViewModel: HomeViewModel,
        val transferViewModel: DesktopTransferViewModel,
        val mdnsDiscovery: DesktopMdnsDiscovery,
        val cert: DeviceCertificate?,
        val certManager: CertificateManager,
        val trustStore: TrustStore,
        val coreTransferViewModel: TransferViewModel,
        val sessionMachine: SessionStateMachine,
        val scope: CoroutineScope,
)

fun main() = application {
    val rootLogger = Logger.getLogger("")
    rootLogger.handlers.forEach { rootLogger.removeHandler(it) }
    val consoleHandler = ConsoleHandler()
    consoleHandler.level = Level.ALL
    consoleHandler.formatter = SimpleFormatter()
    rootLogger.addHandler(consoleHandler)
    rootLogger.level = Level.ALL

    // Suppress AWT/Swing/JmDNS FINE/FINEST log noise — only FluxSync events should appear
    listOf(
                    "sun.awt",
                    "java.awt",
                    "javax.swing",
                    "sun.lwawt",
                    "com.sun.java.swing",
                    "jdk.internal.reflect"
            )
            .forEach { pkg -> Logger.getLogger(pkg).level = Level.WARNING }
    // Also suppress JmDNS internal noise
    Logger.getLogger("javax.jmdns").level = Level.WARNING

    val viewModels = provideViewModels()
    val homeViewModel = viewModels.homeViewModel
    val viewModel = viewModels.transferViewModel
    val mdnsDiscovery = viewModels.mdnsDiscovery

    val homeUiState by homeViewModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var sessionJob by remember { mutableStateOf<Job?>(null) }

    // PIN dialog state for TOFU pairing
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinDeferred by remember { mutableStateOf<CompletableDeferred<String>?>(null) }

    val cert = viewModels.cert
    val certManager = viewModels.certManager
    val trustStore = viewModels.trustStore
    val coreVm = viewModels.coreTransferViewModel
    val sessionMachine = viewModels.sessionMachine
    val vmScope = viewModels.scope

    // Remove the old device selection LaunchedEffect — file selection is now handled in
    // DesktopHomeScreen

    LaunchedEffect(Unit) {
        mdnsDiscovery.start()
        val certManager = CertificateManager()
        val deviceName = System.getProperty("user.name") ?: "Desktop"
        val cert = runCatching { certManager.getOrCreateCertificate(deviceName) }.getOrNull()
        val fingerprint = cert?.sha256Fingerprint ?: "unknown"
        mdnsDiscovery.bindAndAdvertise(deviceName, fingerprint)
    }

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
            Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFD)),
            ) {
                when (currentScreen) {
                    AppScreen.HOME ->
                            DesktopHomeScreen(
                                    uiState = homeUiState,
                                    localIpAddress = "0.0.0.0",
                                    localPort = 5001,
                                    localCertFingerprint = cert?.sha256Fingerprint ?: "unknown",
                                    onFilesDropped = { files ->
                                        viewModel.onFilesDropped(files)
                                        currentScreen = AppScreen.TRANSFER
                                    },
                                    onDeviceSelected = { device, files ->
                                        if (cert == null) {
                                            logger.warning(
                                                    "Cannot start transfer: cert not initialized"
                                            )
                                            return@DesktopHomeScreen
                                        }
                                        logger.info(
                                                "Files selected: ${files.size} file(s), target: ${device.deviceName}"
                                        )

                                        val pairingCoordinator =
                                                TofuPairingCoordinator(
                                                        trustStore = trustStore,
                                                        onDisplayPin = { pin ->
                                                            logger.info(
                                                                    "Server displayed PIN: $pin"
                                                            )
                                                        },
                                                        onRequestPin = {
                                                            // Show PIN dialog and wait for user
                                                            // input
                                                            val deferred =
                                                                    CompletableDeferred<String>()
                                                            pinDeferred = deferred
                                                            pinInput = ""
                                                            showPinDialog = true
                                                            deferred.await()
                                                        },
                                                        onSoftwareCipherWarning = {
                                                            logger.warning(
                                                                    "Software cipher detected (ChaCha20)"
                                                            )
                                                        }
                                                )

                                        // Create a fresh session machine for this transfer
                                        val newSessionId = System.currentTimeMillis()
                                        val newSessionMachine =
                                                SessionStateMachine(
                                                        sessionId = newSessionId,
                                                        scope = vmScope,
                                                        onCancel = { reason ->
                                                            logger.info(
                                                                    "Session cancelled: $reason"
                                                            )
                                                        },
                                                        onComplete = {
                                                            logger.info("Session completed")
                                                        },
                                                )

                                        // Create a fresh DRTLB + TransferViewModel for this session
                                        val chunkSource =
                                                kotlinx.coroutines.channels.Channel<
                                                        com.fluxsync.core.protocol.ChunkPacket>()
                                        val drtlb = DRTLB(chunkSource)
                                        val sessionVm =
                                                TransferViewModel(drtlb, newSessionMachine, vmScope)

                                        val session =
                                                DesktopTransferSession(
                                                        targetIp = device.ipAddress,
                                                        targetPort = device.port,
                                                        targetDeviceName = device.deviceName,
                                                        files = files,
                                                        cert = cert,
                                                        certManager = certManager,
                                                        pairingCoordinator = pairingCoordinator,
                                                        sessionMachine = newSessionMachine,
                                                        transferViewModel = sessionVm,
                                                )

                                        currentScreen = AppScreen.TRANSFER
                                        sessionJob?.cancel()
                                        sessionJob = vmScope.launch { session.run() }
                                    },
                                    onManualIpSubmitted = { ip, port ->
                                        homeViewModel.onManualIpSubmitted(ip, port)
                                    },
                                    modifier = Modifier.fillMaxSize(),
                            )
                    AppScreen.TRANSFER ->
                            DesktopCockpitScreen(
                                    state = uiState,
                                    onPauseResume = viewModel::onPauseResume,
                                    onCancel = {
                                        viewModel.onCancel()
                                        sessionJob?.cancel()
                                        sessionJob = null
                                        currentScreen = AppScreen.HOME
                                    },
                                    modifier = Modifier.fillMaxSize(),
                            )
                }
            }

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
                DialogWindow(onCloseRequest = viewModel::declineConsent) {
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

private fun provideViewModels(): DesktopViewModels {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val trustStore = JsonFileTrustStore()
    val homeViewModel = HomeViewModel(trustStore, scope)
    val mdnsDiscovery = DesktopMdnsDiscovery(scope)

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

    scope.launch {
        mdnsDiscovery.discoveredDevices.collect { list ->
            homeViewModel.onDiscoveredDevicesUpdated(
                    list.map {
                        com.fluxsync.core.transfer.DiscoveredDevice(
                                deviceName = it.deviceName,
                                ipAddress = it.ipAddress,
                                port = it.port,
                                certFingerprint = it.certFingerprint,
                                protocolVersion = it.protocolVersion
                        )
                    }
            )
        }
    }

    val desktopTransferViewModel =
            object : DesktopTransferViewModel {
                override val uiState: StateFlow<TransferUiState> = coreViewModel.uiState
                override val trayUiState: StateFlow<DesktopTrayUiState> = trayStateFlow

                override fun onFilesDropped(files: List<File>) = coreViewModel.onFilesDropped(files)
                override fun onPauseResume() = coreViewModel.onPauseResume()
                override fun onCancel() = coreViewModel.onCancel()
                override fun declineConsent() = coreViewModel.onConsentDeclined()
            }

    return DesktopViewModels(
            homeViewModel,
            desktopTransferViewModel,
            mdnsDiscovery,
            cert,
            certManager,
            trustStore,
            coreViewModel,
            sessionMachine,
            scope,
    )
}

private const val APP_INDICATOR_EXTENSION_URL =
        "https://extensions.gnome.org/extension/615/appindicator-support/"

private val logger = Logger.getLogger("FluxSyncDesktop")
