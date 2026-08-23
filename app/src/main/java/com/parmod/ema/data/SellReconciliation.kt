package com.parmod.ema.data

import kotlin.math.max
import kotlin.math.min

/**
 * Pure broker/local quantity reconciliation for LIVE SELLs.
 *
 * [protectedQuantity] is the latest VARDHANI exchange-held protection size and is used as
 * the broker-side memory of the local position before this exit. [brokerLongQuantity] is
 * authoritative for how much can still be sold without creating a short.
 */
internal object SellReconciliation {
    data class Plan(
        val reconciledPositionQuantity: Int,
        val reconciledPriorClosedQuantity: Int,
        val preFilledQuantity: Int,
        val networkSellQuantity: Int,
        val residualBrokerLongIfFilled: Int,
    )

    fun plan(
        requestedQuantity: Int,
        protectedQuantity: Int,
        brokerLongQuantity: Int,
    ): Plan {
        require(requestedQuantity > 0)
        require(protectedQuantity >= 0)
        require(brokerLongQuantity >= 0)

        val reconciledPositionQuantity = max(requestedQuantity, protectedQuantity)
        val reconciledPriorClosedQuantity =
            (reconciledPositionQuantity - brokerLongQuantity).coerceAtLeast(0)
        // Only the part relevant to this caller's requested SELL may be credited to the
        // current execution. The uncapped reconciledPriorClosedQuantity is retained so a
        // later broker-flat safety reconciliation can clear the entire local position.
        val preFilledQuantity = reconciledPriorClosedQuantity.coerceAtMost(requestedQuantity)
        val networkSellQuantity =
            min((requestedQuantity - preFilledQuantity).coerceAtLeast(0), brokerLongQuantity)
        val residualBrokerLongIfFilled =
            (brokerLongQuantity - networkSellQuantity).coerceAtLeast(0)

        return Plan(
            reconciledPositionQuantity = reconciledPositionQuantity,
            reconciledPriorClosedQuantity = reconciledPriorClosedQuantity,
            preFilledQuantity = preFilledQuantity,
            networkSellQuantity = networkSellQuantity,
            residualBrokerLongIfFilled = residualBrokerLongIfFilled,
        )
    }
}
