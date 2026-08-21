package com.parmod.ema.training

import com.parmod.ema.data.DataIntegrityMonitor
import com.upstox.marketdatafeederv3udapi.rpc.proto.Feed
import com.upstox.marketdatafeederv3udapi.rpc.proto.FeedResponse
import com.upstox.marketdatafeederv3udapi.rpc.proto.FullFeed
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Read-only Upstox V3 feed for AI research.
 *
 * Unlike the dashboard feed this class has no global execution-quality side effects.
 * Every decoded instrument is delivered to the dual-market coordinator which owns
 * market routing explicitly.
 */
class DualMarketResearchTickStream(
    private val authorizedUrlProvider: () -> String,
    private val instrumentKeys: List<String>,
    private val listener: Listener,
) {
    data class DepthLevel(
        val bidPrice: Double,
        val bidQty: Long,
        val askPrice: Double,
        val askQty: Long,
    )

    data class Tick(
        val instrumentKey: String,
        val ltp: Double?,
        val ltt: Long?,
        val oi: Long?,
        val delta: Double?,
        val gamma: Double?,
        val feedTimestamp: Long,
        val bid: Double? = null,
        val ask: Double? = null,
        val volume: Long? = null,
        val ltq: Long? = null,
        val totalBuyQty: Long? = null,
        val totalSellQty: Long? = null,
        val depth: List<DepthLevel> = emptyList(),
        val requestMode: String = "",
    )

    interface Listener {
        fun onOpen()
        fun onTick(tick: Tick)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val integrity = DataIntegrityMonitor()
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vardhani-ai-dual-reconnect").apply { isDaemon = true }
    }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var active = false
    @Volatile private var reconnectAttempts = 0
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var lastIntegrityNoticeMillis = 0L

    @Synchronized
    fun connect() {
        if (instrumentKeys.isEmpty()) return
        active = true
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        openSocket()
    }

    @Synchronized
    private fun openSocket() {
        if (!active) return
        try {
            val request = Request.Builder().url(authorizedUrlProvider()).build()
            socket = client.newWebSocket(request, socketListener())
        } catch (error: Exception) {
            listener.onError("AI research socket authorize/connect failed: ${error.message}")
            scheduleReconnect()
        }
    }

    private fun socketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            val requestJson = JSONObject()
                .put("guid", UUID.randomUUID().toString())
                .put("method", "sub")
                .put(
                    "data",
                    JSONObject()
                        .put("mode", REQUEST_MODE_D30)
                        .put("instrumentKeys", JSONArray(instrumentKeys)),
                )
            webSocket.send(ByteString.of(*requestJson.toString().toByteArray(Charsets.UTF_8)))
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val response = FeedResponse.parseFrom(bytes.toByteArray())
                val now = System.currentTimeMillis()
                response.feedsMap.forEach { (key, feed) ->
                    val tick = decodeTick(key, feed, response.currentTs) ?: return@forEach
                    val timestamp = tick.ltt?.takeIf { it > 0L } ?: tick.feedTimestamp
                    val report = integrity.checkLive(key, tick.ltp, timestamp, now)
                    if (report.accepted) {
                        listener.onTick(tick)
                    } else if (!report.duplicate && now - lastIntegrityNoticeMillis >= INTEGRITY_NOTICE_THROTTLE_MS) {
                        lastIntegrityNoticeMillis = now
                        listener.onError("AI DATA INTEGRITY · ${report.message} · $key")
                    }
                }
                val stalled = integrity.stalledInstruments(now)
                if (stalled.isNotEmpty() && now - lastIntegrityNoticeMillis >= INTEGRITY_NOTICE_THROTTLE_MS) {
                    lastIntegrityNoticeMillis = now
                    listener.onError("AI DATA INTEGRITY · stalled feed: ${stalled.take(3).joinToString()}")
                }
            } catch (error: Exception) {
                listener.onError("AI research tick decode failed: ${error.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!active) return
            listener.onError("AI research WebSocket dropped · reconnecting: ${t.message ?: "network failure"}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!active) {
                listener.onClosed()
                return
            }
            listener.onError("AI research WebSocket closed ($code) · reconnecting")
            scheduleReconnect()
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (!active || reconnectFuture?.isDone == false) return
        socket = null
        val attempt = ++reconnectAttempts
        val delaySeconds = min(30L, 1L shl min(attempt - 1, 4))
        reconnectFuture = reconnectScheduler.schedule({
            synchronized(this) {
                reconnectFuture = null
                if (active) openSocket()
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    @Synchronized
    fun disconnect() {
        active = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        socket?.close(1000, "AI research disconnect")
        socket = null
    }

    private fun decodeTick(key: String, feed: Feed, timestamp: Long): Tick? = when (feed.feedUnionCase) {
        Feed.FeedUnionCase.LTPC -> Tick(
            instrumentKey = key,
            ltp = feed.ltpc.ltp,
            ltt = feed.ltpc.ltt,
            oi = null,
            delta = null,
            gamma = null,
            feedTimestamp = timestamp,
            ltq = feed.ltpc.ltq,
        )
        Feed.FeedUnionCase.FIRSTLEVELWITHGREEKS -> {
            val item = feed.firstLevelWithGreeks
            Tick(
                instrumentKey = key,
                ltp = item.ltpc.ltp,
                ltt = item.ltpc.ltt,
                oi = item.oi.toLong(),
                delta = item.optionGreeks.delta,
                gamma = item.optionGreeks.gamma,
                feedTimestamp = timestamp,
                bid = item.firstDepth.bidP,
                ask = item.firstDepth.askP,
                volume = item.vtt,
                ltq = item.ltpc.ltq,
                depth = listOf(
                    DepthLevel(
                        item.firstDepth.bidP,
                        item.firstDepth.bidQ.toLong(),
                        item.firstDepth.askP,
                        item.firstDepth.askQ.toLong(),
                    ),
                ),
            )
        }
        Feed.FeedUnionCase.FULLFEED -> {
            val full = feed.fullFeed
            when (full.fullFeedUnionCase) {
                FullFeed.FullFeedUnionCase.MARKETFF -> {
                    val item = full.marketFF
                    val levels = item.marketLevel.bidAskQuoteList.take(30).map {
                        DepthLevel(
                            bidPrice = it.bidP,
                            bidQty = it.bidQ.toLong(),
                            askPrice = it.askP,
                            askQty = it.askQ.toLong(),
                        )
                    }
                    val first = levels.firstOrNull()
                    Tick(
                        instrumentKey = key,
                        ltp = item.ltpc.ltp,
                        ltt = item.ltpc.ltt,
                        oi = item.oi.toLong(),
                        delta = item.optionGreeks.delta,
                        gamma = item.optionGreeks.gamma,
                        feedTimestamp = timestamp,
                        bid = first?.bidPrice,
                        ask = first?.askPrice,
                        volume = item.vtt,
                        ltq = item.ltpc.ltq,
                        totalBuyQty = item.tbq.toLong(),
                        totalSellQty = item.tsq.toLong(),
                        depth = levels,
                        requestMode = REQUEST_MODE_D30,
                    )
                }
                FullFeed.FullFeedUnionCase.INDEXFF -> {
                    val item = full.indexFF
                    Tick(
                        instrumentKey = key,
                        ltp = item.ltpc.ltp,
                        ltt = item.ltpc.ltt,
                        oi = null,
                        delta = null,
                        gamma = null,
                        feedTimestamp = timestamp,
                        ltq = item.ltpc.ltq,
                        requestMode = REQUEST_MODE_D30,
                    )
                }
                else -> null
            }
        }
        else -> null
    }

    companion object {
        private const val INTEGRITY_NOTICE_THROTTLE_MS = 5_000L
        private const val REQUEST_MODE_D30 = "full_d30"
    }
}
