package com.parmod.ema.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime

/** Read-only V3 candle loader used only to warm-start live signal engines. */
class UpstoxIntradayCandleClient(private val accessToken: String) {
    data class Candle(
        val time: OffsetDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
    )

    fun getOneMinuteCandles(instrumentKey: String): List<Candle> = fetch(
        "https://api.upstox.com/v3/historical-candle/intraday/${encode(instrumentKey)}/minutes/1",
    )

    /** Mirrors V7.6 HISTORICAL_WARMUP_DAYS=10 then merges current intraday candles. */
    fun getWarmupOneMinuteCandles(instrumentKey: String, days: Int = 10): List<Candle> {
        require(days > 0)
        val today = LocalDate.now()
        val from = today.minusDays(days.toLong())
        val historical = fetch(
            "https://api.upstox.com/v3/historical-candle/${encode(instrumentKey)}/minutes/1/$today/$from",
        )
        val intraday = runCatching { getOneMinuteCandles(instrumentKey) }.getOrDefault(emptyList())
        return (historical + intraday)
            .associateBy { it.time.toInstant().toEpochMilli() }
            .values
            .sortedBy { it.time }
    }

    private fun encode(instrumentKey: String): String {
        require(accessToken.isNotBlank()) { "Upstox access token is required" }
        require(instrumentKey.isNotBlank()) { "Instrument key is required" }
        return URLEncoder.encode(instrumentKey, Charsets.UTF_8.name())
    }

    private fun fetch(url: String): List<Candle> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox candle warm-up HTTP $code: ${body.take(220)}")
            val rows = JSONObject(body).getJSONObject("data").getJSONArray("candles")
            return buildList {
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
                        ),
                    )
                }
            }.sortedBy { it.time }
        } finally {
            connection.disconnect()
        }
    }
}
