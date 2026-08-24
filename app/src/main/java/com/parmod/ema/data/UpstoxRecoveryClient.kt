package com.parmod.ema.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only broker position reconciliation used after an Android process restart. */
class UpstoxRecoveryClient(private val accessToken: String) {
    data class Position(
        val instrumentKey: String,
        val quantity: Int,
        val averagePrice: Double,
        val lastPrice: Double,
        val pnl: Double,
        val product: String,
        val tradingSymbol: String,
    )

    fun getPositions(): List<Position> {
        require(accessToken.isNotBlank()) { "Upstox access token is required" }
        val response = getJson(POSITIONS_URL)
        val data = response.getJSONArray("data")
        return buildList {
            for (i in 0 until data.length()) {
                val node = data.optJSONObject(i) ?: continue
                val key = node.optString("instrument_token")
                if (key.isBlank()) continue
                add(
                    Position(
                        instrumentKey = key,
                        quantity = node.optInt("quantity", 0),
                        averagePrice = node.optDouble("average_price", 0.0),
                        lastPrice = node.optDouble("last_price", 0.0),
                        pnl = node.optDouble("pnl", 0.0),
                        product = node.optString("product"),
                        tradingSymbol = node.optString("trading_symbol", node.optString("tradingsymbol")),
                    ),
                )
            }
        }
    }

    fun positionFor(instrumentKey: String): Position? =
        getPositions().firstOrNull { it.instrumentKey == instrumentKey && it.quantity != 0 }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox positions HTTP $code: ${body.take(240)}")
            val json = JSONObject(body)
            if (!json.optString("status").equals("success", ignoreCase = true)) {
                error("Upstox positions API did not return success")
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val POSITIONS_URL = "https://api.upstox.com/v2/portfolio/short-term-positions"
    }
}
