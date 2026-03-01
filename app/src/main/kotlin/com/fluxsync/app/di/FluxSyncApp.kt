package com.fluxsync.app.di

import android.app.Application
import android.content.Context
import android.os.Build
import com.fluxsync.core.transfer.DebugLog
import com.fluxsync.core.transfer.LogLevel
import kotlinx.coroutines.launch

/**
 * FluxSync [Application] subclass.
 *
 * This is the **only** place where [AppContainer] is constructed. [MainActivity] and all ViewModels
 * receive their dependencies from [container] via the [Context.appContainer] extension property.
 */
class FluxSyncApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Build the DI graph
        container = AppContainer(applicationContext)

        // 2. Start mDNS discovery (non-blocking)
        container.appScope.launch {
            val cert =
                    runCatching { container.certificateManager.getOrCreateCertificate(Build.MODEL) }
                            .getOrNull()
            val fingerprint = cert?.sha256Fingerprint ?: "unknown"
            container.mdnsDiscovery.registerSelf(Build.MODEL, 5001, fingerprint)
            container.mdnsDiscovery.startDiscovery()
        }

        // 3. Start transfer server (non-blocking)
        container.transferServer.start()

        // 4. Log app start
        DebugLog.log(LogLevel.INFO, TAG, "FluxSync started")
    }

    /**
     * NOTE: [onTerminate] is **not guaranteed** to be called by Android on production devices.
     * Critical cleanup (e.g. WakeLock release) is handled defensively in
     * [TransferForegroundService.onDestroy] regardless of whether this fires.
     */
    override fun onTerminate() {
        container.teardown()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "FluxSyncApp"
    }
}

/**
 * Convenience extension — use in [MainActivity] and anywhere [Context] is available:
 * ```kotlin
 * val container = context.appContainer
 * ```
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as FluxSyncApp).container
