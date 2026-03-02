package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelTelemetry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FileUiEntry(
        val fileId: Int,
        val name: String,
        val progressFraction: Float,
        val outcome: FileOutcome?,
        val hasFluxPart: Boolean,
)

enum class FileOutcome {
    COMPLETED,
    FAILED,
    PARTIAL,
}

data class TransferUiState(
        val sessionState: SessionState = SessionState.IDLE,
        val aggregateSpeedMbs: Float = 0f,
        val etaSeconds: Int = 0,
        val overallProgressFraction: Float = 0f,
        val fileEntries: List<FileUiEntry> = emptyList(),
        val channelStats: List<ChannelTelemetry> = emptyList(),
        val pendingConsentTimeoutSeconds: Int = 60,
        val consentSenderDeviceName: String = "Unknown sender",
        val consentFileSummary: String = "",
        val pendingConsentDeviceName: String = "receiver",
)

@OptIn(FlowPreview::class)
class TransferViewModel(
        private val drtlb: DRTLB,
        private val sessionMachine: SessionStateMachine,
        private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private val fileProgressMutex = Mutex()
    private val fileProgress = mutableMapOf<Int, FileProgressRecord>()

    private val pendingConsentCountdownFlow = MutableStateFlow(DEFAULT_CONSENT_TIMEOUT_SECONDS)
    private var pendingConsentCountdownJob: Job? = null
    @Volatile private var trackedTotalBytes: Long = 0L
    @Volatile private var trackedSentBytes: Long = 0L

    init {
        drtlb.telemetryFlow
                .sample(200)
                .conflate()
                .onEach { telemetry ->
                    _uiState.value =
                            _uiState.value.let { current ->
                                val updatedSpeed = telemetry.sumOf { it.throughputBytesPerSec }
                                val etaSeconds =
                                        calculateEtaSeconds(speedBytesPerSec = updatedSpeed)
                                current.copy(
                                        channelStats = telemetry,
                                        aggregateSpeedMbs =
                                                updatedSpeed.toFloat() / BYTES_PER_MEGABYTE,
                                        etaSeconds = etaSeconds,
                                )
                            }
                }
                .launchIn(scope)

        sessionMachine
                .stateFlow
                .sample(200)
                .conflate()
                .onEach { state ->
                    _uiState.value = _uiState.value.copy(sessionState = state)
                    if (state == SessionState.PENDING_CONSENT) {
                        startPendingConsentCountdown()
                    } else {
                        stopPendingConsentCountdown(reset = true)
                    }
                }
                .launchIn(scope)

        pendingConsentCountdownFlow
                .sample(200)
                .conflate()
                .onEach { seconds ->
                    _uiState.value = _uiState.value.copy(pendingConsentTimeoutSeconds = seconds)
                }
                .launchIn(scope)
    }

    fun onFilesDropped(files: List<File>) {
        scope.launch {
            fileProgressMutex.withLock {
                fileProgress.clear()
                files.forEachIndexed { index, file ->
                    fileProgress[index] =
                            FileProgressRecord(
                                    fileId = index,
                                    name = file.name,
                                    bytesWritten = 0L,
                                    totalBytes = file.length().coerceAtLeast(0L),
                                    outcome = null,
                                    hasFluxPart = hasFluxPartFile(file),
                            )
                }
            }

            refreshFileUiState()
        }
    }

    fun onPauseResume() {
        when (sessionMachine.stateFlow.value) {
            SessionState.TRANSFERRING -> sessionMachine.transition(SessionState.RETRYING)
            SessionState.RETRYING -> sessionMachine.transition(SessionState.TRANSFERRING)
            else -> Unit
        }
    }

    fun onCancel() {
        scope.launch { sessionMachine.cancel(reason = "Cancelled by user") }
    }

    fun onConsentAccepted() {
        if (sessionMachine.stateFlow.value == SessionState.PENDING_CONSENT) {
            sessionMachine.transition(SessionState.TRANSFERRING)
        }
    }

    fun onConsentDeclined() {
        scope.launch { sessionMachine.cancel(reason = "Consent declined") }
    }

    /**
     * Called by the Android receiver bridge to trigger the consent dialog.
     * Transitions the session to AWAITING_CONSENT so the ConsentBottomSheet shows.
     */
    fun showConsentDialog() {
        _uiState.value = _uiState.value.copy(
            sessionState = SessionState.AWAITING_CONSENT,
        )
    }

    fun updatePendingConsentInfo(deviceName: String, fileSummary: String) {
        _uiState.value =
                _uiState.value.copy(
                        pendingConsentDeviceName = deviceName,
                        consentSenderDeviceName = deviceName,
                        consentFileSummary = fileSummary,
                )
    }

    fun updateFileProgress(fileId: Int, bytesWritten: Long, totalBytes: Long) {
        scope.launch {
            fileProgressMutex.withLock {
                val existing =
                        requireNotNull(fileProgress[fileId]) {
                            "Unable to update progress for unknown fileId=$fileId"
                        }

                fileProgress[fileId] =
                        existing.copy(
                                bytesWritten = bytesWritten.coerceAtLeast(0L),
                                totalBytes = totalBytes.coerceAtLeast(0L),
                        )
            }

            refreshFileUiState()
        }
    }

    fun markFileComplete(fileId: Int) {
        scope.launch {
            fileProgressMutex.withLock {
                val existing =
                        requireNotNull(fileProgress[fileId]) {
                            "Unable to mark complete for unknown fileId=$fileId"
                        }

                fileProgress[fileId] =
                        existing.copy(
                                bytesWritten = existing.totalBytes,
                                outcome = FileOutcome.COMPLETED,
                        )
            }

            refreshFileUiState()
        }
    }

    fun markFileFailed(fileId: Int) {
        scope.launch {
            fileProgressMutex.withLock {
                val existing =
                        requireNotNull(fileProgress[fileId]) {
                            "Unable to mark failed for unknown fileId=$fileId"
                        }

                val outcome =
                        if (existing.bytesWritten > 0L) FileOutcome.PARTIAL else FileOutcome.FAILED
                fileProgress[fileId] = existing.copy(outcome = outcome)
            }

            refreshFileUiState()
        }
    }

    private fun startPendingConsentCountdown() {
        pendingConsentCountdownJob?.cancel()
        pendingConsentCountdownFlow.value = DEFAULT_CONSENT_TIMEOUT_SECONDS
        pendingConsentCountdownJob =
                scope.launch {
                    var secondsLeft = DEFAULT_CONSENT_TIMEOUT_SECONDS
                    while (secondsLeft > 0 &&
                            sessionMachine.stateFlow.value == SessionState.PENDING_CONSENT) {
                        delay(1_000)
                        secondsLeft -= 1
                        pendingConsentCountdownFlow.value = secondsLeft
                    }
                }
    }

    private fun stopPendingConsentCountdown(reset: Boolean) {
        pendingConsentCountdownJob?.cancel()
        pendingConsentCountdownJob = null
        if (reset) {
            pendingConsentCountdownFlow.value = DEFAULT_CONSENT_TIMEOUT_SECONDS
        }
    }

    private suspend fun refreshFileUiState() {
        val snapshot = fileProgressMutex.withLock { fileProgress.values.sortedBy { it.fileId } }

        val totalBytes = snapshot.sumOf { it.totalBytes }
        val totalWrittenBytes = snapshot.sumOf { it.bytesWritten.coerceIn(0L, it.totalBytes) }
        trackedTotalBytes = totalBytes
        trackedSentBytes = totalWrittenBytes
        val overallProgress =
                if (totalBytes > 0L) {
                    (totalWrittenBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

        _uiState.value =
                _uiState.value.let { current ->
                    val speedBytesPerSec = (current.aggregateSpeedMbs * BYTES_PER_MEGABYTE).toLong()
                    current.copy(
                            overallProgressFraction = overallProgress,
                            etaSeconds =
                                    calculateEtaSeconds(
                                            totalBytes,
                                            totalWrittenBytes,
                                            speedBytesPerSec
                                    ),
                            fileEntries = snapshot.map { it.toUiEntry() },
                    )
                }
    }

    private fun calculateEtaSeconds(speedBytesPerSec: Long): Int {
        return calculateEtaSeconds(trackedTotalBytes, trackedSentBytes, speedBytesPerSec)
    }

    private fun calculateEtaSeconds(
            totalBytes: Long,
            bytesSent: Long,
            speedBytesPerSec: Long
    ): Int {
        if (speedBytesPerSec <= 0L) return 0

        val remainingBytes = (totalBytes - bytesSent).coerceAtLeast(0L)
        return (remainingBytes / speedBytesPerSec).toInt().coerceAtLeast(0)
    }

    private fun hasFluxPartFile(file: File): Boolean {
        val parent = file.parentFile ?: return false
        return File(parent, "${file.name}.fluxpart").exists()
    }

    private data class FileProgressRecord(
            val fileId: Int,
            val name: String,
            val bytesWritten: Long,
            val totalBytes: Long,
            val outcome: FileOutcome?,
            val hasFluxPart: Boolean,
    ) {
        fun toUiEntry(): FileUiEntry {
            val progress =
                    if (totalBytes > 0L) {
                        (bytesWritten.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

            return FileUiEntry(
                    fileId = fileId,
                    name = name,
                    progressFraction = progress,
                    outcome = outcome,
                    hasFluxPart = hasFluxPart,
            )
        }
    }

    private companion object {
        private const val DEFAULT_CONSENT_TIMEOUT_SECONDS = 60
        private const val BYTES_PER_MEGABYTE = 1024f * 1024f
    }
}
