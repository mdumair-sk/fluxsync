package com.fluxsync.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fluxsync.app.di.appContainer
import com.fluxsync.app.home.HomeScreen
import com.fluxsync.app.onboarding.OnboardingPreferences
import com.fluxsync.app.onboarding.OnboardingScreen
import com.fluxsync.app.pairing.PairingScreen
import com.fluxsync.app.service.TransferForegroundService
import com.fluxsync.app.settings.SettingsScreen
import com.fluxsync.app.transfer.CockpitScreen
import com.fluxsync.app.transfer.ConsentBottomSheet
import com.fluxsync.app.transfer.HistoryScreen
import com.fluxsync.core.transfer.HistoryViewModel
import com.fluxsync.core.transfer.HomeViewModel
import com.fluxsync.core.transfer.SessionState
import com.fluxsync.core.transfer.TransferViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")
val ONBOARDING_COMPLETE_KEY = booleanPreferencesKey("onboarding_complete")

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object Cockpit : Screen("cockpit")
    object Pairing : Screen("pairing")
    object History : Screen("history")
    object Settings : Screen("settings")
}

class MainActivity : ComponentActivity() {

    // Core ViewModels are plain Kotlin classes (not AndroidX ViewModel subclasses),
    // so we construct them via AppContainer instead of using viewModels() delegate.
    private val homeViewModel: HomeViewModel by lazy { appContainer.createHomeViewModel() }
    private val transferViewModel: TransferViewModel by lazy {
        appContainer.createTransferViewModel()
    }
    private val historyViewModel: HistoryViewModel by lazy { appContainer.createHistoryViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var isCheckingOnboarding by remember { mutableStateOf(true) }
            var startDestination by remember { mutableStateOf(Screen.Onboarding.route) }
            val context = LocalContext.current

            LaunchedEffect(Unit) {
                val complete =
                        context.dataStore.data.map { it[ONBOARDING_COMPLETE_KEY] ?: false }.first()
                if (complete) {
                    startDestination = Screen.Home.route
                } else {
                    startDestination = Screen.Onboarding.route
                }
                isCheckingOnboarding = false
            }

            if (isCheckingOnboarding) {
                return@setContent
            }

            AppNavGraph(
                    startDestination = startDestination,
                    homeViewModel = homeViewModel,
                    transferViewModel = transferViewModel,
                    historyViewModel = historyViewModel,
                    onOnboardingComplete = { startDestination = Screen.Home.route }
            )
        }

        intent?.let { handleIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            TransferForegroundService.ACTION_CONSENT_ACCEPT -> {
                transferViewModel.onConsentAccepted()
            }
            TransferForegroundService.ACTION_CONSENT_DECLINE -> {
                transferViewModel.onConsentDeclined()
            }
        }
    }
}

