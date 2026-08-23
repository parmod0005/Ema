package com.parmod.ema.model

enum class TradingMode { MANUAL, AUTO }
enum class ExecutionMode { PAPER, LIVE }
enum class AppMode { LIVE_MARKET, BACKTEST }
enum class ConnectionMode { DEMO, UPSTOX }
enum class MarketIndex { NIFTY, SENSEX }
enum class SignalAction { BUY_CE, BUY_PE, WAIT }
enum class TrendDirection { BULLISH, BEARISH, NEUTRAL }
enum class PositionSide { CE, PE }
enum class EngineId { ENGINE_1_TREND, ENGINE_2_AVWAP_LIQUIDITY, ENGINE_3_V76_SCALPER }
enum class TradeStatus { OPEN, CLOSED }

data class OptionQuote(
    val strike: Double,
    val type: String,
    val ltp: Double,
    val openInterest: Long,
    val changeInOpenInterest: Long,
    val delta: Double,
    val gamma: Double,
    val isAtm: Boolean = false,
    val instrumentKey: String = "",
    val lastTickMillis: Long = 0L,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val volume: Long = 0L,
    val lotSize: Int = 0,
)

data class SignalSnapshot(
    val action: SignalAction,
    val confidence: Int,
    val trend: TrendDirection,
    val entry: Double?,
    val stopLoss: Double?,
    val target: Double?,
    val reasons: List<String>,
    val setup: String = "WAIT",
)

data class TradeLogEntry(
    val id: Long,
    val engineId: EngineId,
    val engineName: String,
    val index: MarketIndex,
    val side: PositionSide,
    val strike: Double,
    val quantity: Int,
    val lots: Int,
    val entryPrice: Double,
    val entrySpot: Double,
    val entryTimeMillis: Long,
    val setup: String,
    val status: TradeStatus = TradeStatus.OPEN,
    val exitPrice: Double? = null,
    val exitSpot: Double? = null,
    val exitTimeMillis: Long? = null,
    val pnl: Double? = null,
    val exitReason: String = "",
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val brokerEntryOrderId: String = "",
    val brokerExitOrderId: String = "",
) {
    init {
        TradingRecoveryRegistry.observeTrade(this)
    }
}

data class PaperPosition(
    val side: PositionSide,
    val strike: Double,
    val quantity: Int,
    val entryPrice: Double,
    val currentPrice: Double,
    val highestPrice: Double = entryPrice,
    val stopPrice: Double = entryPrice * 0.85,
    val targetPrice: Double = entryPrice * 1.30,
    val breakevenActive: Boolean = false,
    val trailingActive: Boolean = false,
    val openedAtMillis: Long = System.currentTimeMillis(),
    val strategy: String = "",
    val lotSize: Int = 0,
    val lots: Int = 1,
    val initialQuantity: Int = quantity,
    val indexInvalidation: Double = 0.0,
    val target1Hit: Boolean = false,
    val target1ExitQuantity: Int = 0,
    val realizedPartialPnl: Double = 0.0,
    val maxHoldMinutes: Int = 0,
    val instrumentKey: String = "",
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val brokerEntryOrderId: String = "",
) {
    init {
        TradingRecoveryRegistry.observePosition(this)
    }

    val pnl: Double get() = (currentPrice - entryPrice) * quantity + realizedPartialPnl
}

data class EnginePerformance(
    val trades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val realizedPnl: Double = 0.0,
    val grossProfit: Double = 0.0,
    val grossLoss: Double = 0.0,
    val peakEquity: Double = 0.0,
    val maxDrawdown: Double = 0.0,
) {
    val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades * 100.0
    val profitFactor: Double get() = when {
        grossLoss > 0.0 -> grossProfit / grossLoss
        grossProfit > 0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }
}

data class EngineState(
    val id: EngineId,
    val name: String,
    val signal: SignalSnapshot = SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf("Waiting for market data")),
    val position: PaperPosition? = null,
    val performance: EnginePerformance = EnginePerformance(),
    val message: String = "Ready",
) {
    val openPnl: Double get() = position?.pnl ?: 0.0
    val totalPnl: Double get() = performance.realizedPnl + openPnl
}

data class DashboardState(
    val index: MarketIndex = MarketIndex.NIFTY,
    val marketSelection: MarketSelection = MarketSelection.NIFTY,
    val tradingMode: TradingMode = TradingMode.AUTO,
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val liveArmMode: LiveArmMode = LiveArmMode.DISARMED,
    val emergencyKill: Boolean = false,
    val appMode: AppMode = AppMode.LIVE_MARKET,
    val connectionMode: ConnectionMode = ConnectionMode.DEMO,
    val isConnected: Boolean = false,
    val startingCapital: Double = 100_000.0,
    val spotPrice: Double = 0.0,
    val optionChain: List<OptionQuote> = emptyList(),
    val engine1: EngineState = EngineState(EngineId.ENGINE_1_TREND, "ENGINE 1 · TREND / BREAKOUT"),
    val engine2: EngineState = EngineState(EngineId.ENGINE_2_AVWAP_LIQUIDITY, "ENGINE 2 · AVWAP / LIQUIDITY + D30"),
    val engine3: EngineState = EngineState(EngineId.ENGINE_3_V76_SCALPER, "ENGINE 3 · V7.6 REVERSAL RUNNER"),
    val enabledEngines: Set<EngineId> = EngineId.entries.toSet(),
    val engineTimeframes: Map<EngineId, EngineTimeframeConfig> = mapOf(
        EngineId.ENGINE_1_TREND to EngineTimeframeConfig.E1_DEFAULT,
        EngineId.ENGINE_2_AVWAP_LIQUIDITY to EngineTimeframeConfig.E2_DEFAULT,
        EngineId.ENGINE_3_V76_SCALPER to EngineTimeframeConfig.E3_DEFAULT,
    ),
    val niftyLots: Int = 1,
    val sensexLots: Int = 1,
    val dailyTradeLimit: Int = 15,
    val riskConfig: TradingRiskConfig = TradingRiskConfig(),
    val message: String = "Ready · PAPER default · LIVE requires explicit arm",
    val lastTickMillis: Long = 0L,
    val ticksReceived: Long = 0L,
    val riskLocked: Boolean = false,
    val riskReason: String = "Risk gates clear",
    val tradeLog: List<TradeLogEntry> = emptyList(),
    val marketDepthMode: String = "WAITING",
    val marketDepthLevels: Int = 0,
) {
    val selectedLots: Int get() = if (index == MarketIndex.NIFTY) niftyLots else sensexLots
    val selectedIndexes: Set<MarketIndex> get() = marketSelection.indexes
    val combinedRealizedPnl: Double get() = engine1.performance.realizedPnl + engine2.performance.realizedPnl + engine3.performance.realizedPnl
    val combinedOpenPnl: Double get() = engine1.openPnl + engine2.openPnl + engine3.openPnl
    val combinedEquity: Double get() = startingCapital + combinedRealizedPnl + combinedOpenPnl
}
