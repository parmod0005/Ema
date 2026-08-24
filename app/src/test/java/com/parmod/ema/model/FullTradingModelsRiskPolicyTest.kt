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
        val position = livePosition(openedAt, "ENTRY-PRICED-ZERO", quantity = 0, initialQuantity = 65).copy(
            target1Hit = true,
            target1ExitQuantity = 65,
            realizedPartialPnl = realizedBeforeBaseCost,
        )
        val rawMarket = marketWithPosition(position, trade(openedAt, "ENTRY-PRICED-ZERO"))
        val state = liveState().withMarket(MarketIndex.NIFTY, rawMarket)

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
        val position = livePosition(openedAt, "ENTRY-UNPRICED-ZERO", quantity = 0, initialQuantity = 65)
        LivePnlUncertaintyRegistry.mark(position)
        val rawMarket = marketWithPosition(position, trade(openedAt, "ENTRY-UNPRICED-ZERO"))
        val state = liveState().withMarket(MarketIndex.NIFTY, rawMarket)

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

    @Test
    fun `unpriced broker reduction shrinks stale local residual and locks live`() {
        val openedAt = System.currentTimeMillis()
        val position = livePosition(openedAt, "ENTRY-RESIDUAL", quantity = 80, initialQuantity = 100)
        LiveBrokerReconciliationRegistry.observe(
            instrumentKey = position.instrumentKey,
            localPositionQuantity = 100,
            brokerRemainingQuantity = 60,
            unpricedClosedQuantity = 20,
        )
        val state = liveState().withMarket(
            MarketIndex.NIFTY,
            marketWithPosition(position, trade(openedAt, "ENTRY-RESIDUAL")),
        )

        val normalized = state.market(MarketIndex.NIFTY)
        val reconciled = normalized.engine1.position!!
        assertEquals(60, reconciled.quantity)
        assertTrue(LivePnlUncertaintyRegistry.isMarked(reconciled))
        assertTrue(normalized.recoveredPnlUncertain)
        assertTrue(normalized.riskLocked)
        assertEquals(LiveArmMode.DISARMED, state.liveArmMode)
    }

    @Test
    fun `later priced residual close cannot overwrite earlier unpriced pnl`() {
        val openedAt = System.currentTimeMillis()
        val position = livePosition(openedAt, "ENTRY-FINAL-UNKNOWN", quantity = 80, initialQuantity = 100)
        val openTrade = trade(openedAt, "ENTRY-FINAL-UNKNOWN")
        LiveBrokerReconciliationRegistry.observe(
            instrumentKey = position.instrumentKey,
            localPositionQuantity = 100,
            brokerRemainingQuantity = 60,
            unpricedClosedQuantity = 20,
        )
        val afterBrokerReconcile = liveState().withMarket(
            MarketIndex.NIFTY,
            marketWithPosition(position, openTrade),
        )
        val beforeClose = afterBrokerReconcile.market(MarketIndex.NIFTY)
        val bogusPerformance = beforeClose.engine1.performance.copy(
            trades = 1,
            wins = 1,
            realizedPnl = 999.0,
            grossProfit = 999.0,
            peakEquity = 999.0,
        )
        val afterEngineClose = afterBrokerReconcile.withMarket(
            MarketIndex.NIFTY,
            beforeClose.withEngine(
                EngineId.ENGINE_1_TREND,
                beforeClose.engine1.copy(position = null, performance = bogusPerformance),
            ),
        )
        val protectedMarket = afterEngineClose.market(MarketIndex.NIFTY)
        assertEquals(0, protectedMarket.engine1.performance.trades)
        assertEquals(0.0, protectedMarket.engine1.performance.realizedPnl, 0.0001)
        assertTrue(LivePnlUncertaintyRegistry.isMarked(openTrade))

        val numericClosedRow = protectedMarket.tradeLog.single().copy(
            status = TradeStatus.CLOSED,
            exitTimeMillis = System.currentTimeMillis(),
            exitPrice = 120.0,
            pnl = 999.0,
            exitReason = "NORMAL EXIT",
        )
        val finalState = afterEngineClose.withMarket(
            MarketIndex.NIFTY,
            protectedMarket.copy(tradeLog = listOf(numericClosedRow)),
        )
        val finalMarket = finalState.market(MarketIndex.NIFTY)
        assertNull(finalMarket.tradeLog.single().pnl)
        assertTrue(finalMarket.tradeLog.single().exitReason.contains("P&L UNPRICED"))
        assertTrue(finalMarket.riskLocked)
        assertFalse(LivePnlUncertaintyRegistry.isMarked(finalMarket.tradeLog.single()))
    }

    private fun liveState() = FullDashboardState(
        executionMode = ExecutionMode.LIVE,
        liveArmMode = LiveArmMode.AUTO_ARMED,
        marketSelection = MarketSelection.NIFTY,
    )

    private fun marketWithPosition(position: PaperPosition, log: TradeLogEntry) = FullMarketState(
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

    private fun livePosition(
        openedAt: Long,
        brokerEntryOrderId: String,
        quantity: Int,
        initialQuantity: Int,
    ) = PaperPosition(
        side = PositionSide.CE,
        strike = 24_500.0,
        quantity = quantity,
        entryPrice = 100.0,
        currentPrice = 112.0,
        openedAtMillis = openedAt,
        lotSize = 20,
        lots = (quantity / 20).coerceAtLeast(1),
        initialQuantity = initialQuantity,
        instrumentKey = "NSE_FO|$brokerEntryOrderId",
        executionMode = ExecutionMode.LIVE,
        brokerEntryOrderId = brokerEntryOrderId,
    )

    private fun trade(openedAt: Long, brokerEntryOrderId: String) = TradeLogEntry(
        id = openedAt,
        engineId = EngineId.ENGINE_1_TREND,
        engineName = "ENGINE 1 · TREND / BREAKOUT",
        index = MarketIndex.NIFTY,
        side = PositionSide.CE,
        strike = 24_500.0,
        quantity = 100,
        lots = 5,
        entryPrice = 100.0,
        entrySpot = 24_500.0,
        entryTimeMillis = openedAt,
        setup = "TEST",
        executionMode = ExecutionMode.LIVE,
        brokerEntryOrderId = brokerEntryOrderId,
    )
}
