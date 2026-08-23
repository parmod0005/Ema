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
        val preFilledQuantity =
            (reconciledPositionQuantity - brokerLongQuantity).coerceIn(0, requestedQuantity)
        val networkSellQuantity =
            min((requestedQuantity - preFilledQuantity).coerceAtLeast(0), brokerLongQuantity)
        val residualBrokerLongIfFilled =
            (brokerLongQuantity - networkSellQuantity).coerceAtLeast(0)

        return Plan(
            reconciledPositionQuantity = reconciledPositionQuantity,
            preFilledQuantity = preFilledQuantity,
            networkSellQuantity = networkSellQuantity,
            residualBrokerLongIfFilled = residualBrokerLongIfFilled,
        )
    }
}
