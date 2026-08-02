package com.parmod.ema.ai

import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalAction

/** Selects which decision provider controls the visible/final signal. */
enum class SignalEngineMode { NATIVE, AI_BRAIN, HYBRID }

/** Selects how the AI brain is reached. */
enum class AiConnectionMode { BRIDGE_SERVER, DIRECT_OPENAI }

/** AI can observe without trading, drive paper decisions, or be eligible for live validation. */
enum class AiRunMode { SHADOW, PAPER, LIVE_CANDIDATE }

enum class MarketRegime {
    TRENDING_BULLISH,
    TRENDING_BEARISH,
    RANGE,
    BREAKOUT,
    REVERSAL,
    HIGH_VOLATILITY,
    UNKNOWN,
}

data class CompactBar(
    val epochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class NewsContext(
    val headline: String,
    val source: String,
    val publishedAtMillis: Long,
    val sentimentScore: Double,
    val relevanceScore: Double,
)

data class RiskContext(
    val capital: Double,
    val realizedPnl: Double,
    val openSide: PositionSide? = null,
    val openEntryPrice: Double? = null,
    val dailyTrades: Int = 0,
    val dailyLossLocked: Boolean = false,
)

data class AiMarketSnapshot(
    val schemaVersion: Int = 1,
    val snapshotId: String,
    val generatedAtMillis: Long,
    val index: MarketIndex,
    val expiry: String,
    val spot: Double,
    val bars1m: List<CompactBar>,
    val bars5m: List<CompactBar>,
    val bars15m: List<CompactBar>,
    val optionChain: List<OptionQuote>,
    val nativeAction: SignalAction,
    val nativeConfidence: Int,
    val risk: RiskContext,
    val news: List<NewsContext> = emptyList(),
)

data class ConditionalTrigger(
    val spotAbove: Double? = null,
    val spotBelow: Double? = null,
    val minimumVolumeRatio: Double? = null,
    val maximumSpreadPct: Double? = null,
)

data class AiTradeDecision(
    val schemaVersion: Int = 1,
    val decisionId: String,
    val snapshotId: String,
    val decidedAtMillis: Long,
    val validForMillis: Long,
    val action: SignalAction,
    val confidence: Int,
    val regime: MarketRegime,
    val instrumentKey: String? = null,
    val strike: Double? = null,
    val optionType: String? = null,
    val entryMin: Double? = null,
    val entryMax: Double? = null,
    val stopLoss: Double? = null,
    val target: Double? = null,
    val trigger: ConditionalTrigger? = null,
    val maximumSpotMovePct: Double = 0.20,
    val reasons: List<String> = emptyList(),
    val riskFlags: List<String> = emptyList(),
    val modelVersion: String,
    val promptVersion: String,
) {
    init {
        require(confidence in 0..100) { "Confidence must be 0..100" }
        require(validForMillis in 1_000..300_000) { "Signal validity must be 1s..5m" }
    }

    fun isExpired(nowMillis: Long): Boolean = nowMillis > decidedAtMillis + validForMillis
}

data class AiBridgeHealth(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val lastLatencyMillis: Long? = null,
    val lastSuccessMillis: Long? = null,
    val consecutiveFailures: Int = 0,
    val message: String = "AI not configured",
)
