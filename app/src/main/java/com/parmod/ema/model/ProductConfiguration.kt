package com.parmod.ema.model

/** User-facing market scope. BOTH is a real dual-market selection, not a UI alias. */
enum class MarketSelection {
    NIFTY,
    SENSEX,
    BOTH;

    val indexes: Set<MarketIndex>
        get() = when (this) {
            NIFTY -> setOf(MarketIndex.NIFTY)
            SENSEX -> setOf(MarketIndex.SENSEX)
            BOTH -> setOf(MarketIndex.NIFTY, MarketIndex.SENSEX)
        }

    fun includes(index: MarketIndex): Boolean = index in indexes
}

/** Supported completed-candle timeframes exposed in the dashboard. */
enum class SignalTimeframe(val minutes: Int, val label: String) {
    M1(1, "1m"),
    M3(3, "3m"),
    M5(5, "5m"),
    M15(15, "15m"),
}

/**
 * Three-stage timing used by each engine.
 * Trigger must be the fastest leg and bias the slowest leg so a UI change cannot
 * accidentally invert the causal hierarchy.
 */
data class EngineTimeframeConfig(
    val trigger: SignalTimeframe,
    val setup: SignalTimeframe,
    val bias: SignalTimeframe,
) {
    init {
        require(trigger.minutes <= setup.minutes) { "Trigger timeframe must be <= setup timeframe" }
        require(setup.minutes <= bias.minutes) { "Setup timeframe must be <= bias timeframe" }
    }

    companion object {
        val E1_DEFAULT = EngineTimeframeConfig(SignalTimeframe.M1, SignalTimeframe.M3, SignalTimeframe.M5)
        val E2_DEFAULT = EngineTimeframeConfig(SignalTimeframe.M1, SignalTimeframe.M3, SignalTimeframe.M5)
        val E3_DEFAULT = EngineTimeframeConfig(SignalTimeframe.M1, SignalTimeframe.M3, SignalTimeframe.M15)
    }
}

enum class LiveArmMode {
    DISARMED,
    MANUAL_ONLY,
    AUTO_ARMED,
}

/** Runtime risk controls that are adjustable from the app without changing engine code. */
data class TradingRiskConfig(
    val dailyLossLimitInr: Double = 3_500.0,
    val maxTradesPerIndex: Int = 15,
    val maxLotsPerOrder: Int = 20,
    val minimumAutoLiveConfidence: Int = 75,
    val maximumSpreadPercent: Double = 4.5,
    val maximumTickAgeMillis: Long = 2_000L,
) {
    init {
        require(dailyLossLimitInr > 0.0)
        require(maxTradesPerIndex in 1..100)
        require(maxLotsPerOrder in 1..100)
        require(minimumAutoLiveConfidence in 1..100)
        require(maximumSpreadPercent > 0.0)
        require(maximumTickAgeMillis in 250L..60_000L)
    }
}

data class LiveGateInput(
    val executionMode: ExecutionMode,
    val armMode: LiveArmMode,
    val automatic: Boolean,
    val connected: Boolean,
    val upstoxTokenPresent: Boolean,
    val instrumentKeyPresent: Boolean,
    val quantity: Int,
    val riskLocked: Boolean,
    val emergencyKill: Boolean,
    val marketOpen: Boolean,
    val entriesAllowed: Boolean,
    val tickAgeMillis: Long,
    val confidence: Int,
    val spreadPercent: Double,
    val tradesToday: Int,
    val risk: TradingRiskConfig,
)

data class LiveGateDecision(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Final broker-order gate. Every live entry must pass this immediately before the
 * network request. Manual and automatic live trading share the same hard risk gate;
 * automatic mode additionally requires AUTO_ARMED and the configured confidence bar.
 */
object LiveExecutionGuard {
    fun evaluate(input: LiveGateInput): LiveGateDecision {
        if (input.executionMode != ExecutionMode.LIVE) return deny("Execution mode is PAPER")
        if (!input.connected) return deny("Live market feed is not connected")
        if (!input.upstoxTokenPresent) return deny("Upstox access token is missing")
        if (!input.instrumentKeyPresent) return deny("Selected option instrument key is missing")
        if (input.quantity <= 0) return deny("Order quantity is invalid")
        if (input.riskLocked) return deny("Daily risk lock is active")
        if (input.emergencyKill) return deny("Emergency kill switch is active")
        if (!input.marketOpen) return deny("Live intraday orders are blocked outside market hours")
        if (!input.entriesAllowed) return deny("Entry window is closed")
        if (input.tickAgeMillis < 0L || input.tickAgeMillis > input.risk.maximumTickAgeMillis) {
            return deny("Market data is stale")
        }
        if (input.tradesToday >= input.risk.maxTradesPerIndex) return deny("Daily trade limit reached")
        if (input.spreadPercent.isFinite() && input.spreadPercent > input.risk.maximumSpreadPercent) {
            return deny("Option spread exceeds live limit")
        }

        if (input.automatic) {
            if (input.armMode != LiveArmMode.AUTO_ARMED) return deny("Automatic live trading is not armed")
            if (input.confidence < input.risk.minimumAutoLiveConfidence) return deny("Signal confidence below live-auto minimum")
        } else if (input.armMode == LiveArmMode.DISARMED) {
            return deny("Manual live trading is not armed")
        }

        return LiveGateDecision(true, "LIVE gate clear")
    }

    private fun deny(reason: String) = LiveGateDecision(false, reason)
}
