package com.parmod.ema.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SellReconciliationTest {
    @Test
    fun partial_protective_fill_reduces_network_sell_and_preserves_exact_residual() {
        val plan = SellReconciliation.plan(
            requestedQuantity = 50,
            protectedQuantity = 100,
            brokerLongQuantity = 80,
        )
        assertEquals(100, plan.reconciledPositionQuantity)
        assertEquals(20, plan.preFilledQuantity)
        assertEquals(30, plan.networkSellQuantity)
        assertEquals(50, plan.residualBrokerLongIfFilled)
    }

    @Test
    fun protective_fill_larger_than_requested_never_sends_an_extra_sell() {
        val plan = SellReconciliation.plan(
            requestedQuantity = 50,
            protectedQuantity = 100,
            brokerLongQuantity = 40,
        )
        assertEquals(50, plan.preFilledQuantity)
        assertEquals(0, plan.networkSellQuantity)
        assertEquals(40, plan.residualBrokerLongIfFilled)
    }

    @Test
    fun full_close_after_partial_stop_fill_sells_only_actual_broker_long() {
        val plan = SellReconciliation.plan(
            requestedQuantity = 100,
            protectedQuantity = 100,
            brokerLongQuantity = 80,
        )
        assertEquals(20, plan.preFilledQuantity)
        assertEquals(80, plan.networkSellQuantity)
        assertEquals(0, plan.residualBrokerLongIfFilled)
    }

    @Test
    fun network_sell_can_never_exceed_broker_long() {
        val plan = SellReconciliation.plan(
            requestedQuantity = 50,
            protectedQuantity = 0,
            brokerLongQuantity = 10,
        )
        assertEquals(10, plan.networkSellQuantity)
        assertEquals(0, plan.residualBrokerLongIfFilled)
    }
}
