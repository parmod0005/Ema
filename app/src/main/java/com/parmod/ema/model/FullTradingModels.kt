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
    val riskLocked: Boolean = RiskLockPolicy.evaluate(
        recoveredPnlUncertain = recoveredPnlUncertain,
        realizedPnl = recoveredRealizedPnl,
        dailyLossLimitInr = TradingRiskConfig().dailyLossLimitInr,
    ).locked,
    val riskReason: String = RiskLockPolicy.evaluate(
        recoveredPnlUncertain = recoveredPnlUncertain,
        realizedPnl = recoveredRealizedPnl,
        dailyLossLimitInr = TradingRiskConfig().dailyLossLimitInr,
    ).reason,
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

    /**
     * A zero/negative-quantity LIVE position is never a valid active local position.
     * The only audited path that can produce one is broker reconciliation proving the
     * position flat after a safety action. Drop the phantom position immediately, close
     * its trade row with unknown P&L, and engage the daily uncertainty lock rather than
     * manufacturing an exit price from LTP/bid data.
     */
    private fun normalizeBrokerFlatLivePositions(): FullMarketState {
        var normalized = this
        EngineId.entries.forEach { engineId ->
            val state = normalized.engine(engineId)
            val position = state.position ?: return@forEach
            if (position.executionMode != ExecutionMode.LIVE || position.quantity > 0) return@forEach

            val now = System.currentTimeMillis()
            val log = normalized.tradeLog.toMutableList()
            val row = log.indexOfLast {
                it.engineId == engineId &&
                    it.status == TradeStatus.OPEN &&
                    it.entryTimeMillis == position.openedAtMillis
            }
            if (row >= 0) {
                log[row] = log[row].copy(
                    status = TradeStatus.CLOSED,
                    exitPrice = null,
                    exitSpot = normalized.spotPrice.takeIf { it > 0.0 },
                    exitTimeMillis = now,
                    pnl = null,
                    exitReason = "BROKER SAFETY FLAT · P&L UNPRICED",
                )
            }
            LivePnlUncertaintyRegistry.clear(position)
            normalized = normalized
                .withEngine(
                    engineId,
                    state.copy(
                        position = null,
                        message = "BROKER SAFETY FLAT · local position cleared · P&L unpriced",
                    ),
                )
                .copy(
                    recoveredPnlUncertain = true,
                    tradeLog = log,
                    message = "LIVE broker-flat reconciliation · P&L uncertainty lock active",
                )
        }
        return normalized
    }

    fun withRiskPolicy(dailyLossLimitInr: Double): FullMarketState {
        val normalized = normalizeBrokerFlatLivePositions()
        val decision = RiskLockPolicy.evaluate(
            recoveredPnlUncertain = normalized.recoveredPnlUncertain,
            realizedPnl = normalized.realizedPnl,
            dailyLossLimitInr = dailyLossLimitInr,
        )
        return if (normalized.riskLocked == decision.locked && normalized.riskReason == decision.reason) {
            normalized
        } else {
            normalized.copy(riskLocked = decision.locked, riskReason = decision.reason)
        }
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
        get() = marketSelection.indexes.map(::market)

    val combinedRealizedPnl: Double get() = visibleMarkets.sumOf { it.realizedPnl }
    val combinedOpenPnl: Double get() = visibleMarkets.sumOf { it.openPnl }
    val combinedPnl: Double get() = combinedRealizedPnl + combinedOpenPnl

    fun lotsFor(index: MarketIndex): Int = if (index == MarketIndex.NIFTY) niftyLots else sensexLots

    fun market(index: MarketIndex): FullMarketState =
        markets.getValue(index).withRiskPolicy(riskConfig.dailyLossLimitInr)

    fun withMarket(index: MarketIndex, value: FullMarketState): FullDashboardState {
        val adjusted = value.withRiskPolicy(riskConfig.dailyLossLimitInr)
        return copy(
            markets = markets + (index to adjusted),
            liveArmMode = if (executionMode == ExecutionMode.LIVE && adjusted.riskLocked) {
                LiveArmMode.DISARMED
            } else {
                liveArmMode
            },
        )
    }
}
