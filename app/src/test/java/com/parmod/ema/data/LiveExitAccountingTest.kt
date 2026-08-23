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
}
