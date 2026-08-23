package com.parmod.ema.data

import kotlin.math.max
import kotlin.math.min

/**
 * Pure fail-closed accounting for a LIVE exit.
 *
 * UpstoxOrderClient normalizes returned SELL executions so [UpstoxOrderClient.Execution.pricedQuantity]
 * is the total quantity the runtime may safely book with [UpstoxOrderClient.Execution.averagePrice].
 * execution.states can still expose a larger actually-closed quantity when a broker/network fill
 * is missing its price; that difference is deliberately reported as unpriced instead of borrowing
 * LTP/bid as historical P&L.
 */
internal object LiveExitAccounting {
    data class Result(
        val requestedQuantity: Int,
        val effectiveClosedQuantity: Int,
        val remainingLocalQuantity: Int,
        val networkFilledQuantity: Int,
        val brokerPreFilledQuantity: Int,
        val safetyFlattenedQuantity: Int,
        val unpricedClosedQuantity: Int,
        val knownPricedQuantity: Int,
        val knownWeightedAveragePrice: Double,
        val brokerFlatAfterSafetyAction: Boolean,
    ) {
        val requiresPnlUncertaintyLock: Boolean get() = unpricedClosedQuantity > 0
    }

    fun fromExecution(
        requestedQuantity: Int,
        execution: UpstoxOrderClient.Execution,
    ): Result {
        require(requestedQuantity > 0) { "requestedQuantity must be positive" }

        val stateFilled = execution.states.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val safetyFilled = execution.safetyFlattenedQuantity.coerceAtLeast(0)
        val networkStateFilled = (stateFilled - safetyFilled).coerceAtLeast(0)

        // Returned filledQuantity is caller-safe/priced after SELL normalization. states may
        // show additional unpriced closed quantity. Use the larger observed closure for risk,
        // but only pricedQuantity for P&L.
        val observedClosed = max(execution.filledQuantity.coerceAtLeast(0), stateFilled)
        val effectiveClosed = if (execution.brokerFlatAfterSafetyAction) {
            requestedQuantity
        } else {
            min(requestedQuantity, observedClosed)
        }
        val pricedQty = execution.pricedQuantity.coerceIn(0, effectiveClosed)
        val weightedAverage = if (pricedQty > 0 && execution.averagePrice > 0.0) {
            execution.averagePrice
        } else {
            0.0
        }
        val pricedSafety = if (safetyFilled > 0 && execution.safetyFlattenAveragePrice > 0.0) {
            safetyFilled
        } else {
            0
        }
        val estimatedPricedNormal = (pricedQty - pricedSafety).coerceAtLeast(0)
        val networkFilled = min(networkStateFilled, effectiveClosed)
        val brokerPreFilled = (estimatedPricedNormal - networkFilled).coerceAtLeast(0)
        val unpriced = (effectiveClosed - pricedQty).coerceAtLeast(0)

        return Result(
            requestedQuantity = requestedQuantity,
            effectiveClosedQuantity = effectiveClosed,
            remainingLocalQuantity = (requestedQuantity - effectiveClosed).coerceAtLeast(0),
            networkFilledQuantity = networkFilled,
            brokerPreFilledQuantity = brokerPreFilled,
            safetyFlattenedQuantity = min(safetyFilled, effectiveClosed),
            unpricedClosedQuantity = unpriced,
            knownPricedQuantity = pricedQty,
            knownWeightedAveragePrice = weightedAverage,
            brokerFlatAfterSafetyAction = execution.brokerFlatAfterSafetyAction,
        )
    }

    fun reconcile(
        requestedQuantity: Int,
        executionFilledQuantity: Int,
        networkFilledQuantity: Int,
        networkPricedQuantity: Int = networkFilledQuantity,
        networkAveragePrice: Double,
        safetyFlattenedQuantity: Int,
        safetyFlattenAveragePrice: Double,
        brokerFlatAfterSafetyAction: Boolean,
    ): Result {
        require(requestedQuantity > 0) { "requestedQuantity must be positive" }

        val executionFilled = executionFilledQuantity.coerceIn(0, requestedQuantity)
        val networkFilled = networkFilledQuantity.coerceIn(0, executionFilled)
        val pricedNetworkQty = networkPricedQuantity.coerceIn(0, networkFilled)
        val brokerPreFilled = (executionFilled - networkFilled).coerceAtLeast(0)
        val safetyFilled = safetyFlattenedQuantity.coerceIn(0, requestedQuantity)

        val explicitlyObservedClosed = min(requestedQuantity, executionFilled + safetyFilled)
        val effectiveClosed = if (brokerFlatAfterSafetyAction) requestedQuantity else explicitlyObservedClosed
        val remaining = (requestedQuantity - effectiveClosed).coerceAtLeast(0)

        val pricedNetwork = if (pricedNetworkQty > 0 && networkAveragePrice > 0.0) pricedNetworkQty else 0
        val pricedSafetyQty = if (safetyFilled > 0 && safetyFlattenAveragePrice > 0.0) safetyFilled else 0
        val pricedQty = min(effectiveClosed, pricedNetwork + pricedSafetyQty)
        val pricedValue =
            pricedNetwork * networkAveragePrice + pricedSafetyQty * safetyFlattenAveragePrice
        val weightedAverage = if (pricedQty > 0) pricedValue / pricedQty else 0.0
        val unpriced = (effectiveClosed - pricedQty).coerceAtLeast(0)

        return Result(
            requestedQuantity = requestedQuantity,
            effectiveClosedQuantity = effectiveClosed,
            remainingLocalQuantity = remaining,
            networkFilledQuantity = networkFilled,
            brokerPreFilledQuantity = brokerPreFilled,
            safetyFlattenedQuantity = safetyFilled,
            unpricedClosedQuantity = unpriced,
            knownPricedQuantity = pricedQty,
            knownWeightedAveragePrice = weightedAverage,
            brokerFlatAfterSafetyAction = brokerFlatAfterSafetyAction,
        )
    }
}
