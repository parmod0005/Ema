package com.parmod.ema.model

import org.junit.Assert.assertTrue
import org.junit.Test

class FullTradingModelsRiskPolicyTest {
    private fun market(index: MarketIndex, uncertain: Boolean): FullMarketState = FullMarketState(
        index = index,
        recoveredRealizedPnl = 0.0,
        recoveredPnlUncertain = uncertain,
        tradeLog = emptyList(),
        riskLocked = false,
        riskReason = "Risk gates clear",
    )

    @Test
    fun `dashboard market read re-applies uncertainty lock`() {
        val state = FullDashboardState(
            marketSelection = MarketSelection.BOTH,
            markets = mapOf(
                MarketIndex.NIFTY to market(MarketIndex.NIFTY, uncertain = true),
                MarketIndex.SENSEX to market(MarketIndex.SENSEX, uncertain = false),
            ),
        )

        assertTrue(state.market(MarketIndex.NIFTY).riskLocked)
        assertTrue(state.market(MarketIndex.NIFTY).riskReason.contains("unpriced", ignoreCase = true))
    }

    @Test
    fun `visible markets cannot expose stale cleared uncertainty lock`() {
        val state = FullDashboardState(
            marketSelection = MarketSelection.NIFTY,
            markets = mapOf(
                MarketIndex.NIFTY to market(MarketIndex.NIFTY, uncertain = true),
                MarketIndex.SENSEX to market(MarketIndex.SENSEX, uncertain = false),
            ),
        )

        assertTrue(state.visibleMarkets.single().riskLocked)
    }
}
