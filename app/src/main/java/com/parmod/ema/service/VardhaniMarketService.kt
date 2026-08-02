package com.parmod.ema.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.parmod.ema.MainActivity
import com.parmod.ema.R

/**
 * Foreground process keeper for the personal paper-trading build.
 *
 * The TradingViewModel is the single owner of the Upstox WebSocket. This service
 * deliberately does not open another broker connection; it only keeps the
 * process scheduled while the app is minimized or the screen is locked.
 */
class VardhaniMarketService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var shouldRun = false
    private var indexName = "NIFTY"
    private var expiry = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VARDHANI:PaperRuntime",
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                indexName = intent?.getStringExtra(EXTRA_INDEX).orEmpty().ifBlank { indexName }
                expiry = intent?.getStringExtra(EXTRA_EXPIRY).orEmpty().ifBlank { expiry }
                shouldRun = true
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(12 * 60 * 60 * 1000L)
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification("$indexName paper runtime active${expiry.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"),
                )
            }
        }
        return START_STICKY
    }

    private fun stopRuntime() {
        shouldRun = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VARDHANI paper runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps live-data paper monitoring active while minimized"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VardhaniMarketService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.vardhani_logo)
            .setContentTitle("VARDHANI live paper session")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRun) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("$indexName paper runtime active · app minimized"),
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
        const val EXTRA_TOKEN = "token" // retained for backwards-compatible intents; never read here
        const val EXTRA_EXPIRY = "expiry"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL_ID = "vardhani_market_service"
        private const val NOTIFICATION_ID = 7301
    }
}
