package com.parmod.ema.data

import com.parmod.ema.model.MarketIndex
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/** Read-only option-contract discovery. No order endpoints are used. */
class UpstoxOptionDiscoveryClient(private val accessToken: String) {
    data class Contract(
        val instrumentKey: String,
        val expiry: String,
        val strike: Double,
        val optionType: String,
        val lotSize: Int,
    )

    data class Discovery(
        val expiries: List<String>,
        val nearestExpiry: String,
        val contractsByExpiry: Map<String, List<Contract>>,
    )

    fun discover(index: MarketIndex, today: LocalDate = LocalDate.now()): Discovery {
        require(accessToken.isNotBlank()) { "Access token is required" }
        val underlying = when (index) {
            MarketIndex.NIFTY -> "NSE_INDEX|Nifty 50"
            MarketIndex.SENSEX -> "BSE_INDEX|SENSEX"
        }
        val encoded = URLEncoder.encode(underlying, Charsets.UTF_8.name())
        val response = getJson("https://api.upstox.com/v2/option/contract?instrument_key=$encoded")
        val data = response.getJSONArray("data")
        val contracts = buildList {
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val expiry = item.optString("expiry")
                val key = item.optString("instrument_key")
                val type = item.optString("instrument_type")
                if (expiry.isBlank() || key.isBlank() || type !in setOf("CE", "PE")) continue
                add(
                    Contract(
                        instrumentKey = key,
                        expiry = expiry,
                        strike = item.optDouble("strike_price", 0.0),
                        optionType = type,
                        lotSize = item.optInt("lot_size", if (index == MarketIndex.NIFTY) 65 else 20),
                    ),
                )
            }
        }
        if (contracts.isEmpty()) error("No option contracts returned by Upstox")
        val grouped = contracts.groupBy { it.expiry }.toSortedMap()
        val validExpiries = grouped.keys.filter {
            runCatching { !LocalDate.parse(it).isBefore(today) }.getOrDefault(false)
        }
        val expiries = if (validExpiries.isNotEmpty()) validExpiries else grouped.keys.toList()
        val nearest = expiries.firstOrNull() ?: error("No expiry dates returned")
        return Discovery(expiries, nearest, grouped)
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Upstox HTTP $code: $body")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
