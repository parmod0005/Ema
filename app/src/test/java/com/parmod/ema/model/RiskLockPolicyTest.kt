package com.parmod.ema.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskLockPolicyTest {
    @Test
    fun `unpriced recovered live pnl always locks`() {
        val decision = RiskLockPolicy.evaluate(
            recoveredPnlUncertain = true,
            realizedPnl = 10_000.0,
            dailyLossLimitInr = 3_500.0,
        )
        assertTrue(decision.locked)
        assertTrue(decision.reason.contains("unpriced", ignoreCase = true))
    }

    @Test
    fun `daily loss threshold locks`() {
        val decision = RiskLockPolicy.evaluate(
            recoveredPnlUncertain = false,
            realizedPnl = -3_500.0,
            dailyLossLimitInr = 3_500.0,
        )
        assertTrue(decision.locked)
    }

    @Test
    fun `ordinary positive pnl remains unlocked`() {
        val decision = RiskLockPolicy.evaluate(
            recoveredPnlUncertain = false,
            realizedPnl = 850.0,
            dailyLossLimitInr = 3_500.0,
        )
        assertFalse(decision.locked)
    }
}
