package com.parmod.ema.model

import com.parmod.ema.engine.AdaptiveExitEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `fully priced zero quantity live position closes with pnl and disarms live without false uncertainty`() {
        val openedAt = System.currentTimeMillis()
        val realizedBeforeBaseCost = 780.0
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
            target1Hit = true,
            target1ExitQuantity = 65,
            realizedPartialPnl = realizedBeforeBaseCost,
            instrumentKey = "NSE_FO|TEST",
            executionMode = ExecutionMode.LIVE,
            brokerEntryOrderId = "ENTRY-PRICED-ZERO",
        )
        val log = trade(openedAt, "ENTRY-PRICED-ZERO")
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
        val expectedPnl = realizedBeforeBaseCost - AdaptiveExitEngine.PAPER_ROUND_TRIP_COST_INR
        assertNull(normalized.engine1.position)
        assertFalse(normalized.recoveredPnlUncertain)
        assertFalse(normalized.riskLocked)
        assertEquals(1, normalized.engine1.performance.trades)
        assertEquals(expectedPnl, normalized.engine1.performance.realizedPnl, 0.0001)
        assertEquals(TradeStatus.CLOSED, normalized.tradeLog.single().status)
        assertEquals(expectedPnl, normalized.tradeLog.single().pnl!!, 0.0001)
        assertTrue(normalized.tradeLog.single().exitReason.contains("PRICED RECONCILIATION"))
        assertEquals(LiveArmMode.DISARMED, state.liveArmMode)
    }

    @Test
    fun `marked uncertain zero quantity live position closes unpriced locks risk and disarms live`() {
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
            brokerEntryOrderId = "ENTRY-UNPRICED-ZERO",
        )
        LivePnlUncertaintyRegistry.mark(position)
        val rawMarket = FullMarketState(
            index = MarketIndex.NIFTY,
            engine1 = EngineState(
                id = EngineId.ENGINE_1_TREND,
                name = "ENGINE 1 · TREND / BREAKOUT",
                position = position,
            ),
            tradeLog = listOf(trade(openedAt, "ENTRY-UNPRICED-ZERO")),
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
        assertEquals(0, normalized.engine1.performance.trades)
        assertEquals(TradeStatus.CLOSED, normalized.tradeLog.single().status)
        assertNull(normalized.tradeLog.single().pnl)
        assertTrue(normalized.tradeLog.single().exitReason.contains("P&L UNPRICED"))
        assertEquals(LiveArmMode.DISARMED, state.liveArmMode)
    }

    private fun trade(openedAt: Long, brokerEntryOrderId: String) = TradeLogEntry(
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
        brokerEntryOrderId = brokerEntryOrderId,
    )
}
