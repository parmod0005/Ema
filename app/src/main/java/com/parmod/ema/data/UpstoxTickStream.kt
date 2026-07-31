package com.parmod.ema.data

import com.upstox.marketdatafeederv3udapi.rpc.proto.Feed
import com.upstox.marketdatafeederv3udapi.rpc.proto.FeedResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Read-only Market Data Feed V3 client. It contains no broker order APIs. */
class UpstoxTickStream(
    private val authorizedUrlProvider: () -> String,
    private val instrumentKeys: List<String>,
    private val listener: Listener,
) {
    data class Tick(
        val instrumentKey: String,
        val ltp: Double?,
        val ltt: Long?,
        val oi: Long?,
        val delta: Double?,
        val gamma: Double?,
        val feedTimestamp: Long,
    )

    interface Listener {
        fun onOpen()
        fun onTick(tick: Tick)
        fun onError(message: String)
        fun onClosed()
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private var socket: WebSocket? = null

    fun connect() {
        val url = authorizedUrlProvider()
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val requestJson = JSONObject()
                    .put("guid", UUID.randomUUID().toString())
                    .put("method", "sub")
                    .put("data", JSONObject()
                        .put("mode", "full")
                        .put("instrumentKeys", JSONArray(instrumentKeys)))
                webSocket.send(ByteString.of(*requestJson.toString().toByteArray(Charsets.UTF_8)))
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val response = FeedResponse.parseFrom(bytes.toByteArray())
                    response.feedsMap.forEach { (key, feed) ->
                        decodeTick(key, feed, response.currentTs)?.let(listener::onTick)
                    }
                } catch (error: Exception) {
                    listener.onError("Tick decode failed: ${error.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Upstox WebSocket failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }
        })
    }

    fun disconnect() {
        socket?.close(1000, "Client disconnect")
        socket = null
    }

    private fun decodeTick(key: String, feed: Feed, timestamp: Long): Tick? {
        return when (feed.feedUnionCase) {
            Feed.FeedUnionCase.LTPC -> Tick(key, feed.ltpc.ltp, feed.ltpc.ltt, null, null, null, timestamp)
            Feed.FeedUnionCase.FIRSTLEVELWITHGREEKS -> {
                val item = feed.firstLevelWithGreeks
                Tick(key, item.ltpc.ltp, item.ltpc.ltt, item.oi.toLong(), item.optionGreeks.delta, item.optionGreeks.gamma, timestamp)
            }
            Feed.FeedUnionCase.FULLFEED -> {
                val full = feed.fullFeed
                when (full.fullFeedUnionCase) {
                    com.upstox.marketdatafeederv3udapi.rpc.proto.FullFeed.FullFeedUnionCase.MARKETFF -> {
                        val item = full.marketFF
                        Tick(key, item.ltpc.ltp, item.ltpc.ltt, item.oi.toLong(), item.optionGreeks.delta, item.optionGreeks.gamma, timestamp)
                    }
                    com.upstox.marketdatafeederv3udapi.rpc.proto.FullFeed.FullFeedUnionCase.INDEXFF -> {
                        val item = full.indexFF
                        Tick(key, item.ltpc.ltp, item.ltpc.ltt, null, null, null, timestamp)
                    }
                    else -> null
                }
            }
            else -> null
        }
    }
}
