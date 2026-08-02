package com.parmod.ema.backtest

import com.parmod.ema.model.MarketIndex
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.min

/** Read-only Upstox Plus client for expired derivatives research. */
class UpstoxPlusHistoricalClient(
    private val accessToken: String,
    private val minimumRequestSpacingMillis: Long = 350L,
    private val maximumAttempts: Int = 6,
) {
    data class ExpiredContract(
        val instrumentKey: String,
        val expiry: LocalDate,
        val strike: Double,
        val optionType: String,
        val lotSize: Int,
        val tradingSymbol: String,
    )

    data class Candle(
        val time: OffsetDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
        val openInterest: Long,
    )

    data class RequestStats(
        val requests: Long = 0,
        val retries: Long = 0,
        val rateLimits: Long = 0,
    )

    @Volatile
    private var stats = RequestStats()
    private val throttleLock = Any()
    private var lastRequestStartedMillis = 0L

    fun requestStats(): RequestStats = stats

    fun getExpiries(index: MarketIndex): List<LocalDate> {
        val key = encodedUnderlying(index)
        val json = get("https://api.upstox.com/v2/expired-instruments/expiries?instrument_key=$key")
        val data = json.getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                runCatching { LocalDate.parse(data.getString(i)) }.getOrNull()?.let(::add)
            }
        }.distinct().sorted()
    }

    fun getExpiredOptionContracts(index: MarketIndex, expiry: LocalDate): List<ExpiredContract> {
        val key = encodedUnderlying(index)
        val json = get(
            "https://api.upstox.com/v2/expired-instruments/option/contract" +
                "?instrument_key=$key&expiry_date=$expiry",
        )
        val data = json.getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val type = item.optString("instrument_type")
                if (type != "CE" && type != "PE") continue
                add(
                    ExpiredContract(
                        instrumentKey = item.getString("instrument_key"),
                        expiry = LocalDate.parse(item.getString("expiry")),
                        strike = item.getDouble("strike_price"),
                        optionType = type,
                        lotSize = item.optInt("lot_size", item.optInt("minimum_lot", 1)),
                        tradingSymbol = item.optString("trading_symbol"),
                    ),
                )
            }
        }
    }

    fun getExpiredCandles(
        expiredInstrumentKey: String,
        interval: String,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<Candle> {
        require(interval in setOf("1minute", "3minute", "5minute", "15minute", "30minute", "day")) {
            "Unsupported expired-candle interval: $interval"
        }
        require(!fromDate.isAfter(toDate)) { "fromDate must not be after toDate" }
        val key = URLEncoder.encode(expiredInstrumentKey, Charsets.UTF_8.name())
        val json = get(
            "https://api.upstox.com/v2/expired-instruments/historical-candle/" +
                "$key/$interval/$toDate/$fromDate",
        )
        val candles = json.getJSONObject("data").getJSONArray("candles")
        return buildList {
            for (i in 0 until candles.length()) {
                val row = candles.getJSONArray(i)
                add(
                    Candle(
                        time = OffsetDateTime.parse(row.getString(0)),
                        open = row.getDouble(1),
                        high = row.getDouble(2),
                        low = row.getDouble(3),
                        close = row.getDouble(4),
                        volume = row.optLong(5, 0L),
                        openInterest = row.optLong(6, 0L),
                    ),
                )
            }
        }.sortedBy { it.time }
    }

    private fun encodedUnderlying(index: MarketIndex): String {
        val underlying = when (index) {
            MarketIndex.NIFTY -> "NSE_INDEX|Nifty 50"
            MarketIndex.SENSEX -> "BSE_INDEX|SENSEX"
        }
        return URLEncoder.encode(underlying, Charsets.UTF_8.name())
    }

    private fun get(url: String): JSONObject {
        require(accessToken.isNotBlank()) { "Upstox access token is required" }
        require(maximumAttempts >= 1) { "maximumAttempts must be positive" }

        var lastFailure: String = "Unknown Upstox error"
        for (attempt in 1..maximumAttempts) {
            awaitRequestSlot()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                stats = stats.copy(requests = stats.requests + 1)

                if (code in 200..299) return JSONObject(body)
                lastFailure = "Upstox HTTP $code: ${body.take(500)}"

                val retryable = code == 429 || code == 408 || code in 500..599
                if (!retryable || attempt == maximumAttempts) error(lastFailure)

                stats = stats.copy(
                    retries = stats.retries + 1,
                    rateLimits = stats.rateLimits + if (code == 429) 1 else 0,
                )
                Thread.sleep(retryDelayMillis(connection, attempt))
            } finally {
                connection.disconnect()
            }
        }
        error(lastFailure)
    }

    private fun awaitRequestSlot() {
        synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val wait = minimumRequestSpacingMillis - (now - lastRequestStartedMillis)
            if (wait > 0) Thread.sleep(wait)
            lastRequestStartedMillis = System.currentTimeMillis()
        }
    }

    private fun retryDelayMillis(connection: HttpURLConnection, attempt: Int): Long {
        val retryAfterSeconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return min(retryAfterSeconds * 1_000L, 60_000L)
        }
        val exponential = 1_000L shl (attempt - 1).coerceAtMost(5)
        val jitter = (System.nanoTime() and 511L)
        return min(exponential + jitter, 30_000L)
    }
}
