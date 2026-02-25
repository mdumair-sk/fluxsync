package com.fluxsync.desktop.app.tray

import com.fluxsync.core.transfer.SessionState
import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

private const val TOOLTIP_IDLE = "FluxSync — Ready"
private const val AMBER_HEX = 0xF09D3D
private const val BLUE_HEX = 0x3D85F0

interface TrayUiState {
    val sessionState: SessionState
    val aggregateSpeedMbs: Float
    val overallProgressFraction: Float
    val isPaused: Boolean
}

class DesktopTrayManager<T : TrayUiState>(
    uiState: StateFlow<T>,
    private val scope: CoroutineScope,
    private val onOpenFluxSync: () -> Unit,
    private val onSendFile: () -> Unit,
    private val onPause: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private val tray = SystemTray.getSystemTray()

    private val openItem = MenuItem("Open FluxSync")
    private val sendFileItem = MenuItem("Send File")
    private val pauseItem = MenuItem("Pause")
    private val quitItem = MenuItem("Quit")

    private val trayIcon = TrayIcon(createPulseFrame(Color(AMBER_HEX)), "FluxSync")

    private var collectJob: Job? = null
    private var pulseJob: Job? = null

    init {
        require(SystemTray.isSupported()) { "System tray is not supported on this platform." }

        openItem.addActionListener { onOpenFluxSync() }
        sendFileItem.addActionListener { onSendFile() }
        pauseItem.addActionListener { onPause() }
        quitItem.addActionListener { onQuit() }

        trayIcon.isImageAutoSize = true
        trayIcon.popupMenu = PopupMenu().apply {
            add(openItem)
            add(sendFileItem)
            addSeparator()
            add(pauseItem)
            addSeparator()
            add(quitItem)
        }
        trayIcon.toolTip = TOOLTIP_IDLE
        tray.add(trayIcon)

        collectJob = scope.launch {
            uiState.collectLatest { state ->
                withContextOnSwing {
                    pauseItem.isEnabled = state.sessionState == SessionState.TRANSFERRING
                    trayIcon.toolTip = buildTooltip(state)
                }

                if (state.sessionState == SessionState.TRANSFERRING) {
                    startPulse()
                } else {
                    stopPulse()
                    withContextOnSwing {
                        trayIcon.image = createPulseFrame(Color(BLUE_HEX))
                    }
                }
            }
        }
    }

    fun dispose() {
        collectJob?.cancel()
        stopPulse()
        tray.remove(trayIcon)
    }

    private fun startPulse() {
        if (pulseJob?.isActive == true) return

        pulseJob = scope.launch(Dispatchers.Swing) {
            var amber = true
            while (true) {
                trayIcon.image = createPulseFrame(if (amber) Color(AMBER_HEX) else Color(BLUE_HEX))
                amber = !amber
                delay(500)
            }
        }
    }

    private fun stopPulse() {
        pulseJob?.cancel()
        pulseJob = null
    }

    private fun buildTooltip(state: T): String {
        val pct = (state.overallProgressFraction * 100f).toInt().coerceIn(0, 100)
        return when {
            state.isPaused -> "FluxSync — Paused (${pct}%)"
            state.sessionState == SessionState.TRANSFERRING -> {
                val speed = String.format("%.1f", state.aggregateSpeedMbs)
                "Syncing... ${pct}% at $speed MB/s"
            }
            else -> TOOLTIP_IDLE
        }
    }

    private fun createPulseFrame(fillColor: Color): Image {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0, 0, 0, 0)
        graphics.fillRect(0, 0, 16, 16)
        graphics.color = fillColor
        graphics.fillOval(2, 2, 12, 12)
        graphics.color = fillColor.darker()
        graphics.drawOval(2, 2, 12, 12)
        graphics.dispose()
        return image
    }

    private suspend fun withContextOnSwing(block: () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Swing) {
            block()
        }
    }
}
