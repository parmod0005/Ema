package com.parmod.ema.model

enum class TradingMode { MANUAL, AUTO }
enum class ExecutionMode { PAPER, LIVE }
enum class AppMode { LIVE_MARKET, BACKTEST }
enum class ConnectionMode { DEMO, UPSTOX }
enum class MarketIndex { NIFTY, SENSEX }
enum class SignalAction { BUY_CE, BUY_PE, WAIT }
enum class TrendDirection { BULLISH, BEARISH, NEUTRAL }
enum class PositionSide { CE, PE }
enum class EngineId { ENGINE_1_TREND, ENGINE_2_AVWAP_LIQUIDITY }

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
) {
    val pnl: Double get() = (currentPrice - entryPrice) * quantity
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
    val tradingMode: TradingMode = TradingMode.AUTO,
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val appMode: AppMode = AppMode.LIVE_MARKET,
    val connectionMode: ConnectionMode = ConnectionMode.DEMO,
    val isConnected: Boolean = false,
    val startingCapital: Double = 100_000.0,
    val spotPrice: Double = 0.0,
    val optionChain: List<OptionQuote> = emptyList(),
    val engine1: EngineState = EngineState(EngineId.ENGINE_1_TREND, "ENGINE 1 · TREND / BREAKOUT"),
    val engine2: EngineState = EngineState(EngineId.ENGINE_2_AVWAP_LIQUIDITY, "ENGINE 2 · AVWAP / LIQUIDITY"),
    val message: String = "Ready · live paper trading only",
    val lastTickMillis: Long = 0L,
    val ticksReceived: Long = 0L,
    val riskLocked: Boolean = false,
    val riskReason: String = "Risk gates clear",
) {
    val combinedRealizedPnl: Double get() = engine1.performance.realizedPnl + engine2.performance.realizedPnl
    val combinedOpenPnl: Double get() = engine1.openPnl + engine2.openPnl
    val combinedEquity: Double get() = startingCapital + combinedRealizedPnl + combinedOpenPnl
}
