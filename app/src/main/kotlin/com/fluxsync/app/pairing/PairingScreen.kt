package com.fluxsync.app.pairing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

fun interface PairingViewModel {
    fun onPinEntered(pin: String)
}

@Composable
fun PairingScreen(
    viewModel: PairingViewModel,
    isServer: Boolean,
    serverPin: String = "",
    isPinIncorrect: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val digits = remember { mutableStateListOf<Int?>(null, null, null, null) }
    var shakeTarget by remember { mutableFloatStateOf(0f) }
    var submittedPin by remember { mutableStateOf<String?>(null) }
    val shakeX by animateFloatAsState(
        targetValue = shakeTarget,
        animationSpec = spring(stiffness = 600f, dampingRatio = 0.4f),
        label = "pin-shake",
    )

    LaunchedEffect(isPinIncorrect) {
        if (isPinIncorrect && !isServer) {
            val sequence = listOf(-18f, 18f, -12f, 12f, -6f, 6f, 0f)
            sequence.forEach { target ->
                shakeTarget = target
                delay(40)
            }
            repeat(digits.size) { index -> digits[index] = null }
            submittedPin = null
        }
    }

    if (!isServer) {
        val pin = digits.joinToString(separator = "") { it?.toString() ?: "" }
        LaunchedEffect(pin) {
            if (pin.length == digits.size && pin != submittedPin) {
                submittedPin = pin
                viewModel.onPinEntered(digits.joinToString(separator = "") { it.toString() })
            }
            if (pin.length < digits.size) {
                submittedPin = null
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = if (isServer) "Your PIN: ${serverPin.padEnd(4, '_').take(4)}" else "Enter PIN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        if (isServer) {
            Text(
                text = "Waiting for other device...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.offset(x = shakeX.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(4) { index ->
                val digit = if (isServer) serverPin.getOrNull(index)?.toString() else digits[index]?.toString()
                PinDigitBox(value = digit ?: "_")
            }
        }

        if (!isServer) {
            NumericKeypad(
                onDigitTapped = { digit ->
                    val firstEmpty = digits.indexOfFirst { it == null }
                    if (firstEmpty >= 0) {
                        digits[firstEmpty] = digit
                    }
                },
                onBackspaceTapped = {
                    val lastFilled = digits.indexOfLast { it != null }
                    if (lastFilled >= 0) {
                        digits[lastFilled] = null
                    }
                },
            )
        }
    }
}

@Composable
private fun PinDigitBox(value: String) {
    OutlinedCard(
        modifier = Modifier.size(width = 64.dp, height = 80.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = value,
                fontSize = 48.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigitTapped: (Int) -> Unit,
    onBackspaceTapped: () -> Unit,
) {
    val rows: List<List<String>> = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    when (key) {
                        "" -> Box(modifier = Modifier.size(width = 88.dp, height = 64.dp))
                        "⌫" -> {
                            FilledTonalButton(
                                onClick = onBackspaceTapped,
                                modifier = Modifier.size(width = 88.dp, height = 64.dp),
                            ) {
                                Text(text = key, fontSize = 24.sp)
                            }
                        }

                        else -> {
                            FilledTonalButton(
                                onClick = { onDigitTapped(key.toInt()) },
                                modifier = Modifier.size(width = 88.dp, height = 64.dp),
                            ) {
                                Text(text = key, fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PairingScreenClientPreview() {
    PairingScreen(
        viewModel = PairingViewModel { },
        isServer = false,
    )
}

@Preview(showBackground = true)
@Composable
private fun PairingScreenServerPreview() {
    PairingScreen(
        viewModel = PairingViewModel { },
        isServer = true,
        serverPin = "4821",
    )
}
