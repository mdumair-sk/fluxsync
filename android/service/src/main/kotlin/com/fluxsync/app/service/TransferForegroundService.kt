package com.fluxsync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.fluxsync.android.service.R
import com.fluxsync.core.protocol.ChannelTelemetry
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.transfer.DRTLB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max

class TransferForegroundService : Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildTransferNotification())

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(30 * 60 * 1000L) }

        return START_STICKY
    }

    override fun onDestroy() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun bindTelemetry(drtlb: DRTLB) {
        serviceScope.launch {
            drtlb.telemetryFlow.collectLatest { telemetry ->
                val wifiWeight = telemetry.firstOrNull { it.type == ChannelType.WIFI }?.weightFraction ?: 0f
                val usbWeight = telemetry.firstOrNull { it.type == ChannelType.USB_ADB }?.weightFraction ?: 0f
                val totalThroughputBps = telemetry.sumOf(ChannelTelemetry::throughputBytesPerSec)
                val speedMbs = totalThroughputBps.toFloat() / BYTES_PER_MB

                val normalized = normalizeWeights(wifiWeight, usbWeight)
                val notification = buildTransferNotification(
                    wifiPercent = normalized.first,
                    usbPercent = normalized.second,
                    speedMbs = speedMbs,
                )
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification)
            }
        }
    }

    fun buildTransferNotification(
        wifiPercent: Float = 0f,
        usbPercent: Float = 0f,
        speedMbs: Float = 0f,
    ): Notification {
        val safeWifi = wifiPercent.coerceIn(0f, 1f)
        val safeUsb = usbPercent.coerceIn(0f, 1f)
        val remoteViews = RemoteViews(packageName, R.layout.notification_transfer).apply {
            setTextViewText(R.id.tv_speed, String.format("%.1f MB/s", speedMbs.coerceAtLeast(0f)))
            setInt(R.id.wifi_bar, "setLayoutWeight", max(1, (safeWifi * 100).toInt()))
            setInt(R.id.usb_bar, "setLayoutWeight", max(1, (safeUsb * 100).toInt()))
        }

        return NotificationCompat.Builder(this, TRANSFER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .build()
    }

    fun buildConsentNotification(
        senderName: String,
        fileCount: Int,
        totalSizeFormatted: String,
    ): Notification {
        val fullScreenLaunchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = ACTION_OPEN_CONSENT
        } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONSENT_FULL_SCREEN,
            fullScreenLaunchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val acceptIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_ACCEPT,
            Intent(ACTION_CONSENT_ACCEPT).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declineIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_CODE_DECLINE,
            Intent(ACTION_CONSENT_DECLINE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = "$fileCount files · $totalSizeFormatted"

        return NotificationCompat.Builder(this, CONSENT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Incoming transfer from $senderName")
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(0, "Accept", acceptIntent)
            .addAction(0, "Decline", declineIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val transferChannel = NotificationChannel(
            TRANSFER_CHANNEL_ID,
            "Transfer progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows ongoing FluxSync transfer speed and channel balance"
            setShowBadge(false)
        }

        val consentChannel = NotificationChannel(
            CONSENT_CHANNEL_ID,
            "Incoming transfer requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Heads-up prompts requiring transfer consent"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannels(listOf(transferChannel, consentChannel))
    }

    private fun normalizeWeights(wifi: Float, usb: Float): Pair<Float, Float> {
        val sum = (wifi + usb).coerceAtLeast(0f)
        if (sum == 0f) {
            return 0.5f to 0.5f
        }
        return wifi / sum to usb / sum
    }

    companion object {
        const val NOTIFICATION_ID: Int = 1001
        const val TRANSFER_CHANNEL_ID: String = "fluxsync_transfer"
        const val CONSENT_CHANNEL_ID: String = "fluxsync_consent"

        const val ACTION_OPEN_CONSENT: String = "com.fluxsync.app.action.OPEN_CONSENT"
        const val ACTION_CONSENT_ACCEPT: String = "com.fluxsync.app.action.CONSENT_ACCEPT"
        const val ACTION_CONSENT_DECLINE: String = "com.fluxsync.app.action.CONSENT_DECLINE"

        private const val WAKE_LOCK_TAG = "FluxSync::TransferLock"
        private const val REQUEST_CODE_CONSENT_FULL_SCREEN = 3001
        private const val REQUEST_CODE_ACCEPT = 3002
        private const val REQUEST_CODE_DECLINE = 3003
        private const val BYTES_PER_MB = 1024f * 1024f
    }
}

fun requestBatteryOptimizationExemption(context: Context) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        return
    }

    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
