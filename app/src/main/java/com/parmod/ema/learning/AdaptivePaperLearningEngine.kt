package com.parmod.ema.learning

import com.parmod.ema.ai.MarketRegime
import com.parmod.ema.model.PositionSide
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Learns only from completed paper trades.
 *
 * This is deliberately conservative: it adjusts eligibility thresholds rather
 * than rewriting executable trading code. Changes are bounded and require a
 * minimum sample, positive expectancy, acceptable drawdown and a lower-bound
 * confidence estimate before a candidate policy can be promoted.
 */
class AdaptivePaperLearningEngine(
    private val minimumPromotionTrades: Int = 40,
    private val minimumWinRate: Double = 0.52,
    private val minimumProfitFactor: Double = 1.20,
    private val maximumDrawdownPct: Double = 8.0,
) {
    data class PaperTradeOutcome(
        val openedAtMillis: Long,
        val closedAtMillis: Long,
        val provider: String,
        val modelVersion: String,
        val promptVersion: String,
        val regime: MarketRegime,
        val side: PositionSide,
        val confidence: Int,
        val entryPrice: Double,
        val exitPrice: Double,
        val quantity: Int,
        val pnl: Double,
        val maximumAdverseExcursionPct: Double,
        val maximumFavourableExcursionPct: Double,
        val exitReason: String,
    ) {
        init {
            require(closedAtMillis >= openedAtMillis)
            require(confidence in 0..100)
            require(quantity > 0)
            require(entryPrice > 0.0)
            require(exitPrice >= 0.0)
        }
    }

    data class Policy(
        val minimumAiConfidence: Int = 80,
        val maximumTradesPerDay: Int = 4,
        val riskFractionPct: Double = 0.50,
        val version: Int = 1,
    ) {
        init {
            require(minimumAiConfidence in 70..95)
            require(maximumTradesPerDay in 1..10)
            require(riskFractionPct in 0.10..1.00)
        }
    }

    data class Evaluation(
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val profitFactor: Double,
        val expectancy: Double,
        val maximumDrawdownPct: Double,
        val wilsonLowerBound: Double,
        val eligibleForPromotion: Boolean,
        val reasons: List<String>,
        val candidatePolicy: Policy,
    )

    fun evaluate(outcomes: List<PaperTradeOutcome>, current: Policy): Evaluation {
        val ordered = outcomes.sortedBy { it.closedAtMillis }
        val wins = ordered.count { it.pnl > 0.0 }
        val losses = ordered.count { it.pnl < 0.0 }
        val grossProfit = ordered.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = -ordered.filter { it.pnl < 0.0 }.sumOf { it.pnl }
        val profitFactor = when {
            grossLoss > 0.0 -> grossProfit / grossLoss
            grossProfit > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
        val expectancy = ordered.map { it.pnl }.average().takeIf { it.isFinite() } ?: 0.0
        val winRate = if (ordered.isEmpty()) 0.0 else wins.toDouble() / ordered.size
        val drawdown = maximumDrawdownPct(ordered)
        val lowerBound = wilsonLowerBound(wins, ordered.size)

        val reasons = buildList {
            if (ordered.size < minimumPromotionTrades) add("Need at least $minimumPromotionTrades completed paper trades")
            if (winRate < minimumWinRate) add("Win rate below ${"%.0f".format(minimumWinRate * 100)}%")
            if (profitFactor < minimumProfitFactor) add("Profit factor below ${"%.2f".format(minimumProfitFactor)}")
            if (expectancy <= 0.0) add("Expectancy is not positive")
            if (drawdown > maximumDrawdownPct) add("Drawdown exceeds ${"%.1f".format(maximumDrawdownPct)}%")
            if (lowerBound < 0.45) add("Statistical lower-bound confidence is too weak")
        }

        val eligible = reasons.isEmpty()
        val candidate = if (eligible) proposeBoundedPolicy(ordered, current) else current
        return Evaluation(
            trades = ordered.size,
            wins = wins,
            losses = losses,
            winRate = winRate,
            profitFactor = profitFactor,
            expectancy = expectancy,
            maximumDrawdownPct = drawdown,
            wilsonLowerBound = lowerBound,
            eligibleForPromotion = eligible,
            reasons = if (eligible) listOf("Candidate passed guarded paper-performance gates") else reasons,
            candidatePolicy = candidate,
        )
    }

    private fun proposeBoundedPolicy(outcomes: List<PaperTradeOutcome>, current: Policy): Policy {
        val highConfidence = outcomes.filter { it.confidence >= current.minimumAiConfidence }
        val highConfidenceWinRate = if (highConfidence.isEmpty()) 0.0 else highConfidence.count { it.pnl > 0.0 }.toDouble() / highConfidence.size
        val nextConfidence = when {
            highConfidenceWinRate >= 0.62 -> current.minimumAiConfidence - 1
            highConfidenceWinRate < 0.54 -> current.minimumAiConfidence + 2
            else -> current.minimumAiConfidence
        }.coerceIn(75, 90)

        val severeLosses = outcomes.count { it.maximumAdverseExcursionPct >= 20.0 || it.pnl < 0.0 && it.maximumAdverseExcursionPct >= 12.0 }
        val severeLossRate = severeLosses.toDouble() / max(1, outcomes.size)
        val nextRisk = when {
            severeLossRate > 0.15 -> current.riskFractionPct - 0.10
            severeLossRate < 0.05 -> current.riskFractionPct + 0.05
            else -> current.riskFractionPct
        }.coerceIn(0.25, 0.75)

        return current.copy(
            minimumAiConfidence = nextConfidence,
            riskFractionPct = nextRisk,
            version = current.version + 1,
        )
    }

    private fun maximumDrawdownPct(outcomes: List<PaperTradeOutcome>): Double {
        var equity = 100.0
        var peak = equity
        var maximum = 0.0
        outcomes.forEach { trade ->
            val returnPct = if (trade.entryPrice > 0.0 && trade.quantity > 0) {
                trade.pnl / (trade.entryPrice * trade.quantity) * 100.0
            } else 0.0
            equity += returnPct
            peak = max(peak, equity)
            if (peak > 0.0) maximum = max(maximum, (peak - equity) / peak * 100.0)
        }
        return maximum
    }

    private fun wilsonLowerBound(wins: Int, total: Int, z: Double = 1.96): Double {
        if (total == 0) return 0.0
        val n = total.toDouble()
        val p = wins / n
        val denominator = 1.0 + z * z / n
        val centre = p + z * z / (2.0 * n)
        val margin = z * sqrt((p * (1.0 - p) + z * z / (4.0 * n)) / n)
        return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
    }
}
