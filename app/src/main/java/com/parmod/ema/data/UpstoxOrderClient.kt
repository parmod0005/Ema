package com.parmod.ema.data

import com.parmod.ema.model.UpstoxComplianceRegistry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Single audited broker-order adapter for VARDHANI LIVE execution.
 *
 * Safety boundary:
 * - live order APIs require a registered Upstox static-IP preflight,
 * - configured Algo Name is sent as X-Algo-Name,
 * - BUY fills are not returned to the runtime until an exchange-held SELL SL-M
 *   catastrophic stop has been accepted,
 * - VARDHANI protective stops are cancelled before a discretionary/adaptive SELL,
 * - partial exits re-arm protection for the remaining protected quantity,
 * - broker-side stop fills are reconciled before another SELL so VARDHANI does not
 *   blindly create a short position after its protection already fired.
 *
 * The phone-side AdaptiveExitEngine remains the primary/tighter exit manager. The SL-M
 * managed here is a disaster backstop for app/network/process failure, not a replacement
 * for adaptive T1/runner logic.
 */
class UpstoxOrderClient(private val accessToken: String) {
    enum class TransactionType { BUY, SELL }

    data class Placement(
        val orderIds: List<String>,
        val latencyMillis: Long = 0L,
        val instrumentKey: String = "",
        val transactionType: TransactionType = TransactionType.BUY,
        val tag: String = "",
        val networkQuantity: Int = 0,
        val preFilledQuantity: Int = 0,
        val preFillAveragePrice: Double = 0.0,
        val protectionBeforeQuantity: Int = 0,
        val protectionTriggerPrice: Double = 0.0,
        val protectAfterBuyFill: Boolean = false,
    ) {
        val orderId: String get() = orderIds.firstOrNull().orEmpty()
    }

    data class Status(
        val orderId: String,
        val state: String,
        val averagePrice: Double,
        val quantity: Int,
        val filledQuantity: Int,
        val pendingQuantity: Int,
        val statusMessage: String,
        val instrumentKey: String = "",
        val orderType: String = "",
        val transactionType: String = "",
        val triggerPrice: Double = 0.0,
        val tag: String = "",
        val orderTimestamp: String = "",
    ) {
        val normalizedState: String get() = state.trim().lowercase()
        val completed: Boolean get() = normalizedState == "complete" && filledQuantity > 0
        val terminal: Boolean get() = normalizedState in TERMINAL_STATES
        val rejected: Boolean get() = normalizedState == "rejected"
        val active: Boolean get() = !terminal && pendingQuantity > 0
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

    private data class ProtectionGroup(
        val tag: String,
        val quantity: Int,
        val filledQuantity: Int,
        val triggerPrice: Double,
        val averageFillPrice: Double,
        val states: List<Status>,
    )

    private data class SellPreparation(
        val networkQuantity: Int,
        val preFilledQuantity: Int,
        val preFillAveragePrice: Double,
        val protectionBeforeQuantity: Int,
        val protectionTriggerPrice: Double,
    )

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

        ensureOrderCompliance()

        val preparation = if (transactionType == TransactionType.SELL) {
            prepareSell(instrumentKey, quantity)
        } else {
            SellPreparation(quantity, 0, 0.0, 0, 0.0)
        }

        val raw = if (preparation.networkQuantity > 0) {
            placeOrderInternal(
                instrumentKey = instrumentKey,
                quantity = preparation.networkQuantity,
                transactionType = transactionType,
                tag = tag,
                orderType = "MARKET",
                triggerPrice = 0.0,
                slice = true,
            )
        } else {
            Placement(orderIds = emptyList())
        }

        return raw.copy(
            instrumentKey = instrumentKey,
            transactionType = transactionType,
            tag = tag,
            networkQuantity = preparation.networkQuantity,
            preFilledQuantity = preparation.preFilledQuantity,
            preFillAveragePrice = preparation.preFillAveragePrice,
            protectionBeforeQuantity = preparation.protectionBeforeQuantity,
            protectionTriggerPrice = preparation.protectionTriggerPrice,
            protectAfterBuyFill = transactionType == TransactionType.BUY,
        )
    }

