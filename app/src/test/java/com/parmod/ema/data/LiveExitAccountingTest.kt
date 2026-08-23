package com.parmod.ema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExitAccountingTest {
    @Test
    fun `normal fully priced exit closes local quantity`() {
        val result = LiveExitAccounting.reconcile(
            requestedQuantity = 100,
            executionFilledQuantity = 100,
            networkFilledQuantity = 100,
            networkAveragePrice = 120.0,
            safetyFlattenedQuantity = 0,
            safetyFlattenAveragePrice = 0.0,
            brokerFlatAfterSafetyAction = false,
        )

        assertEquals(100, result.effectiveClosedQuantity)
        assertEquals(0, result.remainingLocalQuantity)
        assertEquals(0, result.unpricedClosedQuantity)
        assertEquals(120.0, result.knownWeightedAveragePrice, 0.0001)
        assertFalse(result.requiresPnlUncertaintyLock)
    }

    @Test
    fun `partial network exit leaves local residual when broker is not flat`() {
        val result = LiveExitAccounting.reconcile(
            requestedQuantity = 100,
            executionFilledQuantity = 40,
            networkFilledQuantity = 40,
            networkAveragePrice = 115.0,
            safetyFlattenedQuantity = 0,
            safetyFlattenAveragePrice = 0.0,
            brokerFlatAfterSafetyAction = false,
        )

        assertEquals(40, result.effectiveClosedQuantity)
        assertEquals(60, result.remainingLocalQuantity)
        assertEquals(0, result.unpricedClosedQuantity)
    }

    @Test
    fun `emergency safety flatten closes local residual when broker is flat`() {
        val result = LiveExitAccounting.reconcile(
            requestedQuantity = 100,
            executionFilledQuantity = 40,
            networkFilledQuantity = 40,
            networkAveragePrice = 115.0,
            safetyFlattenedQuantity = 60,
            safetyFlattenAveragePrice = 112.0,
            brokerFlatAfterSafetyAction = true,
        )

        assertEquals(100, result.effectiveClosedQuantity)
        assertEquals(0, result.remainingLocalQuantity)
        assertEquals(100, result.knownPricedQuantity)
        assertEquals(113.2, result.knownWeightedAveragePrice, 0.0001)
        assertFalse(result.requiresPnlUncertaintyLock)
    }

    @Test
    fun `broker prefill remains unpriced and requires safety lock`() {
        val result = LiveExitAccounting.reconcile(
            requestedQuantity = 100,
            executionFilledQuantity = 100,
            networkFilledQuantity = 80,
            networkAveragePrice = 118.0,
            safetyFlattenedQuantity = 0,
            safetyFlattenAveragePrice = 0.0,
            brokerFlatAfterSafetyAction = false,
        )

        assertEquals(20, result.brokerPreFilledQuantity)
        assertEquals(20, result.unpricedClosedQuantity)
        assertEquals(0, result.remainingLocalQuantity)
        assertTrue(result.requiresPnlUncertaintyLock)
    }

    @Test
    fun `broker flat gap closes local state but never fabricates pnl`() {
        val result = LiveExitAccounting.reconcile(
            requestedQuantity = 100,
            executionFilledQuantity = 30,
            networkFilledQuantity = 30,
            networkAveragePrice = 110.0,
            safetyFlattenedQuantity = 20,
            safetyFlattenAveragePrice = 108.0,
            brokerFlatAfterSafetyAction = true,
        )

        assertEquals(100, result.effectiveClosedQuantity)
        assertEquals(0, result.remainingLocalQuantity)
        assertEquals(50, result.unpricedClosedQuantity)
        assertTrue(result.requiresPnlUncertaintyLock)
    }

    @Test
    fun `from execution subtracts appended safety states exactly once`() {
        val normal = status("NORMAL", filled = 40, average = 115.0)
        val safety = status("SAFETY", filled = 60, average = 112.0)
        val execution = UpstoxOrderClient.Execution(
            orderIds = listOf("NORMAL", "SAFETY"),
            requestedQuantity = 40,
            filledQuantity = 40,
            pendingQuantity = 0,
            averagePrice = 115.0,
            states = listOf(normal, safety),
            safetyFlattenedQuantity = 60,
            safetyFlattenAveragePrice = 112.0,
            brokerFlatAfterSafetyAction = true,
        )

        val result = LiveExitAccounting.fromExecution(100, execution)

        assertEquals(40, result.networkFilledQuantity)
        assertEquals(60, result.safetyFlattenedQuantity)
        assertEquals(100, result.effectiveClosedQuantity)
        assertEquals(100, result.knownPricedQuantity)
        assertEquals(113.2, result.knownWeightedAveragePrice, 0.0001)
        assertFalse(result.requiresPnlUncertaintyLock)
    }

    @Test
    fun `from execution preserves unpriced network fill as uncertainty`() {
        val unpriced = status("NORMAL", filled = 20, average = 0.0)
        val execution = UpstoxOrderClient.Execution(
            orderIds = listOf("NORMAL"),
            requestedQuantity = 20,
            filledQuantity = 20,
            pendingQuantity = 0,
            averagePrice = 0.0,
            states = listOf(unpriced),
        )

        val result = LiveExitAccounting.fromExecution(50, execution)

        assertEquals(20, result.networkFilledQuantity)
        assertEquals(0, result.knownPricedQuantity)
        assertEquals(20, result.unpricedClosedQuantity)
        assertEquals(30, result.remainingLocalQuantity)
        assertTrue(result.requiresPnlUncertaintyLock)
    }

    private fun status(orderId: String, filled: Int, average: Double) = UpstoxOrderClient.Status(
        orderId = orderId,
        state = "complete",
        averagePrice = average,
        quantity = filled,
        filledQuantity = filled,
        pendingQuantity = 0,
        statusMessage = "",
    )
}
