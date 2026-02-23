package com.fluxsync.core.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionState {
    IDLE,
    CONNECTING,
    HANDSHAKING,
    PAIRING,
    AWAITING_CONSENT,
    PENDING_CONSENT,
    TRANSFERRING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

class SessionStateMachine(
    private val sessionId: Long,
    private val scope: CoroutineScope,
    private val onCancel: suspend (reason: String) -> Unit,
    private val onComplete: suspend () -> Unit,
    private val transferHistoryManager: TransferHistoryManager? = null,
    private val transferHistorySnapshotProvider: (() -> TransferHistorySessionSnapshot)? = null,
) {
    private val stateMutex = Mutex()
    private val _stateFlow = MutableStateFlow(SessionState.IDLE)
    val stateFlow: StateFlow<SessionState> = _stateFlow.asStateFlow()

    private var heartbeatWatchdogJob: Job? = null

    fun transition(newState: SessionState) {
        val current = _stateFlow.value
        val validNextStates = VALID_TRANSITIONS[current].orEmpty()
        check(newState in validNextStates) {
            "Invalid session state transition for session $sessionId: $current -> $newState. " +
                "Allowed next states: ${validNextStates.joinToString()}"
        }

        _stateFlow.value = newState
    }

    fun onStateAdvancingPacketReceived() {
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob = scope.launch {
            delay(HEARTBEAT_TIMEOUT_MS)
            cancel("Heartbeat timeout")
        }
    }

    suspend fun cancel(reason: String) {
        val shouldInvokeCancel = stateMutex.withLock {
            val current = _stateFlow.value
            if (current == SessionState.CANCELLED || current == SessionState.COMPLETED) {
                false
            } else {
                _stateFlow.value = SessionState.CANCELLED
                heartbeatWatchdogJob?.cancel()
                heartbeatWatchdogJob = null
                true
            }
        }

        if (shouldInvokeCancel) {
            persistHistoryIfConfigured(outcome = TransferOutcome.CANCELLED)
            onCancel(reason)
        }
    }

    suspend fun complete() {
        val shouldInvokeComplete = stateMutex.withLock {
            val current = _stateFlow.value
            if (current == SessionState.CANCELLED || current == SessionState.COMPLETED) {
                false
            } else {
                _stateFlow.value = SessionState.COMPLETED
                heartbeatWatchdogJob?.cancel()
                heartbeatWatchdogJob = null
                true
            }
        }

        if (shouldInvokeComplete) {
            persistHistoryIfConfigured(outcome = TransferOutcome.COMPLETED)
            onComplete()
        }
    }

    private suspend fun persistHistoryIfConfigured(outcome: TransferOutcome) {
        val manager = transferHistoryManager ?: return
        val snapshotProvider = transferHistorySnapshotProvider ?: return
        manager.recordSessionEnd(snapshot = snapshotProvider(), outcome = outcome)
    }

    private companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 10_000L

        private val VALID_TRANSITIONS: Map<SessionState, Set<SessionState>> = mapOf(
            SessionState.IDLE to setOf(SessionState.CONNECTING),
            SessionState.CONNECTING to setOf(SessionState.HANDSHAKING, SessionState.FAILED, SessionState.CANCELLED),
            SessionState.HANDSHAKING to setOf(
                SessionState.PAIRING,
                SessionState.AWAITING_CONSENT,
                SessionState.PENDING_CONSENT,
                SessionState.TRANSFERRING,
                SessionState.FAILED,
                SessionState.CANCELLED,
            ),
            SessionState.PAIRING to setOf(
                SessionState.AWAITING_CONSENT,
                SessionState.PENDING_CONSENT,
                SessionState.TRANSFERRING,
                SessionState.FAILED,
                SessionState.CANCELLED,
            ),
            SessionState.AWAITING_CONSENT to setOf(SessionState.TRANSFERRING, SessionState.FAILED, SessionState.CANCELLED),
            SessionState.PENDING_CONSENT to setOf(SessionState.TRANSFERRING, SessionState.FAILED, SessionState.CANCELLED),
            SessionState.TRANSFERRING to setOf(
                SessionState.RETRYING,
                SessionState.COMPLETED,
                SessionState.FAILED,
                SessionState.CANCELLED,
            ),
            SessionState.RETRYING to setOf(
                SessionState.TRANSFERRING,
                SessionState.COMPLETED,
                SessionState.FAILED,
                SessionState.CANCELLED,
            ),
            SessionState.COMPLETED to emptySet(),
            SessionState.FAILED to setOf(SessionState.CANCELLED),
            SessionState.CANCELLED to emptySet(),
        )
    }
}
