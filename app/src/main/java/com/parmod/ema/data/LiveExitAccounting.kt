package com.parmod.ema.data

import kotlin.math.min

/**
 * Pure fail-closed accounting for a LIVE exit.
 *
 * UpstoxOrderClient.Execution.filledQuantity can include a broker-side pre-fill that was
 * discovered while reconciling an already-fired protective order. execution.states, on the
 * other hand, only describe fills from the network SELL submitted by the current request.
 * Emergency safety flatten fills are carried separately.
 *
 * This helper deliberately does not invent a price for broker pre-fills or for any quantity
 * that disappeared before the current network order. Such quantity is reported as unpriced
 * so the caller can persist a P&L-uncertainty safety lock.
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

    fun reconcile(
        requestedQuantity: Int,
        executionFilledQuantity: Int,
        networkFilledQuantity: Int,
        networkAveragePrice: Double,
        safetyFlattenedQuantity: Int,
        safetyFlattenAveragePrice: Double,
        brokerFlatAfterSafetyAction: Boolean,
    ): Result {
        require(requestedQuantity > 0) { "requestedQuantity must be positive" }

        val executionFilled = executionFilledQuantity.coerceIn(0, requestedQuantity)
        val networkFilled = networkFilledQuantity.coerceIn(0, executionFilled)
        val brokerPreFilled = (executionFilled - networkFilled).coerceAtLeast(0)
        val safetyFilled = safetyFlattenedQuantity.coerceIn(0, requestedQuantity)

        val explicitlyObservedClosed = min(requestedQuantity, executionFilled + safetyFilled)
        val effectiveClosed = if (brokerFlatAfterSafetyAction) requestedQuantity else explicitlyObservedClosed
        val remaining = (requestedQuantity - effectiveClosed).coerceAtLeast(0)

        val pricedNetworkQty = if (networkFilled > 0 && networkAveragePrice > 0.0) networkFilled else 0
        val pricedSafetyQty = if (safetyFilled > 0 && safetyFlattenAveragePrice > 0.0) safetyFilled else 0
        val pricedQty = min(effectiveClosed, pricedNetworkQty + pricedSafetyQty)
        val pricedValue =
            pricedNetworkQty * networkAveragePrice + pricedSafetyQty * safetyFlattenAveragePrice
        val weightedAverage = if (pricedQty > 0) pricedValue / pricedQty else 0.0

        // Broker pre-fills are intentionally unpriced here because Execution does not expose
        // their price independently from the network-order average. A broker-flat gap is also
        // unpriced: it represents quantity closed outside fills we can price with certainty.
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
