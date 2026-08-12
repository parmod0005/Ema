package com.parmod.ema.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime

/** Read-only V3 intraday candle loader used only to warm-start live signal engines. */
class UpstoxIntradayCandleClient(private val accessToken: String) {
    data class Candle(
        val time: OffsetDateTime,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long,
    )

    fun getOneMinuteCandles(instrumentKey: String): List<Candle> {
        require(accessToken.isNotBlank()) { "Upstox access token is required" }
        require(instrumentKey.isNotBlank()) { "Instrument key is required" }
        val key = URLEncoder.encode(instrumentKey, Charsets.UTF_8.name())
        val connection = URL(
            "https://api.upstox.com/v3/historical-candle/intraday/$key/minutes/1",
        ).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox intraday warm-up HTTP $code: ${body.take(220)}")
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
