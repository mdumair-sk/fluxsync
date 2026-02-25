package com.fluxsync.app.di

import android.content.Context
import com.fluxsync.android.data.network.AndroidMdnsDiscovery
import com.fluxsync.android.data.storage.AndroidFileAccess
import com.fluxsync.app.data.AndroidHistoryRepository
import com.fluxsync.app.data.FluxSyncDatabase
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkSizeNegotiator
import com.fluxsync.core.security.CertificateManager
import com.fluxsync.core.security.TrustStore
import com.fluxsync.core.transfer.DRTLB
import com.fluxsync.core.transfer.DebugLog
import com.fluxsync.core.transfer.HistoryViewModel
import com.fluxsync.core.transfer.HomeViewModel
import com.fluxsync.core.transfer.LogLevel
import com.fluxsync.core.transfer.SessionStateMachine
import com.fluxsync.core.transfer.TransferHistoryRepository
import com.fluxsync.core.transfer.TransferViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel

/**
 * Manual DI service-locator. Constructed once in [FluxSyncApp.onCreate] and lives for the entire
 * application lifetime.
 *
 * **Invariants:**
 * - Only holds [applicationContext] — never an Activity or Service context.
 * - [appScope] uses [SupervisorJob] so child failures don't cancel the entire scope.
 */
class AppContainer(private val context: Context) {

    // ── App-scoped coroutine scope ──────────────────────────────────────────
    // Survives Activity recreations; cancelled in [teardown].
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Singletons ──────────────────────────────────────────────────────────

    val certificateManager: CertificateManager by lazy { CertificateManager() }

    val trustStore: TrustStore by lazy { AndroidTrustStore(context) }

    val fileAccess: AndroidFileAccess by lazy { AndroidFileAccess(context) }

    val mdnsDiscovery: AndroidMdnsDiscovery by lazy { AndroidMdnsDiscovery(context) }

    val debugLog: DebugLog
        get() = DebugLog // singleton object

    private val database: FluxSyncDatabase by lazy { FluxSyncDatabase.build(context) }

    val historyRepository: TransferHistoryRepository by lazy {
        AndroidHistoryRepository(database.historyDao())
    }

    // ── ViewModel Construction ───────────────────────────────────────────
    //
    // Core ViewModels are plain Kotlin classes (not AndroidX ViewModel subclasses).
    // We provide construction functions instead of ViewModelProvider.Factory.

    fun createHomeViewModel(): HomeViewModel = HomeViewModel(trustStore, appScope)

    /**
     * Creates a **fresh** [DRTLB] and [SessionStateMachine] for each transfer session.
     *
     * These are NOT singletons — a new instance is created every time this function is invoked
     * (i.e. every time the user starts a new transfer session from the UI).
     */
    fun createTransferViewModel(): TransferViewModel {
        val sessionId = System.currentTimeMillis()
        val retrySlot = Channel<ChunkPacket>(capacity = 64)
        val chunkSource =
                Channel<ChunkPacket>(
                        capacity = ChunkSizeNegotiator.queueCapacity(ChunkSizeNegotiator.NORMAL),
                )
        val drtlb = DRTLB(chunkSource, retrySlot)
        val sessionMachine =
                SessionStateMachine(
                        sessionId = sessionId,
                        scope = appScope,
                        onCancel = { reason ->
                            DebugLog.log(LogLevel.WARN, "Session", "Cancelled: $reason")
                        },
                        onComplete = { DebugLog.log(LogLevel.INFO, "Session", "Complete") },
                )
        return TransferViewModel(drtlb, sessionMachine, appScope)
    }

    fun createHistoryViewModel(): HistoryViewModel = HistoryViewModel(historyRepository, appScope)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Cancel the [appScope]. Called from [FluxSyncApp.onTerminate]. */
    fun teardown() {
        appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
