package com.parmod.ema.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `zero quantity live position is cleared closed unpriced and live disarmed`() {
        val openedAt = System.currentTimeMillis()
        val position = PaperPosition(
            side = PositionSide.CE,
            strike = 24_500.0,
            quantity = 0,
            entryPrice = 100.0,
            currentPrice = 112.0,
            openedAtMillis = openedAt,
            lotSize = 65,
            lots = 1,
            initialQuantity = 65,
            instrumentKey = "NSE_FO|TEST",
            executionMode = ExecutionMode.LIVE,
            brokerEntryOrderId = "ENTRY-ZERO-QTY",
        )
        val log = TradeLogEntry(
            id = openedAt,
            engineId = EngineId.ENGINE_1_TREND,
            engineName = "ENGINE 1 · TREND / BREAKOUT",
            index = MarketIndex.NIFTY,
            side = PositionSide.CE,
            strike = 24_500.0,
            quantity = 65,
            lots = 1,
            entryPrice = 100.0,
            entrySpot = 24_500.0,
            entryTimeMillis = openedAt,
            setup = "TEST",
            executionMode = ExecutionMode.LIVE,
            brokerEntryOrderId = "ENTRY-ZERO-QTY",
        )
        val rawMarket = FullMarketState(
            index = MarketIndex.NIFTY,
            engine1 = EngineState(
                id = EngineId.ENGINE_1_TREND,
                name = "ENGINE 1 · TREND / BREAKOUT",
                position = position,
            ),
            tradeLog = listOf(log),
            recoveredPnlUncertain = false,
            riskLocked = false,
        )
        val state = FullDashboardState(
            executionMode = ExecutionMode.LIVE,
            liveArmMode = LiveArmMode.AUTO_ARMED,
            marketSelection = MarketSelection.NIFTY,
        ).withMarket(MarketIndex.NIFTY, rawMarket)

        val normalized = state.market(MarketIndex.NIFTY)
        assertNull(normalized.engine1.position)
        assertTrue(normalized.recoveredPnlUncertain)
        assertTrue(normalized.riskLocked)
        assertEquals(TradeStatus.CLOSED, normalized.tradeLog.single().status)
        assertNull(normalized.tradeLog.single().pnl)
        assertTrue(normalized.tradeLog.single().exitReason.contains("P&L UNPRICED"))
        assertEquals(LiveArmMode.DISARMED, state.liveArmMode)
    }
}
