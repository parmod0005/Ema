package com.parmod.ema.backtest

import com.parmod.ema.model.MarketIndex
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime

/** Read-only Upstox Plus client for expired derivatives research. */
class UpstoxPlusHistoricalClient(private val accessToken: String) {
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
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox HTTP $code: ${body.take(500)}")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