    fun getOrderStatus(orderId: String): Status {
        require(orderId.isNotBlank())
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8.name())
        val response = requestJson("GET", "$ORDER_DETAILS_V2?order_id=$encoded")
        return parseStatus(response.getJSONObject("data"), orderId)
    }

    fun getOrderBook(): List<Status> {
        val response = requestJson("GET", ORDER_BOOK_V2)
        val data = response.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val node = data.optJSONObject(i) ?: continue
                add(parseStatus(node, node.optString("order_id")))
            }
        }
    }

    /**
     * Reconciles every order returned by Upstox auto-slicing. On timeout, all non-terminal
     * slices are cancelled and one final status snapshot is returned. BUY executions are
     * then protected at the broker; SELL executions re-arm the remaining protective quantity.
     */
    fun awaitExecution(
        placement: Placement,
        expectedQuantity: Int,
        timeoutMillis: Long = 8_000L,
    ): Execution {
        require(expectedQuantity > 0)
        val networkExecution = awaitNetworkExecution(placement, timeoutMillis)
        var combined = combinePreFill(placement, expectedQuantity, networkExecution)

        if (placement.transactionType == TransactionType.BUY && placement.protectAfterBuyFill && combined.filledQuantity > 0) {
            val entryAverage = combined.averagePrice.takeIf { it > 0.0 }
                ?: networkExecution.averagePrice.takeIf { it > 0.0 }
                ?: 0.0
            if (entryAverage <= 0.0) {
                UpstoxComplianceRegistry.setProtectionFault("BUY filled but protective stop price could not be established")
                return combined
            }
            val protected = armProtectionWithRetry(
                instrumentKey = placement.instrumentKey,
                quantity = combined.filledQuantity,
                triggerPrice = catastrophicStop(entryAverage, placement.tag),
            )
            if (!protected) {
                UpstoxComplianceRegistry.setProtectionFault("Could not arm exchange-held stop after LIVE BUY")
                // Best-effort immediate flatten. If fully flattened, report zero live exposure
                // so the caller will reject the entry and never create a local position.
                val emergency = runCatching {
                    emergencyRawSell(placement.instrumentKey, combined.filledQuantity)
                }.getOrNull()
                if (emergency != null && emergency.filledQuantity >= combined.filledQuantity) {
                    return combined.copy(
                        filledQuantity = 0,
                        pendingQuantity = 0,
                        averagePrice = 0.0,
                        orderIds = combined.orderIds + emergency.orderIds,
                        states = combined.states + emergency.states,
                    )
                }
            } else {
                UpstoxComplianceRegistry.clearProtectionFault()
            }
        }

        if (placement.transactionType == TransactionType.SELL && placement.protectionBeforeQuantity > 0) {
            val remaining = (placement.protectionBeforeQuantity - combined.filledQuantity).coerceAtLeast(0)
            if (remaining > 0) {
                val trigger = placement.protectionTriggerPrice.takeIf { it > 0.0 }
                if (trigger == null || !armProtectionWithRetry(placement.instrumentKey, remaining, trigger)) {
                    UpstoxComplianceRegistry.setProtectionFault("Could not re-arm protective stop after partial LIVE exit")
                } else {
                    UpstoxComplianceRegistry.clearProtectionFault()
                }
            } else {
                UpstoxComplianceRegistry.clearProtectionFault()
            }
        }

        return combined
    }

    /** Backward-compatible single-order helper used only by legacy code; it does not arm a new protection. */
    fun awaitFill(orderId: String, expectedQuantity: Int, timeoutMillis: Long = 8_000L): Status {
        val execution = awaitExecution(
            Placement(
                orderIds = listOf(orderId),
                networkQuantity = expectedQuantity,
                protectAfterBuyFill = false,
            ),
            expectedQuantity,
            timeoutMillis,
        )
        if (!execution.fullyFilled) {
            error("Upstox order $orderId filled ${execution.filledQuantity}/$expectedQuantity; caller must reconcile partial fill")
        }
        return execution.states.first()
    }

    fun cancelOrder(orderId: String): String {
        require(orderId.isNotBlank())
        ensureOrderCompliance()
        val encoded = URLEncoder.encode(orderId, Charsets.UTF_8.name())
        val response = requestJson("DELETE", "$CANCEL_ORDER_V3?order_id=$encoded")
        return response.optJSONObject("data")?.optString("order_id", orderId) ?: orderId
    }

    private fun prepareSell(instrumentKey: String, requestedQuantity: Int): SellPreparation {
        val latestProtection = latestProtectionGroup(instrumentKey)
        latestProtection?.states?.filter { it.active }?.forEach { status ->
            runCatching { cancelOrder(status.orderId) }
                .getOrElse { error("Could not cancel protective stop ${status.orderId}: ${it.message}") }
        }

        val brokerPosition = UpstoxRecoveryClient(accessToken).positionFor(instrumentKey)
        val brokerLongQuantity = (brokerPosition?.quantity ?: 0).coerceAtLeast(0)
        val missingFromBroker = (requestedQuantity - brokerLongQuantity).coerceIn(0, requestedQuantity)
        val actualSellQuantity = min(requestedQuantity - missingFromBroker, brokerLongQuantity).coerceAtLeast(0)

        val protectionBefore = latestProtection?.quantity ?: 0
        if (actualSellQuantity < brokerLongQuantity && protectionBefore <= 0) {
            UpstoxComplianceRegistry.setProtectionFault("Partial LIVE exit requested while broker protection is missing")
            error("Partial LIVE exit blocked because no VARDHANI protective stop is active")
        }

        val preFillAverage = latestProtection?.averageFillPrice?.takeIf { it > 0.0 } ?: 0.0
        return SellPreparation(
            networkQuantity = actualSellQuantity,
            preFilledQuantity = missingFromBroker,
            preFillAveragePrice = preFillAverage,
            protectionBeforeQuantity = max(protectionBefore, requestedQuantity),
            protectionTriggerPrice = latestProtection?.triggerPrice ?: 0.0,
        )
    }

    private fun latestProtectionGroup(instrumentKey: String): ProtectionGroup? {
        val protections = getOrderBook().filter {
            it.instrumentKey == instrumentKey &&
                it.transactionType.equals("SELL", ignoreCase = true) &&
                it.orderType.equals("SL-M", ignoreCase = true) &&
                it.tag.startsWith(PROTECTION_TAG_PREFIX)
        }
        if (protections.isEmpty()) return null
        val groups = protections.groupBy { it.tag }
        val latest = groups.values.maxByOrNull { group ->
            group.maxOfOrNull { it.orderTimestamp } ?: ""
        } ?: return null
        val quantity = latest.sumOf { it.quantity.coerceAtLeast(it.filledQuantity + it.pendingQuantity) }
        val filled = latest.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val tradedValue = latest.sumOf {
            if (it.filledQuantity > 0 && it.averagePrice > 0.0) it.averagePrice * it.filledQuantity else 0.0
        }
        val average = if (filled > 0) tradedValue / filled else 0.0
        return ProtectionGroup(
            tag = latest.first().tag,
            quantity = quantity,
            filledQuantity = filled,
            triggerPrice = latest.map { it.triggerPrice }.filter { it > 0.0 }.maxOrNull() ?: 0.0,
            averageFillPrice = average,
            states = latest,
        )
    }

    private fun armProtectionWithRetry(instrumentKey: String, quantity: Int, triggerPrice: Double): Boolean {
        if (quantity <= 0 || triggerPrice <= 0.0) return false
        repeat(PROTECTION_RETRIES) { attempt ->
            val accepted = runCatching {
                ensureOrderCompliance()
                val tag = protectionTag(instrumentKey)
                val placement = placeOrderInternal(
                    instrumentKey = instrumentKey,
                    quantity = quantity,
                    transactionType = TransactionType.SELL,
                    tag = tag,
                    orderType = "SL-M",
                    triggerPrice = triggerPrice,
                    slice = true,
                )
                Thread.sleep(150L)
                val statuses = placement.orderIds.map(::getOrderStatus)
                statuses.isNotEmpty() && statuses.none { it.rejected }
            }.getOrDefault(false)
            if (accepted) return true
            if (attempt < PROTECTION_RETRIES - 1) Thread.sleep(200L * (attempt + 1))
        }
        return false
    }

    private fun catastrophicStop(entryPrice: Double, entryTag: String): Double {
        val stopPercent = when {
            entryTag.contains("-E1-") -> 15.0
            entryTag.contains("-E2-") -> 14.0
            entryTag.contains("-E3-") -> 14.0
            else -> 15.0
        }
        val raw = entryPrice * (1.0 - stopPercent / 100.0)
        return roundDownToTick(raw).coerceAtLeast(DEFAULT_TICK_SIZE)
    }

    private fun protectionTag(instrumentKey: String): String {
        val hash = instrumentKey.hashCode().toUInt().toString(16).takeLast(8)
        val stamp = System.currentTimeMillis().toString().takeLast(8)
        return "$PROTECTION_TAG_PREFIX$hash-$stamp".take(40)
    }

    private fun roundDownToTick(value: Double): Double =
        floor((value + 1e-9) / DEFAULT_TICK_SIZE) * DEFAULT_TICK_SIZE

    private fun emergencyRawSell(instrumentKey: String, quantity: Int): Execution {
        val placement = placeOrderInternal(
            instrumentKey = instrumentKey,
            quantity = quantity,
            transactionType = TransactionType.SELL,
            tag = "VRD-EMERGENCY-${System.currentTimeMillis().toString().takeLast(8)}".take(40),
            orderType = "MARKET",
            triggerPrice = 0.0,
            slice = true,
        ).copy(
            instrumentKey = instrumentKey,
            transactionType = TransactionType.SELL,
            networkQuantity = quantity,
        )
        return awaitNetworkExecution(placement, 8_000L)
    }

    private fun placeOrderInternal(
        instrumentKey: String,
        quantity: Int,
        transactionType: TransactionType,
        tag: String,
        orderType: String,
        triggerPrice: Double,
        slice: Boolean,
    ): Placement {
        require(quantity > 0)
        val payload = JSONObject()
            .put("quantity", quantity)
            .put("product", "I")
            .put("validity", "DAY")
            .put("price", 0)
            .put("tag", tag)
            .put("instrument_token", instrumentKey)
            .put("order_type", orderType)
            .put("transaction_type", transactionType.name)
            .put("disclosed_quantity", 0)
            .put("trigger_price", triggerPrice)
            .put("is_amo", false)
            .put("slice", slice)
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
            instrumentKey = instrumentKey,
            transactionType = transactionType,
            tag = tag,
            networkQuantity = quantity,
        )
    }

    private fun awaitNetworkExecution(placement: Placement, timeoutMillis: Long): Execution {
        if (placement.orderIds.isEmpty() || placement.networkQuantity <= 0) {
            return Execution(emptyList(), placement.networkQuantity, 0, 0, 0.0, emptyList())
        }
        val timeout = timeoutMillis.coerceIn(1_000L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var statuses = placement.orderIds.map(::getOrderStatus)
        while (System.currentTimeMillis() < deadline) {
            val execution = aggregate(placement.orderIds, placement.networkQuantity, statuses)
            if (execution.fullyFilled || statuses.all { it.terminal }) return execution
            Thread.sleep(250L)
            statuses = placement.orderIds.map(::getOrderStatus)
        }

        statuses.filterNot { it.terminal }.forEach { status -> runCatching { cancelOrder(status.orderId) } }
        Thread.sleep(200L)
        statuses = placement.orderIds.map { id ->
            runCatching { getOrderStatus(id) }.getOrElse { statuses.first { it.orderId == id } }
        }
        return aggregate(placement.orderIds, placement.networkQuantity, statuses)
    }

    private fun combinePreFill(
        placement: Placement,
        expectedQuantity: Int,
        network: Execution,
    ): Execution {
        val preQty = placement.preFilledQuantity.coerceIn(0, expectedQuantity)
        val networkQty = network.filledQuantity.coerceAtLeast(0)
        val total = (preQty + networkQty).coerceAtMost(expectedQuantity)
        val preValue = if (preQty > 0 && placement.preFillAveragePrice > 0.0) {
            preQty * placement.preFillAveragePrice
        } else 0.0
        val networkValue = if (networkQty > 0 && network.averagePrice > 0.0) {
            networkQty * network.averagePrice
        } else 0.0
        val pricedQty = (if (preValue > 0.0) preQty else 0) + (if (networkValue > 0.0) networkQty else 0)
        val average = if (pricedQty > 0) (preValue + networkValue) / pricedQty else 0.0
        return Execution(
            orderIds = network.orderIds,
            requestedQuantity = expectedQuantity,
            filledQuantity = total,
            pendingQuantity = (expectedQuantity - total).coerceAtLeast(0),
            averagePrice = average,
            states = network.states,
        )
    }

    private fun aggregate(orderIds: List<String>, requestedQuantity: Int, statuses: List<Status>): Execution {
        val filled = statuses.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val pending = statuses.sumOf { it.pendingQuantity.coerceAtLeast(0) }
        val tradedValue = statuses.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) status.averagePrice * status.filledQuantity else 0.0
        }
        val pricedQty = statuses.sumOf { status ->
            if (status.averagePrice > 0.0) status.filledQuantity.coerceAtLeast(0) else 0
        }
        val avg = if (pricedQty > 0) tradedValue / pricedQty else 0.0
        return Execution(orderIds, requestedQuantity, filled, pending, avg, statuses)
    }

    private fun parseStatus(data: JSONObject, fallbackOrderId: String): Status = Status(
        orderId = data.optString("order_id", fallbackOrderId),
        state = data.optString("status"),
        averagePrice = data.optDouble("average_price", 0.0),
        quantity = data.optInt("quantity", 0),
        filledQuantity = data.optInt("filled_quantity", 0),
        pendingQuantity = data.optInt("pending_quantity", 0),
        statusMessage = data.optString("status_message"),
        instrumentKey = data.optString("instrument_token"),
        orderType = data.optString("order_type"),
        transactionType = data.optString("transaction_type"),
        triggerPrice = data.optDouble("trigger_price", 0.0),
        tag = data.optString("tag"),
        orderTimestamp = data.optString("order_timestamp"),
    )

    private fun ensureOrderCompliance() {
        if (!UpstoxComplianceRegistry.staticIpCheckFresh() || !UpstoxComplianceRegistry.hasRegisteredStaticIp()) {
            val status = UpstoxComplianceClient(accessToken).verifyRegisteredStaticIp(force = true)
            if (!status.configured) error("Upstox registered static IP is required for live order placement")
        }
    }

    private fun requestJson(method: String, url: String, payload: JSONObject? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            UpstoxComplianceRegistry.algoName().takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("X-Algo-Name", it)
            }
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
        private const val PROTECTION_TAG_PREFIX = "VRD-PSL-"
        private const val PROTECTION_RETRIES = 3
        private const val DEFAULT_TICK_SIZE = 0.05
        private const val PLACE_ORDER_V3 = "https://api-hft.upstox.com/v3/order/place"
        private const val CANCEL_ORDER_V3 = "https://api-hft.upstox.com/v3/order/cancel"
        private const val ORDER_DETAILS_V2 = "https://api.upstox.com/v2/order/details"
        private const val ORDER_BOOK_V2 = "https://api.upstox.com/v2/order/retrieve-all"
    }
}
