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
 * Callers MUST pass LiveExecutionGuard immediately before invoking a BUY entry.
 */
class UpstoxOrderClient(private val accessToken: String) {
    enum class TransactionType { BUY, SELL }

    data class Placement(
        val orderIds: List<String>,
        val latencyMillis: Long = 0L,
    ) {
        init { require(orderIds.isNotEmpty()) }
        val orderId: String get() = orderIds.first()
    }

    data class Status(
        val orderId: String,
        val state: String,
        val averagePrice: Double,
        val filledQuantity: Int,
        val pendingQuantity: Int,
        val statusMessage: String,
    ) {
        val normalizedState: String get() = state.trim().lowercase()
        val completed: Boolean get() = normalizedState == "complete" && filledQuantity > 0
        val terminal: Boolean get() = normalizedState in TERMINAL_STATES
        val rejected: Boolean get() = normalizedState == "rejected"
    }

    data class Execution(
        val orderIds: List<String>,
        val requestedQuantity: Int,
        val filledQuantity: Int,
        val pendingQuantity: Int,
        val averagePrice: Double,
        val states: List<Status>,
    ) {
        val fullyFilled: Boolean get() = filledQuantity >= requestedQuantity && requestedQuantity > 0
        val partiallyFilled: Boolean get() = filledQuantity in 1 until requestedQuantity
        val zeroFill: Boolean get() = filledQuantity <= 0
        val brokerReference: String get() = orderIds.joinToString(",")
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

        val response = requestJson("POST", PLACE_ORDER_V3, payload)
        val data = response.getJSONObject("data")
        val ids = buildList {
            val array = data.optJSONArray("order_ids")
            if (array != null) {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            if (isEmpty()) data.optString("order_id").takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        if (ids.isEmpty()) error("Upstox accepted the request without an order id")
        return Placement(
            orderIds = ids,
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
     * Reconciles every order returned by Upstox auto-slicing. On timeout, all non-terminal
     * slices are cancelled and one final status snapshot is returned. The caller therefore
     * always knows the actual broker-filled quantity and can flatten or retain a residual
     * position instead of losing track of a partial fill.
     */
    fun awaitExecution(
        placement: Placement,
        expectedQuantity: Int,
        timeoutMillis: Long = 8_000L,
    ): Execution {
        require(expectedQuantity > 0)
        val timeout = timeoutMillis.coerceIn(1_000L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var statuses = placement.orderIds.map(::getOrderStatus)
        while (System.currentTimeMillis() < deadline) {
            val execution = aggregate(placement.orderIds, expectedQuantity, statuses)
            if (execution.fullyFilled || statuses.all { it.terminal }) return execution
            Thread.sleep(250L)
            statuses = placement.orderIds.map(::getOrderStatus)
        }

        statuses.filterNot { it.terminal }.forEach { status -> runCatching { cancelOrder(status.orderId) } }
        Thread.sleep(200L)
        statuses = placement.orderIds.map { id -> runCatching { getOrderStatus(id) }.getOrElse { old -> statuses.first { it.orderId == id } } }
        return aggregate(placement.orderIds, expectedQuantity, statuses)
    }

    /** Backward-compatible single-order helper used only by legacy code. */
    fun awaitFill(orderId: String, expectedQuantity: Int, timeoutMillis: Long = 8_000L): Status {
        val execution = awaitExecution(Placement(listOf(orderId)), expectedQuantity, timeoutMillis)
        if (!execution.fullyFilled) {
            error("Upstox order $orderId filled ${execution.filledQuantity}/$expectedQuantity; caller must reconcile partial fill")
        }
        return execution.states.first()
    }

    fun cancelOrder(orderId: String): String {
        require(orderId.isNotBlank())
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8.name())
        val response = requestJson("DELETE", "$CANCEL_ORDER_V3?order_id=$encoded")
        return response.optJSONObject("data")?.optString("order_id", orderId) ?: orderId
    }

    private fun aggregate(orderIds: List<String>, requestedQuantity: Int, statuses: List<Status>): Execution {
        val filled = statuses.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val pending = statuses.sumOf { it.pendingQuantity.coerceAtLeast(0) }
        val tradedValue = statuses.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) status.averagePrice * status.filledQuantity else 0.0
        }
        val pricedQty = statuses.sumOf { status -> if (status.averagePrice > 0.0) status.filledQuantity.coerceAtLeast(0) else 0 }
        val avg = if (pricedQty > 0) tradedValue / pricedQty else 0.0
        return Execution(orderIds, requestedQuantity, filled, pending, avg, statuses)
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
        private val TERMINAL_STATES = setOf(
            "complete",
            "rejected",
            "cancelled",
            "cancelled after market order",
        )
        private const val PLACE_ORDER_V3 = "https://api-hft.upstox.com/v3/order/place"
        private const val CANCEL_ORDER_V3 = "https://api-hft.upstox.com/v3/order/cancel"
        private const val ORDER_DETAILS_V2 = "https://api.upstox.com/v2/order/details"
    }
}
