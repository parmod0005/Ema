package com.parmod.ema.data

import kotlin.math.min

/**
 * Pure fail-closed accounting for a LIVE exit.
 *
 * UpstoxOrderClient.Execution.filledQuantity can include a broker-side pre-fill that was
 * discovered while reconciling an already-fired protective order. execution.states can
 * also include emergency safety-flatten order states appended after the normal SELL.
 *
 * This helper separates normal network fills, broker pre-fills and safety flatten fills,
 * and deliberately does not invent prices for any closed quantity whose fill price cannot
 * be established. Such quantity is reported as unpriced so the caller can persist a P&L
 * uncertainty safety lock.
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
        val stateFilled = execution.states.sumOf { it.filledQuantity.coerceAtLeast(0) }
        val statePricedQuantity = execution.states.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) status.filledQuantity else 0
        }
        val stateValue = execution.states.sumOf { status ->
            if (status.filledQuantity > 0 && status.averagePrice > 0.0) {
                status.filledQuantity * status.averagePrice
            } else {
                0.0
            }
        }

        val safetyQuantity = execution.safetyFlattenedQuantity.coerceAtLeast(0)
        val safetyPricedQuantity = if (safetyQuantity > 0 && execution.safetyFlattenAveragePrice > 0.0) {
            safetyQuantity
        } else {
            0
        }
        val safetyValue = safetyPricedQuantity * execution.safetyFlattenAveragePrice

        // emergencyFlattenResidual appends its Status rows to execution.states, so subtract
        // the separately reported safety leg before deriving the current request's normal
        // network fill. Clamp to execution.filledQuantity because that field intentionally
        // excludes the later safety-flatten leg.
        val networkFilled = (stateFilled - safetyQuantity)
            .coerceAtLeast(0)
            .coerceAtMost(execution.filledQuantity.coerceAtLeast(0))
        val networkPricedQuantity = (statePricedQuantity - safetyPricedQuantity)
            .coerceAtLeast(0)
            .coerceAtMost(networkFilled)
        val networkValue = (stateValue - safetyValue).coerceAtLeast(0.0)
        val networkAverage = if (networkPricedQuantity > 0) {
            networkValue / networkPricedQuantity
        } else {
            0.0
        }

        return reconcile(
            requestedQuantity = requestedQuantity,
            executionFilledQuantity = execution.filledQuantity,
            networkFilledQuantity = networkFilled,
            networkPricedQuantity = networkPricedQuantity,
            networkAveragePrice = networkAverage,
            safetyFlattenedQuantity = safetyQuantity,
            safetyFlattenAveragePrice = execution.safetyFlattenAveragePrice,
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

        // Broker pre-fills, unpriced network rows and any broker-flat gap remain unknown.
        // Never allocate a quote/LTP fallback to them: that would manufacture historical P&L.
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
