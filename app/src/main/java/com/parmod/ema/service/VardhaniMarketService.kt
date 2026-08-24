package com.parmod.ema.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.parmod.ema.R
import com.parmod.ema.VardhaniEntryActivity

/**
 * Foreground process/network keeper for the full VARDHANI runtime.
 *
 * Signal engines and broker-order authority live outside this service. It only keeps
 * the process/network scheduled while the phone is minimized or locked. The service
 * is stopped from the guarded in-app disconnect flow, not from an unguarded notification.
 */
class VardhaniMarketService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var shouldRun = false
    private var marketName = "NIFTY"
    private var expiry = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VARDHANI:MarketRuntime",
        ).apply { setReferenceCounted(false) }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "VARDHANI:MarketDataWifi",
        )?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                marketName = intent?.getStringExtra(EXTRA_INDEX).orEmpty().ifBlank { marketName }
                expiry = intent?.getStringExtra(EXTRA_EXPIRY).orEmpty().ifBlank { expiry }
                shouldRun = true
                acquireRuntimeLocks()
                startForeground(
                    NOTIFICATION_ID,
                    notification("$marketName runtime active${expiry.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"),
                )
            }
        }
        return START_STICKY
    }

    private fun acquireRuntimeLocks() {
        if (wakeLock?.isHeld != true) wakeLock?.acquire(12 * 60 * 60 * 1000L)
        if (wifiLock?.isHeld != true) runCatching { wifiLock?.acquire() }
    }

    private fun stopRuntime() {
        shouldRun = false
        if (wifiLock?.isHeld == true) runCatching { wifiLock?.release() }
        if (wakeLock?.isHeld == true) runCatching { wakeLock?.release() }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VARDHANI market runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps VARDHANI live market monitoring active while minimized"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VardhaniEntryActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.vardhani_logo)
            .setContentTitle("VARDHANI market session")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRun) {
            acquireRuntimeLocks()
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("$marketName runtime active · app minimized"),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRuntime()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.parmod.ema.action.START_BACKGROUND_MARKET"
        const val ACTION_STOP = "com.parmod.ema.action.STOP_BACKGROUND_MARKET"
        const val EXTRA_TOKEN = "token" // legacy intent compatibility; never read here
        const val EXTRA_EXPIRY = "expiry"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL_ID = "vardhani_market_service"
        private const val NOTIFICATION_ID = 7301
    }
}
