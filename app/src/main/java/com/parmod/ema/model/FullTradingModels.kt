package com.parmod.ema.model

import com.parmod.ema.engine.AdaptiveExitEngine
import kotlin.math.max

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
     *
     * UpstoxOrderClient exposes caller-visible SELL quantity only when its fill price is
     * known. Therefore an unmarked zero-quantity position can be finalized with the P&L
     * already accumulated in [PaperPosition.realizedPartialPnl]. If an earlier exit leg was
     * explicitly marked uncertain, we close the local phantom but persist a null P&L and
     * engage the uncertainty lock instead of manufacturing a historical price.
     */
    private fun normalizeBrokerFlatLivePositions(): FullMarketState {
        var normalized = this
        EngineId.entries.forEach { engineId ->
            val state = normalized.engine(engineId)
            val position = state.position ?: return@forEach
            if (position.executionMode != ExecutionMode.LIVE || position.quantity > 0) return@forEach

            val uncertain = LivePnlUncertaintyRegistry.isMarked(position)
            val now = System.currentTimeMillis()
            val log = normalized.tradeLog.toMutableList()
            val row = log.indexOfLast {
                it.engineId == engineId &&
                    it.status == TradeStatus.OPEN &&
                    it.entryTimeMillis == position.openedAtMillis
            }

            val finalizedPnl = if (uncertain) {
                null
            } else {
                position.realizedPartialPnl - AdaptiveExitEngine.PAPER_ROUND_TRIP_COST_INR
            }

            if (row >= 0) {
                log[row] = log[row].copy(
                    status = TradeStatus.CLOSED,
                    exitPrice = null,
                    exitSpot = normalized.spotPrice.takeIf { it > 0.0 },
                    exitTimeMillis = now,
                    pnl = finalizedPnl,
                    exitReason = if (uncertain) {
                        "BROKER SAFETY FLAT · P&L UNPRICED"
                    } else {
                        "BROKER SAFETY FLAT · PRICED RECONCILIATION"
                    },
                )
            }

            val performance = if (finalizedPnl == null) {
                state.performance
            } else {
                val old = state.performance
                val realized = old.realizedPnl + finalizedPnl
                val peak = max(old.peakEquity, realized)
                val drawdown = (peak - realized).coerceAtLeast(0.0)
                old.copy(
                    trades = old.trades + 1,
                    wins = old.wins + if (finalizedPnl > 0.0) 1 else 0,
                    losses = old.losses + if (finalizedPnl < 0.0) 1 else 0,
                    realizedPnl = realized,
                    grossProfit = old.grossProfit + finalizedPnl.coerceAtLeast(0.0),
                    grossLoss = old.grossLoss + (-finalizedPnl).coerceAtLeast(0.0),
                    peakEquity = peak,
                    maxDrawdown = max(old.maxDrawdown, drawdown),
                )
            }

            LivePnlUncertaintyRegistry.clear(position)
            normalized = normalized
                .withEngine(
                    engineId,
                    state.copy(
                        position = null,
                        performance = performance,
                        message = if (uncertain) {
                            "BROKER SAFETY FLAT · local position cleared · P&L unpriced"
                        } else {
                            "BROKER SAFETY FLAT · priced closure finalized"
                        },
                    ),
                )
                .copy(
                    recoveredPnlUncertain = normalized.recoveredPnlUncertain || uncertain,
                    tradeLog = log,
                    message = if (uncertain) {
                        "LIVE broker-flat reconciliation · P&L uncertainty lock active"
                    } else {
                        "LIVE broker-flat reconciliation complete · LIVE disarmed"
                    },
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
        val brokerSafetyFlatTransition = value.engines.any {
            it.position?.executionMode == ExecutionMode.LIVE && it.position.quantity <= 0
        }
        val adjusted = value.withRiskPolicy(riskConfig.dailyLossLimitInr)
        return copy(
            markets = markets + (index to adjusted),
            liveArmMode = if (
                executionMode == ExecutionMode.LIVE &&
                (adjusted.riskLocked || brokerSafetyFlatTransition)
            ) {
                LiveArmMode.DISARMED
            } else {
                liveArmMode
            },
        )
    }
}
