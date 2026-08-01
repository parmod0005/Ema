package com.parmod.ema.model

enum class TradingMode { MANUAL, AUTO }
enum class ExecutionMode { PAPER, LIVE }
enum class AppMode { LIVE_MARKET, BACKTEST }
enum class ConnectionMode { DEMO, UPSTOX }
enum class MarketIndex { NIFTY, SENSEX }
enum class SignalAction { BUY_CE, BUY_PE, WAIT }
enum class TrendDirection { BULLISH, BEARISH, NEUTRAL }
enum class PositionSide { CE, PE }

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
)

data class PaperPosition(
    val side: PositionSide,
    val strike: Double,
    val quantity: Int,
    val entryPrice: Double,
    val currentPrice: Double,
) { val pnl: Double get() = (currentPrice - entryPrice) * quantity }

data class DashboardState(
    val index: MarketIndex = MarketIndex.NIFTY,
    val tradingMode: TradingMode = TradingMode.MANUAL,
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val appMode: AppMode = AppMode.LIVE_MARKET,
    val connectionMode: ConnectionMode = ConnectionMode.DEMO,
    val isConnected: Boolean = false,
    val liveExecutionUnlocked: Boolean = false,
    val liveTradingEnabled: Boolean = false,
    val startingCapital: Double = 100_000.0,
    val realizedPnl: Double = 0.0,
    val spotPrice: Double = 0.0,
    val pnl: Double = 0.0,
    val signal: SignalSnapshot = SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf("Waiting for market data")),
    val optionChain: List<OptionQuote> = emptyList(),
    val position: PaperPosition? = null,
    val message: String = "Ready",
    val lastTickMillis: Long = 0L,
    val ticksReceived: Long = 0L,
) {
    val equity: Double get() = startingCapital + realizedPnl + pnl
}
