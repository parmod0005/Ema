package com.parmod.ema.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBrokerReconciliationRegistryTest {
    @Test
    fun `unpriced reconciliation survives ordinary clear`() {
        val key = "NSE_FO|UNPRICED-${System.nanoTime()}"
        LiveBrokerReconciliationRegistry.observe(
            instrumentKey = key,
            localPositionQuantity = 100,
            brokerRemainingQuantity = 60,
            unpricedClosedQuantity = 20,
        )

        LiveBrokerReconciliationRegistry.clear(key)

        val snapshot = LiveBrokerReconciliationRegistry.snapshot(key)
        assertTrue(snapshot != null)
        assertEquals(60, snapshot!!.brokerRemainingQuantity)
        assertEquals(20, snapshot.unpricedClosedQuantity)
    }

    @Test
    fun `priced snapshot can be cleared normally`() {
        val key = "NSE_FO|PRICED-${System.nanoTime()}"
        LiveBrokerReconciliationRegistry.observe(
            instrumentKey = key,
            localPositionQuantity = 100,
            brokerRemainingQuantity = 50,
            unpricedClosedQuantity = 0,
        )

        LiveBrokerReconciliationRegistry.clear(key)

        assertNull(LiveBrokerReconciliationRegistry.snapshot(key))
    }
}
