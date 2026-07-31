package com.parmod.ema.model

enum class TradingMode { MANUAL, AUTO }
enum class ExecutionMode { PAPER, LIVE }
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
) {
    val pnl: Double get() = (currentPrice - entryPrice) * quantity
}

data class DashboardState(
    val index: MarketIndex = MarketIndex.NIFTY,
    val tradingMode: TradingMode = TradingMode.MANUAL,
    val executionMode: ExecutionMode = ExecutionMode.PAPER,
    val connectionMode: ConnectionMode = ConnectionMode.DEMO,
    val isConnected: Boolean = false,
    val liveExecutionUnlocked: Boolean = false,
    val spotPrice: Double = 0.0,
    val pnl: Double = 0.0,
    val signal: SignalSnapshot = SignalSnapshot(
        action = SignalAction.WAIT,
        confidence = 0,
        trend = TrendDirection.NEUTRAL,
        entry = null,
        stopLoss = null,
        target = null,
        reasons = listOf("Waiting for market data"),
    ),
    val optionChain: List<OptionQuote> = emptyList(),
    val position: PaperPosition? = null,
    val message: String = "Demo ready",
)
