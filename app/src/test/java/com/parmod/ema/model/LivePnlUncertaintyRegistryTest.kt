package com.parmod.ema.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePnlUncertaintyRegistryTest {
    private fun position(id: String) = PaperPosition(
        side = PositionSide.CE,
        strike = 24_500.0,
        quantity = 65,
        entryPrice = 100.0,
        currentPrice = 110.0,
        openedAtMillis = System.currentTimeMillis(),
        instrumentKey = "TEST",
        executionMode = ExecutionMode.LIVE,
        brokerEntryOrderId = id,
    )

    @Test
    fun `mark and clear use stable live broker identity`() {
        val original = position("ORDER-UNCERTAINTY-1")
        val copied = original.copy(quantity = 32)

        LivePnlUncertaintyRegistry.clear(original)
        assertFalse(LivePnlUncertaintyRegistry.isMarked(original))

        LivePnlUncertaintyRegistry.mark(original)
        assertTrue(LivePnlUncertaintyRegistry.isMarked(copied))

        LivePnlUncertaintyRegistry.clear(copied)
        assertFalse(LivePnlUncertaintyRegistry.isMarked(original))
    }
}
