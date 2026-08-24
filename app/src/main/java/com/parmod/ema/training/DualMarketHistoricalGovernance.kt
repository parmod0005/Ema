package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Additional governance for one shared historical Candidate trained on both markets. */
object DualMarketHistoricalGovernance {
    data class Decision(
        val status: HistoricalCandidateGovernance.Status,
        val reasons: List<String>,
    ) {
        val passed: Boolean get() = status == HistoricalCandidateGovernance.Status.PASS
        val label: String get() = when (status) {
            HistoricalCandidateGovernance.Status.CLOSED -> "CLOSED"
            HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "INSUFFICIENT DATA"
            HistoricalCandidateGovernance.Status.FAIL -> "FAIL"
            HistoricalCandidateGovernance.Status.PASS -> "PASS"
        }
    }

    fun evaluateDevelopment(
        candidate: HistoricalCorpusTrainer.Metrics,
        production: HistoricalCorpusTrainer.Metrics,
        candidateByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        productionByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
    ): Decision = evaluate(
        base = HistoricalCandidateGovernance.evaluateDevelopment(candidate, production, coverage, corpusSamples),
        candidateByMarket = candidateByMarket,
        productionByMarket = productionByMarket,
        evidenceName = "walk-forward",
    )

    fun evaluateHoldout(
        candidate: HistoricalCorpusTrainer.Metrics,
        production: HistoricalCorpusTrainer.Metrics,
        candidateByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        productionByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
    ): Decision = evaluate(
        base = HistoricalCandidateGovernance.evaluate(candidate, production, coverage, corpusSamples, true),
        candidateByMarket = candidateByMarket,
        productionByMarket = productionByMarket,
        evidenceName = "locked holdout",
    )

    private fun evaluate(
        base: HistoricalCandidateGovernance.Decision,
        candidateByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        productionByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        evidenceName: String,
    ): Decision {
        if (base.status == HistoricalCandidateGovernance.Status.CLOSED) return Decision(base.status, base.reasons)
        val insufficient = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for (market in MarketIndex.entries) {
            val c = candidateByMarket[market] ?: HistoricalCorpusTrainer.Metrics()
            val p = productionByMarket[market] ?: HistoricalCorpusTrainer.Metrics()
            if (c.labels < MIN_MARKET_LABELS) {
                insufficient += "${market.name}: need ≥$MIN_MARKET_LABELS $evidenceName labels; found ${c.labels}"
                continue
            }
            val required = requiredActions(c.labels)
            if (c.takeSamples < required) insufficient += "${market.name}: need ≥$required TAKE decisions; found ${c.takeSamples}"
            if (c.rejectSamples < required) insufficient += "${market.name}: need ≥$required REJECT decisions; found ${c.rejectSamples}"
            if (c.takeSamples >= required && c.takePrecision < MIN_MARKET_PRECISION) failures += "${market.name}: TAKE precision ${pct(c.takePrecision)} < ${pct(MIN_MARKET_PRECISION)}"
            if (c.takeSamples >= required && c.takeAverageNetReturn <= 0.0) failures += "${market.name}: TAKE average net ${pctSigned(c.takeAverageNetReturn)} must be positive after costs"
            if (c.rejectSamples >= required && c.rejectPrecision < MIN_MARKET_PRECISION) failures += "${market.name}: REJECT precision ${pct(c.rejectPrecision)} < ${pct(MIN_MARKET_PRECISION)}"
            val accGain = c.accuracy - p.accuracy
            val brierGain = p.brier - c.brier
            if (accGain < -MAX_MARKET_ACCURACY_REGRESSION && brierGain < -MAX_MARKET_BRIER_REGRESSION) {
                failures += "${market.name}: Candidate materially regresses frozen Production"
            }
        }
        if (insufficient.isNotEmpty()) return Decision(HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA, (base.reasons + insufficient).distinct())
        if (base.status == HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA) return Decision(base.status, base.reasons)
        if (base.status == HistoricalCandidateGovernance.Status.FAIL || failures.isNotEmpty()) {
            return Decision(HistoricalCandidateGovernance.Status.FAIL, (base.reasons + failures).distinct())
        }
        return Decision(HistoricalCandidateGovernance.Status.PASS, listOf("Overall + NIFTY + SENSEX historical governance passed"))
    }

    fun requiredActions(labels: Long): Long = max(10L, min(100L, ceil(labels * 0.05).toLong()))

    private fun pct(value: Double) = "%.1f%%".format(value * 100.0)
    private fun pctSigned(value: Double) = "%+.2f%%".format(value * 100.0)

    const val MIN_MARKET_LABELS = 50L
    const val MIN_MARKET_PRECISION = 0.55
    const val MAX_MARKET_ACCURACY_REGRESSION = 0.01
    const val MAX_MARKET_BRIER_REGRESSION = 0.003
}
