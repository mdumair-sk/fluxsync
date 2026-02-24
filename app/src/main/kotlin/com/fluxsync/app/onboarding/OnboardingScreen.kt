package com.fluxsync.app.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

private const val TOTAL_STEPS = 4

@Composable
fun OnboardingScreen(
    preferences: OnboardingPreferences,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }

    var isBatteryExempt by remember {
        mutableStateOf(context.isIgnoringBatteryOptimizations())
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        selectedTreeUri = uri
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        step = (step + 1).coerceAtMost(TOTAL_STEPS - 1)
    }

    LaunchedEffect(Unit) {
        if (preferences.isCompleted.first()) {
            onFinished()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        when (step) {
            0 -> WelcomeStep(onGetStarted = { step++ })
            1 -> DropZoneStep(
                selectedPath = selectedTreeUri?.toString(),
                onChooseFolder = { folderPickerLauncher.launch(null) },
                onContinue = { step++ },
            )

            2 -> BatteryStep(
                isExempt = isBatteryExempt,
                onExemptFluxSync = {
                    context.requestBatteryOptimizationExemption()
                    isBatteryExempt = context.isIgnoringBatteryOptimizations()
                    if (isBatteryExempt) {
                        step++
                    }
                },
                onContinue = { if (isBatteryExempt) step++ },
            )

            3 -> NotificationStep(
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        step = TOTAL_STEPS
                    }
                },
                onSkip = { step = TOTAL_STEPS },
            )
        }

        StepIndicator(
            currentStep = step.coerceAtMost(TOTAL_STEPS - 1),
            totalSteps = TOTAL_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }

    LaunchedEffect(step) {
        if (step >= TOTAL_STEPS) {
            preferences.markCompleted()
            onFinished()
        }
    }
}

@Composable
private fun WelcomeStep(
    onGetStarted: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "Welcome to FluxSync", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Need to share a movie? Lemme FluxSync it to you!",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onGetStarted) {
            Text(text = "Get Started", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun DropZoneStep(
    selectedPath: String?,
    onChooseFolder: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "Choose your drop zone", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Pick the folder where FluxSync should store incoming files.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onChooseFolder) {
            Text(text = "Choose Folder", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            text = selectedPath?.let { "Selected: $it" } ?: "No folder selected yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onContinue,
            enabled = selectedPath != null,
        ) {
            Text(text = "Continue", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun BatteryStep(
    isExempt: Boolean,
    onExemptFluxSync: () -> Unit,
    onContinue: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF3CD))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Battery Optimization",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "Your phone's battery saver will kill high-speed transfers.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = onExemptFluxSync,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(text = "Exempt FluxSync", style = MaterialTheme.typography.labelLarge)
            }
            if (isExempt) {
                Text(
                    text = "✓ FluxSync is already exempt.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onContinue) {
                    Text(text = "Continue", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun NotificationStep(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "Notifications", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "FluxSync needs notifications so active transfers remain visible and controllable.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAllow) {
                Text(text = "Allow", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onSkip) {
                Text(text = "Skip", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalSteps) { index ->
            val color = if (index == currentStep) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .background(color = color, shape = CircleShape)
                    .width(10.dp)
                    .height(10.dp),
            )
        }
    }
}

private fun android.content.Context.requestBatteryOptimizationExemption() {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

private fun android.content.Context.isIgnoringBatteryOptimizations(): Boolean {
    val powerManager = getSystemService(PowerManager::class.java)
    return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
}

