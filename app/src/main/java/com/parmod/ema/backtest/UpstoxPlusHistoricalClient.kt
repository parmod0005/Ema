package com.parmod.ema.backtest

import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.min

/** Read-only Upstox client for historical/expired-instrument research. */
class UpstoxPlusHistoricalClient(
    private val accessToken: String,
    private val minimumRequestSpacingMillis: Long = 350L,
    private val maximumAttempts: Int = 6,
    private val cacheDirectory: File? = null,
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
        val cacheHits: Long = 0,
        val cacheWrites: Long = 0,
    )

    @Volatile private var stats = RequestStats()
    private val throttleLock = Any()
    private var lastRequestStartedMillis = 0L

    init { cacheDirectory?.mkdirs() }

    fun requestStats(): RequestStats = stats

    fun getExpiries(index: MarketIndex): List<LocalDate> {
        val key = encodedUnderlying(index)
        val json = get("https://api.upstox.com/v2/expired-instruments/expiries?instrument_key=$key")
        val data = json.getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) runCatching { LocalDate.parse(data.getString(i)) }.getOrNull()?.let(::add)
        }.distinct().sorted()
    }

    fun getExpiredOptionContracts(index: MarketIndex, expiry: LocalDate): List<ExpiredContract> {
        val key = encodedUnderlying(index)
        val json = get("https://api.upstox.com/v2/expired-instruments/option/contract?instrument_key=$key&expiry_date=$expiry")
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
        require(interval in setOf("1minute", "3minute", "5minute", "15minute", "30minute", "day"))
        require(!fromDate.isAfter(toDate))
        val cacheFile = candleCacheFile("EXPIRED|$expiredInstrumentKey", interval, fromDate, toDate)
        readCachedCandles(cacheFile)?.let {
            stats = stats.copy(cacheHits = stats.cacheHits + 1)
            return it
        }
        val key = URLEncoder.encode(expiredInstrumentKey, Charsets.UTF_8.name())
        val json = get("https://api.upstox.com/v2/expired-instruments/historical-candle/$key/$interval/$toDate/$fromDate")
        val candles = parseCandles(json.getJSONObject("data").getJSONArray("candles"))
        writeCachedCandles(cacheFile, candles)
        return candles
    }

    /** Causal daily reference used for strike-band selection. */
    fun getHistoricalUnderlyingDailyCandles(index: MarketIndex, fromDate: LocalDate, toDate: LocalDate): List<Candle> =
        getHistoricalUnderlyingCandles(index, "days", 1, fromDate, toDate)

    /** 1-minute underlying bars used to reconstruct NIFTY/SENSEX signal context for option labels. */
    fun getHistoricalUnderlyingMinuteCandles(index: MarketIndex, fromDate: LocalDate, toDate: LocalDate): List<Candle> =
        getHistoricalUnderlyingCandles(index, "minutes", 1, fromDate, toDate)

    private fun getHistoricalUnderlyingCandles(
        index: MarketIndex,
        unit: String,
        interval: Int,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<Candle> {
        require(unit in setOf("minutes", "hours", "days", "weeks", "months"))
        require(interval >= 1)
        require(!fromDate.isAfter(toDate))
        if (unit == "minutes" && interval <= 15) {
            require(!fromDate.isBefore(toDate.minusMonths(1))) { "Upstox V3 1-15 minute requests are limited to one month per request" }
        }
        val rawKey = underlyingKey(index)
        val cacheTag = "v3-$unit-$interval"
        val cacheFile = candleCacheFile("UNDERLYING|$rawKey", cacheTag, fromDate, toDate)
        readCachedCandles(cacheFile)?.let {
            stats = stats.copy(cacheHits = stats.cacheHits + 1)
            return it
        }
        val key = URLEncoder.encode(rawKey, Charsets.UTF_8.name())
        val json = get("https://api.upstox.com/v3/historical-candle/$key/$unit/$interval/$toDate/$fromDate")
        val candles = parseCandles(json.getJSONObject("data").getJSONArray("candles"))
        writeCachedCandles(cacheFile, candles)
        return candles
    }

    private fun parseCandles(rows: JSONArray): List<Candle> = buildList {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
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

    private fun candleCacheFile(key: String, interval: String, from: LocalDate, to: LocalDate): File? {
        val root = cacheDirectory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest("$key|$interval|$from|$to".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.json")
    }

    private fun readCachedCandles(file: File?): List<Candle>? {
        if (file == null || !file.isFile || file.length() == 0L) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("version") != CACHE_VERSION || !root.optBoolean("complete", false)) return null
            parseCandles(root.getJSONArray("candles"))
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeCachedCandles(file: File?, candles: List<Candle>) {
        if (file == null || candles.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            val rows = JSONArray()
            candles.forEach { candle ->
                rows.put(JSONArray().apply {
                    put(candle.time.toString()); put(candle.open); put(candle.high); put(candle.low); put(candle.close)
                    put(candle.volume); put(candle.openInterest)
                })
            }
            temp.writeText(JSONObject().put("version", CACHE_VERSION).put("complete", true).put("candles", rows).toString())
            moveReplacing(temp, file)
            stats = stats.copy(cacheWrites = stats.cacheWrites + 1)
        }
    }

    private fun underlyingKey(index: MarketIndex): String = when (index) {
        MarketIndex.NIFTY -> "NSE_INDEX|Nifty 50"
        MarketIndex.SENSEX -> "BSE_INDEX|SENSEX"
    }

    private fun encodedUnderlying(index: MarketIndex): String = URLEncoder.encode(underlyingKey(index), Charsets.UTF_8.name())

    private fun moveReplacing(source: File, target: File) {
        val atomic = runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
        if (!atomic) Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun get(url: String): JSONObject {
        require(accessToken.isNotBlank())
        require(maximumAttempts >= 1)
        var lastFailure = "Unknown Upstox error"
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
                stats = stats.copy(retries = stats.retries + 1, rateLimits = stats.rateLimits + if (code == 429) 1 else 0)
                Thread.sleep(retryDelayMillis(connection, attempt))
            } finally { connection.disconnect() }
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
        if (retryAfterSeconds != null && retryAfterSeconds > 0) return min(retryAfterSeconds * 1_000L, 60_000L)
        val exponential = 1_000L shl (attempt - 1).coerceAtMost(5)
        val jitter = System.nanoTime() and 511L
        return min(exponential + jitter, 30_000L)
    }

    private companion object { const val CACHE_VERSION = 1 }
}
