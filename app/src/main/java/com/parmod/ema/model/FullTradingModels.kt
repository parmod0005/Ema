package com.parmod.ema.model

/** Independent runtime/UI state for one index. */
data class FullMarketState(
    val index: MarketIndex,
    val isConnected: Boolean = false,
    val expiry: String = "",
    val availableExpiries: List<String> = emptyList(),
    val spotPrice: Double = 0.0,
    val optionChain: List<OptionQuote> = emptyList(),
    val engine1: EngineState = EngineState(EngineId.ENGINE_1_TREND, "ENGINE 1 · TREND / BREAKOUT"),
    val engine2: EngineState = EngineState(EngineId.ENGINE_2_AVWAP_LIQUIDITY, "ENGINE 2 · AVWAP / LIQUIDITY + D30"),
    val engine3: EngineState = EngineState(EngineId.ENGINE_3_V76_SCALPER, "ENGINE 3 · V7.6 REVERSAL RUNNER"),
    val recoveredRealizedPnl: Double = TradingRecoveryRegistry.startupTodayRealizedPnl()[index] ?: 0.0,
    val recoveredPnlUncertain: Boolean = TradingRecoveryRegistry.startupHasUnpricedRecoveredLive(index),
    val tradeLog: List<TradeLogEntry> = TradingRecoveryRegistry.startupTradeLog(index),
    val lastTickMillis: Long = 0L,
    val ticksReceived: Long = 0L,
    val marketDepthMode: String = "WAITING",
    val marketDepthLevels: Int = 0,
    val riskLocked: Boolean = recoveredPnlUncertain || recoveredRealizedPnl <= -TradingRiskConfig().dailyLossLimitInr,
    val riskReason: String = when {
        recoveredPnlUncertain -> "Recovered LIVE exit P&L is unpriced · daily safety lock active"
        riskLocked -> "Recovered daily loss lock is active"
        else -> "Risk gates clear"
    },
    val message: String = "Ready",
) {
    val engines: List<EngineState> get() = listOf(engine1, engine2, engine3)
    val realizedPnl: Double get() = recoveredRealizedPnl + engines.sumOf { it.performance.realizedPnl }
    val openPnl: Double get() = engines.sumOf { it.openPnl }
    val totalPnl: Double get() = realizedPnl + openPnl
    val connectedAgeMillis: Long get() = if (lastTickMillis <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - lastTickMillis).coerceAtLeast(0L)

    fun engine(id: EngineId): EngineState = when (id) {
        EngineId.ENGINE_1_TREND -> engine1
        EngineId.ENGINE_2_AVWAP_LIQUIDITY -> engine2
        EngineId.ENGINE_3_V76_SCALPER -> engine3
    }

    fun withEngine(id: EngineId, value: EngineState): FullMarketState = when (id) {
        EngineId.ENGINE_1_TREND -> copy(engine1 = value)
        EngineId.ENGINE_2_AVWAP_LIQUIDITY -> copy(engine2 = value)
        EngineId.ENGINE_3_V76_SCALPER -> copy(engine3 = value)
    }
}

data class FullDashboardState(
    val marketSelection: MarketSelection = MarketSelection.BOTH,
    val tradingMode: TradingMode = TradingMode.AUTO,
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val liveArmMode: LiveArmMode = LiveArmMode.DISARMED,
    val emergencyKill: Boolean = false,
    val enabledEngines: Set<EngineId> = EngineId.entries.toSet(),
    val engineTimeframes: Map<EngineId, EngineTimeframeConfig> = mapOf(
        EngineId.ENGINE_1_TREND to EngineTimeframeConfig.E1_DEFAULT,
        EngineId.ENGINE_2_AVWAP_LIQUIDITY to EngineTimeframeConfig.E2_DEFAULT,
        EngineId.ENGINE_3_V76_SCALPER to EngineTimeframeConfig.E3_DEFAULT,
    ),
    val niftyLots: Int = 1,
    val sensexLots: Int = 1,
    val riskConfig: TradingRiskConfig = TradingRiskConfig(),
    val markets: Map<MarketIndex, FullMarketState> = mapOf(
        MarketIndex.NIFTY to FullMarketState(MarketIndex.NIFTY),
        MarketIndex.SENSEX to FullMarketState(MarketIndex.SENSEX),
    ),
    val message: String = "PAPER default · select markets and connect Upstox",
) {
    val visibleMarkets: List<FullMarketState>
        get() = marketSelection.indexes.mapNotNull(markets::get)

    val combinedRealizedPnl: Double get() = visibleMarkets.sumOf { it.realizedPnl }
    val combinedOpenPnl: Double get() = visibleMarkets.sumOf { it.openPnl }
    val combinedPnl: Double get() = combinedRealizedPnl + combinedOpenPnl

    fun lotsFor(index: MarketIndex): Int = if (index == MarketIndex.NIFTY) niftyLots else sensexLots
    fun market(index: MarketIndex): FullMarketState = markets.getValue(index)
    fun withMarket(index: MarketIndex, value: FullMarketState): FullDashboardState = copy(markets = markets + (index to value))
}
