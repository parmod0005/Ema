package com.parmod.ema.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Explicit broker-order adapter for the opt-in LIVE execution path.
 *
 * Market-data clients remain read-only. This class is the only low-level VARDHANI
 * component allowed to call Upstox order endpoints so live authority is easy to audit.
 * Callers MUST pass LiveExecutionGuard immediately before invoking placeMarketOrder.
 */
class UpstoxOrderClient(private val accessToken: String) {
    enum class TransactionType { BUY, SELL }

    data class Placement(
        val orderId: String,
        val latencyMillis: Long = 0L,
    )

    data class Status(
        val orderId: String,
        val state: String,
        val averagePrice: Double,
        val filledQuantity: Int,
        val pendingQuantity: Int,
        val statusMessage: String,
    ) {
        val normalizedState: String get() = state.trim().lowercase()
        val completed: Boolean get() = normalizedState in setOf("complete", "completed") && filledQuantity > 0
        val rejected: Boolean get() = normalizedState in setOf("rejected", "cancelled", "canceled")
    }

    fun placeMarketOrder(
        instrumentKey: String,
        quantity: Int,
        transactionType: TransactionType,
        tag: String,
    ): Placement {
        require(accessToken.isNotBlank()) { "Access token is required" }
        require(instrumentKey.isNotBlank()) { "Instrument key is required" }
        require(quantity > 0) { "Quantity must be positive" }
        require(tag.isNotBlank() && tag.length <= 40) { "Order tag must be 1..40 characters" }

        val payload = JSONObject()
            .put("quantity", quantity)
            .put("product", "I")
            .put("validity", "DAY")
            .put("price", 0)
            .put("tag", tag)
            .put("instrument_token", instrumentKey)
            .put("order_type", "MARKET")
            .put("transaction_type", transactionType.name)
            .put("disclosed_quantity", 0)
            .put("trigger_price", 0)
            .put("is_amo", false)
            .put("slice", true)
            .put("market_protection", -1)

        val response = requestJson(
            method = "POST",
            url = PLACE_ORDER_V3,
            payload = payload,
        )
        val data = response.getJSONObject("data")
        val orderId = when {
            data.has("order_id") -> data.getString("order_id")
            data.optJSONArray("order_ids")?.length() ?: 0 > 0 -> data.getJSONArray("order_ids").getString(0)
            else -> error("Upstox accepted the request without an order id")
        }
        return Placement(
            orderId = orderId,
            latencyMillis = response.optJSONObject("metadata")?.optLong("latency", 0L) ?: 0L,
        )
    }

    fun getOrderStatus(orderId: String): Status {
        require(orderId.isNotBlank())
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8.name())
        val response = requestJson("GET", "$ORDER_DETAILS_V2?order_id=$encoded")
        val data = response.getJSONObject("data")
        return Status(
            orderId = data.optString("order_id", orderId),
            state = data.optString("status"),
            averagePrice = data.optDouble("average_price", 0.0),
            filledQuantity = data.optInt("filled_quantity", 0),
            pendingQuantity = data.optInt("pending_quantity", 0),
            statusMessage = data.optString("status_message"),
        )
    }

    /**
     * Wait briefly for a market order to reach a terminal fill state. A timeout is
     * treated as unsafe: the client attempts cancellation and returns an error instead
     * of guessing a fill price or quantity.
     */
    fun awaitFill(orderId: String, expectedQuantity: Int, timeoutMillis: Long = 8_000L): Status {
        require(expectedQuantity > 0)
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceIn(1_000L, 30_000L)
        var last = getOrderStatus(orderId)
        while (System.currentTimeMillis() < deadline) {
            if (last.completed && last.filledQuantity >= expectedQuantity) return last
            if (last.rejected) error("Upstox order ${last.orderId} ${last.state}: ${last.statusMessage}")
            Thread.sleep(250L)
            last = getOrderStatus(orderId)
        }
        runCatching { cancelOrder(orderId) }
        error("Upstox order $orderId fill confirmation timed out; cancel requested")
    }

    fun cancelOrder(orderId: String): String {
        require(orderId.isNotBlank())
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8.name())
        val response = requestJson("DELETE", "$CANCEL_ORDER_V3?order_id=$encoded")
        return response.optJSONObject("data")?.optString("order_id", orderId) ?: orderId
    }

    private fun requestJson(method: String, url: String, payload: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox order HTTP $code: $body")
            val json = JSONObject(body)
            if (!json.optString("status").equals("success", ignoreCase = true)) {
                error("Upstox order API did not return success: $body")
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PLACE_ORDER_V3 = "https://api-hft.upstox.com/v3/order/place"
        private const val CANCEL_ORDER_V3 = "https://api-hft.upstox.com/v3/order/cancel"
        private const val ORDER_DETAILS_V2 = "https://api.upstox.com/v2/order/details"
    }
}
