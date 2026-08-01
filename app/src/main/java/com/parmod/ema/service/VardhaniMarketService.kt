package com.parmod.ema.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.parmod.ema.MainActivity
import com.parmod.ema.R
import com.parmod.ema.data.UpstoxLiveClient
import com.parmod.ema.data.UpstoxTickStream
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the Upstox market-data socket alive while VARDHANI is not in the foreground.
 * The service is intentionally data/paper-only; broker order placement is not implemented.
 */
class VardhaniMarketService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var stream: UpstoxTickStream? = null
    private var shouldRun = false
    private var token = ""
    private var expiry = ""
    private var index = MarketIndex.NIFTY
    private var receivedTicks = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRuntime()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty().ifBlank { token }
                expiry = intent?.getStringExtra(EXTRA_EXPIRY).orEmpty().ifBlank { expiry }
                index = runCatching {
                    MarketIndex.valueOf(intent?.getStringExtra(EXTRA_INDEX).orEmpty())
                }.getOrDefault(index)
                if (token.isNotBlank() && expiry.isNotBlank()) {
                    shouldRun = true
                    startForeground(NOTIFICATION_ID, notification("Connecting ${index.name}…"))
                    connectWithRetry()
                }
            }
        }
        return START_STICKY
    }

    private fun connectWithRetry() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            var attempt = 0
            while (shouldRun) {
                try {
                    val client = UpstoxLiveClient(token)
                    val snapshot = client.fetchSnapshot(index, expiry)
                    val keys = (listOf(snapshot.underlyingKey) + snapshot.options.mapNotNull {
                        it.instrumentKey.takeIf(String::isNotBlank)
                    }).distinct()
                    stream?.disconnect()
                    stream = UpstoxTickStream(
                        authorizedUrlProvider = { client.authorizedSocketUrl() },
                        instrumentKeys = keys,
                        listener = object : UpstoxTickStream.Listener {
                            override fun onOpen() {
                                attempt = 0
                                updateNotification("${index.name} live · ${keys.size} instruments")
                            }

                            override fun onTick(tick: UpstoxTickStream.Tick) {
                                receivedTicks += 1
                                if (receivedTicks % 100L == 0L) {
                                    updateNotification("${index.name} live · $receivedTicks ticks")
                                }
                            }

                            override fun onError(message: String) {
                                updateNotification("Feed error · reconnecting")
                                stream?.disconnect()
                            }

                            override fun onClosed() {
                                if (shouldRun) connectWithRetry()
                            }
                        },
                    ).also { it.connect() }
                    return@launch
                } catch (_: Exception) {
                    attempt += 1
                    val waitMillis = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
                    updateNotification("Reconnect attempt $attempt")
                    delay(waitMillis)
                }
            }
        }
    }

    private fun stopRuntime() {
        shouldRun = false
        connectionJob?.cancel()
        connectionJob = null
        stream?.disconnect()
        stream = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VARDHANI market connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps live Upstox data and paper-trade monitoring active"
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
            .setContentTitle("VARDHANI running in background")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (shouldRun) connectWithRetry()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRuntime()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.parmod.ema.action.START_BACKGROUND_MARKET"
        const val ACTION_STOP = "com.parmod.ema.action.STOP_BACKGROUND_MARKET"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_EXPIRY = "expiry"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL_ID = "vardhani_market_service"
        private const val NOTIFICATION_ID = 7301
    }
}