@Composable
fun AppNavGraph(
        startDestination: String,
        homeViewModel: HomeViewModel,
        transferViewModel: TransferViewModel,
        historyViewModel: HistoryViewModel,
        onOnboardingComplete: () -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val transferUiState by transferViewModel.uiState.collectAsState()
    val sessionState = transferUiState.sessionState

    LaunchedEffect(sessionState) {
        val serviceIntent = Intent(context, TransferForegroundService::class.java)
        when (sessionState) {
            SessionState.TRANSFERRING, SessionState.AWAITING_CONSENT -> {
                ContextCompat.startForegroundService(context, serviceIntent)
            }
            SessionState.COMPLETED, SessionState.FAILED, SessionState.CANCELLED -> {
                context.stopService(serviceIntent)
            }
            else -> {}
        }
    }

    LaunchedEffect(sessionState) {
        if (sessionState == SessionState.PAIRING) {
            navController.navigate(Screen.Pairing.route)
        }
    }

    // Collect device selection events and navigate accordingly
    LaunchedEffect(Unit) {
        homeViewModel.selectedDevice.collect { device ->
            Log.i("FluxSync", "Device selected: name=${device.deviceName}, ip=${device.ipAddress}")
            if (device.isTrusted) {
                navController.navigate(Screen.Cockpit.route)
            } else {
                navController.navigate(Screen.Pairing.route)
            }
        }
    }

    var permissionsGranted by remember {
        mutableStateOf(
                if (Build.VERSION.SDK_INT >= 33) {
                    ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.NEARBY_WIFI_DEVICES
                            ) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                }
        )
    }

    val permissionLauncher =
            rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
            ) { results -> permissionsGranted = results.values.all { it } }

    LaunchedEffect(startDestination) {
        if (startDestination == Screen.Home.route && !permissionsGranted) {
            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            NavHost(
                    navController = navController,
                    startDestination = startDestination,
            ) {
                composable(Screen.Onboarding.route) {
                    val prefs = remember { OnboardingPreferences(context) }
                    OnboardingScreen(
                            preferences = prefs,
                            onFinished = {
                                coroutineScope.launch {
                                    context.dataStore.edit { it[ONBOARDING_COMPLETE_KEY] = true }
                                }
                                onOnboardingComplete()
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                                }
                            }
                    )
                }
                composable(Screen.Home.route) {
                    val homeUiState by homeViewModel.uiState.collectAsState()
                    HomeScreen(
                            uiState = homeUiState,
                            localIpAddress = "127.0.0.1",
                            onSendFilesClick = { navController.navigate(Screen.Cockpit.route) },
                            onDeviceSelected = { device -> homeViewModel.onDeviceSelected(device) },
                            onManualIpSubmitted = { ip, port ->
                                homeViewModel.onManualIpSubmitted(ip, port)
                            }
                    )
                }
                composable(Screen.Cockpit.route) {
                    CockpitScreen(
                            state = transferUiState,
                            onPauseResume = { transferViewModel.onPauseResume() },
                            onCancel = { transferViewModel.onCancel() },
                            onAcceptConsent = { transferViewModel.onConsentAccepted() },
                            onDeclineConsent = { transferViewModel.onConsentDeclined() }
                    )
                }
                composable(Screen.Pairing.route) {
                    PairingScreen(
                            viewModel = com.fluxsync.app.pairing.PairingViewModel { pin -> },
                            isServer = false,
                            serverPin = "",
                            isPinIncorrect = false
                    )
                }
                composable(Screen.History.route) {
                    val historyState by historyViewModel.uiState.collectAsState()
                    HistoryScreen(
                            uiState = historyState,
                            onFilterChanged = {},
                            onEntryTapped = {},
                            onRetryFailedFiles = {},
                            onCopyDetails = {},
                            onDeleteEntry = {},
                            isPeerOnline = { false }
                    )
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                            state =
                                    com.fluxsync.app.settings.SettingsUiState(
                                            downloadDirectoryPath = "",
                                            orphanedFluxpartCount = 0,
                                            orphanedFluxpartTotalSizeLabel = "0 B",
                                            routingMode =
                                                    com.fluxsync.app.settings.RoutingMode
                                                            .OPPORTUNISTIC,
                                            portOverride = 5001,
                                            formattedFingerprint = "",
                                            cipherSuiteLabel = "",
                                            protocolVersionLabel = ""
                                    ),
                            onBackClick = { navController.popBackStack() },
                            onChangeDownloadDirectoryClick = {},
                            onViewAndCleanOrphansClick = {},
                            onRoutingModeChanged = {},
                            onPortOverrideChanged = {},
                            onPortOverrideCommitted = {},
                            onCopyFingerprintClick = {},
                            onManageTrustedDevicesClick = {},
                            onViewDebugLogClick = {},
                            onExportDebugLogClick = {}
                    )
                }
            }

            if (sessionState == SessionState.AWAITING_CONSENT) {
                ConsentBottomSheet(viewModel = transferViewModel)
            }
        }
    }
}
