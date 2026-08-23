package com.parmod.ema.data

import com.parmod.ema.model.UpstoxComplianceRegistry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.floor
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
 * - partial exits re-arm protection using the broker's actual remaining long quantity,
 * - if residual protection cannot be re-armed, a best-effort exact-quantity emergency
 *   flatten is attempted and the protection fault remains latched,
 * - broker-side stop fills are reconciled before another SELL so VARDHANI does not
 *   blindly create a short position after its protection already fired,
 * - a LIVE SELL quantity is exposed to the runtime only to the extent that its fill price
 *   is known; unpriced broker reductions fail closed instead of borrowing LTP as P&L.
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
        val brokerLongBeforeQuantity: Int = 0,
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
        val safetyFlattenedQuantity: Int = 0,
        val safetyFlattenAveragePrice: Double = 0.0,
        val brokerFlatAfterSafetyAction: Boolean = false,
        val pricedQuantity: Int = if (averagePrice > 0.0) filledQuantity.coerceAtLeast(0) else 0,
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
        val pricedFilledQuantity: Int,
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
        val brokerLongBeforeQuantity: Int,
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
            SellPreparation(quantity, 0, 0.0, 0, 0.0, 0)
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
            brokerLongBeforeQuantity = preparation.brokerLongBeforeQuantity,
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
     * then protected at the broker; SELL executions re-arm the actual broker residual.
     */
    fun awaitExecution(
        placement: Placement,
        expectedQuantity: Int,
        timeoutMillis: Long = 8_000L,
    ): Execution {
        require(expectedQuantity > 0)
        val networkExecution = awaitNetworkExecution(placement, timeoutMillis)
        val combined = combinePreFill(placement, expectedQuantity, networkExecution)

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
                        safetyFlattenedQuantity = emergency.filledQuantity,
                        safetyFlattenAveragePrice = emergency.averagePrice,
                        brokerFlatAfterSafetyAction = true,
                        pricedQuantity = 0,
                    )
                }
            } else {
                UpstoxComplianceRegistry.clearProtectionFault()
            }
        }

        if (placement.transactionType == TransactionType.SELL) {
            var sellResult = combined
            if (placement.protectionBeforeQuantity > 0) {
                val fallbackRemaining =
                    (placement.brokerLongBeforeQuantity - networkExecution.filledQuantity).coerceAtLeast(0)
                val remaining = currentBrokerLongQuantity(placement.instrumentKey) ?: fallbackRemaining
                if (remaining > 0) {
                    val trigger = placement.protectionTriggerPrice.takeIf { it > 0.0 }
                    if (trigger == null || !armProtectionWithRetry(placement.instrumentKey, remaining, trigger)) {
                        UpstoxComplianceRegistry.setProtectionFault(
                            "Could not re-arm protective stop after partial LIVE exit; emergency flatten required",
                        )
                        sellResult = emergencyFlattenResidual(
                            placement = placement,
                            combined = combined,
                            trigger = trigger,
                        )
                    } else {
                        UpstoxComplianceRegistry.clearProtectionFault()
                    }
                } else {
                    UpstoxComplianceRegistry.clearProtectionFault()
                }
            }
            return normalizeSellExecutionForCaller(placement, sellResult)
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
        val rawBrokerQuantity = brokerPosition?.quantity ?: 0
        if (rawBrokerQuantity < 0) {
            UpstoxComplianceRegistry.setProtectionFault("Unexpected broker short position detected during LIVE exit")
            error("LIVE exit blocked because broker position is unexpectedly short")
        }
        val brokerLongQuantity = rawBrokerQuantity.coerceAtLeast(0)

        val protectedQuantity = latestProtection?.quantity ?: 0
        val sellPlan = SellReconciliation.plan(
            requestedQuantity = requestedQuantity,
            protectedQuantity = protectedQuantity,
            brokerLongQuantity = brokerLongQuantity,
        )

        val trigger = latestProtection?.triggerPrice ?: 0.0
        if (sellPlan.residualBrokerLongIfFilled > 0 && trigger <= 0.0) {
            UpstoxComplianceRegistry.setProtectionFault(
                "Partial LIVE exit requested while broker protection is missing or unpriced",
            )
            error("Partial LIVE exit blocked because residual quantity cannot be protected")
        }

        val priorClosed = sellPlan.reconciledPriorClosedQuantity
        val preFillAverage = latestProtection?.averageFillPrice
            ?.takeIf {
                priorClosed <= 0 ||
                    (it > 0.0 && latestProtection.pricedFilledQuantity >= priorClosed)
            }
            ?: 0.0
        if (priorClosed > 0 && preFillAverage <= 0.0) {
            UpstoxComplianceRegistry.setProtectionFault(
                "Broker quantity reduced before LIVE exit but full prior fill pricing is unavailable",
            )
            error("LIVE exit requires broker recovery because prior closed quantity is unpriced")
        }

        return SellPreparation(
            networkQuantity = sellPlan.networkSellQuantity,
            preFilledQuantity = sellPlan.preFilledQuantity,
            preFillAveragePrice = preFillAverage,
            protectionBeforeQuantity = sellPlan.reconciledPositionQuantity,
            protectionTriggerPrice = trigger,
            brokerLongBeforeQuantity = brokerLongQuantity,
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
        val pricedFilled = latest.sumOf {
            if (it.filledQuantity > 0 && it.averagePrice > 0.0) it.filledQuantity else 0
        }
        val tradedValue = latest.sumOf {
            if (it.filledQuantity > 0 && it.averagePrice > 0.0) it.averagePrice * it.filledQuantity else 0.0
        }
        val average = if (pricedFilled > 0) tradedValue / pricedFilled else 0.0
        return ProtectionGroup(
            tag = latest.first().tag,
            quantity = quantity,
            filledQuantity = filled,
            pricedFilledQuantity = pricedFilled,
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

    private fun emergencyFlattenResidual(
        placement: Placement,
        combined: Execution,
        trigger: Double?,
    ): Execution {
        var result = combined
        repeat(EMERGENCY_FLATTEN_RETRIES) {
            val remaining = currentBrokerLongQuantity(placement.instrumentKey) ?: return@repeat
            if (remaining <= 0) {
                return result.copy(brokerFlatAfterSafetyAction = true)
            }
            val emergency = runCatching {
                emergencyRawSell(placement.instrumentKey, remaining)
            }.getOrNull() ?: return@repeat
            result = result.copy(
                orderIds = result.orderIds + emergency.orderIds,
                states = result.states + emergency.states,
                safetyFlattenedQuantity = result.safetyFlattenedQuantity + emergency.filledQuantity,
                safetyFlattenAveragePrice = weightedAverage(
                    firstQty = result.safetyFlattenedQuantity,
                    firstAvg = result.safetyFlattenAveragePrice,
                    secondQty = emergency.filledQuantity,
                    secondAvg = emergency.averagePrice,
                ),
            )
            val after = currentBrokerLongQuantity(placement.instrumentKey)
            if (after == 0) {
                return result.copy(brokerFlatAfterSafetyAction = true)
            }
        }

        val residual = currentBrokerLongQuantity(placement.instrumentKey)
        if (residual != null && residual > 0 && trigger != null && trigger > 0.0) {
            if (armProtectionWithRetry(placement.instrumentKey, residual, trigger)) {
                UpstoxComplianceRegistry.setProtectionFault(
                    "Protection re-arm failed during partial exit; emergency reduction executed and residual re-protected. LIVE entries remain locked",
                )
            } else {
                UpstoxComplianceRegistry.setProtectionFault(
                    "Protection re-arm and emergency flatten both incomplete; residual LIVE exposure remains",
                )
            }
        } else if (residual == 0) {
            return result.copy(brokerFlatAfterSafetyAction = true)
        } else {
            UpstoxComplianceRegistry.setProtectionFault(
                "Protection re-arm failed and broker residual could not be verified",
            )
        }
        return result
    }

    /**
     * Translate full broker history into quantities the runtime may safely book.
     * Prior exchange-held protective fills are included even when they exceed the current
     * T1 request. Only quantity with a trustworthy broker price is exposed as filled.
     */
    private fun normalizeSellExecutionForCaller(
        placement: Placement,
        execution: Execution,
    ): Execution {
        val localPositionQuantity = placement.protectionBeforeQuantity
            .coerceAtLeast(execution.requestedQuantity)
            .coerceAtLeast(0)
        if (localPositionQuantity <= 0) return execution

        val stateFilled = execution.states.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val statePricedQuantity = execution.states.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) status.filledQuantity else 0
        }
        val statePricedValue = execution.states.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) {
                status.filledQuantity * status.averagePrice
            } else {
                0.0
            }
        }

        val safetyClosed = execution.safetyFlattenedQuantity.coerceAtLeast(0)
        val safetyPriced = if (safetyClosed > 0 && execution.safetyFlattenAveragePrice > 0.0) {
            safetyClosed
        } else {
            0
        }
        val safetyValue = safetyPriced * execution.safetyFlattenAveragePrice

        val networkFilled = (stateFilled - safetyClosed).coerceAtLeast(0)
        val networkPriced = (statePricedQuantity - safetyPriced)
            .coerceAtLeast(0)
            .coerceAtMost(networkFilled)
        val networkValue = (statePricedValue - safetyValue).coerceAtLeast(0.0)
        val networkAverage = if (networkPriced > 0) networkValue / networkPriced else 0.0

        val accounting = LiveExitAccounting.reconcileFullBrokerHistory(
            localPositionQuantity = localPositionQuantity,
            brokerLongBeforeQuantity = placement.brokerLongBeforeQuantity,
            priorClosedAveragePrice = placement.preFillAveragePrice,
            networkFilledQuantity = networkFilled,
            networkPricedQuantity = networkPriced,
            networkAveragePrice = networkAverage,
            safetyFlattenedQuantity = safetyClosed,
            safetyFlattenAveragePrice = execution.safetyFlattenAveragePrice,
            brokerFlatAfterSafetyAction = execution.brokerFlatAfterSafetyAction,
        )

        if (accounting.requiresPnlUncertaintyLock) {
            UpstoxComplianceRegistry.setProtectionFault(
                "LIVE SELL closed quantity has incomplete fill pricing; P&L reconciliation locked",
            )
        }

        val callerFilled = accounting.knownPricedQuantity
        return execution.copy(
            filledQuantity = callerFilled,
            pendingQuantity = (localPositionQuantity - callerFilled).coerceAtLeast(0),
            averagePrice = accounting.knownWeightedAveragePrice,
            pricedQuantity = callerFilled,
        )
    }

    private fun currentBrokerLongQuantity(instrumentKey: String): Int? = runCatching {
        val quantity = UpstoxRecoveryClient(accessToken).positionFor(instrumentKey)?.quantity ?: 0
        if (quantity < 0) {
            UpstoxComplianceRegistry.setProtectionFault("Unexpected broker short position detected during reconciliation")
            error("Unexpected broker short position")
        }
        quantity
    }.getOrNull()

    private fun weightedAverage(
        firstQty: Int,
        firstAvg: Double,
        secondQty: Int,
        secondAvg: Double,
    ): Double {
        val q1 = if (firstQty > 0 && firstAvg > 0.0) firstQty else 0
        val q2 = if (secondQty > 0 && secondAvg > 0.0) secondQty else 0
        val total = q1 + q2
        return if (total > 0) ((q1 * firstAvg) + (q2 * secondAvg)) / total else 0.0
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
            return Execution(emptyList(), placement.networkQuantity, 0, 0, 0.0, emptyList(), pricedQuantity = 0)
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
        val networkPricedQty = network.pricedQuantity.coerceIn(0, networkQty)
        val networkValue = if (networkPricedQty > 0 && network.averagePrice > 0.0) {
            networkPricedQty * network.averagePrice
        } else 0.0
        val pricedQty = (if (preValue > 0.0) preQty else 0) + networkPricedQty
        val average = if (pricedQty > 0) (preValue + networkValue) / pricedQty else 0.0
        return Execution(
            orderIds = network.orderIds,
            requestedQuantity = expectedQuantity,
            filledQuantity = total,
            pendingQuantity = (expectedQuantity - total).coerceAtLeast(0),
            averagePrice = average,
            states = network.states,
            pricedQuantity = pricedQty.coerceAtMost(total),
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
        return Execution(
            orderIds = orderIds,
            requestedQuantity = requestedQuantity,
            filledQuantity = filled,
            pendingQuantity = pending,
            averagePrice = avg,
            states = statuses,
            pricedQuantity = pricedQty.coerceAtMost(filled),
        )
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
        private const val EMERGENCY_FLATTEN_RETRIES = 2
        private const val DEFAULT_TICK_SIZE = 0.05
        private const val PLACE_ORDER_V3 = "https://api-hft.upstox.com/v3/order/place"
        private const val CANCEL_ORDER_V3 = "https://api-hft.upstox.com/v3/order/cancel"
        private const val ORDER_DETAILS_V2 = "https://api.upstox.com/v2/order/details"
        private const val ORDER_BOOK_V2 = "https://api.upstox.com/v2/order/retrieve-all"
    }
}
