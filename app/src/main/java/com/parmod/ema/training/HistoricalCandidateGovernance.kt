package com.parmod.ema.training

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One strict governance policy shared by all historical VARDHANI AI trainers.
 * A historical Candidate must demonstrate that it can TAKE profitable setups,
 * reject poor setups, improve on frozen Production, and not depend almost entirely
 * on a single historical engine proxy. Historical PASS never promotes Production;
 * it only makes the champion eligible to be installed as a live-shadow Candidate.
 */
object HistoricalCandidateGovernance {
    enum class Status { CLOSED, INSUFFICIENT_DATA, FAIL, PASS }

    data class Decision(
        val status: Status,
        val reasons: List<String>,
        val requiredTakeSamples: Long = 0,
        val requiredRejectSamples: Long = 0,
        val requiredEngineSamples: Int = 0,
        val dominantEngineShare: Double = 0.0,
    ) {
        val passed: Boolean get() = status == Status.PASS
        val label: String get() = when (status) {
            Status.CLOSED -> "CLOSED"
            Status.INSUFFICIENT_DATA -> "INSUFFICIENT DATA"
            Status.FAIL -> "FAIL"
            Status.PASS -> "PASS"
        }
    }

    const val MIN_HOLDOUT_LABELS = 50L
    const val MIN_TAKE_PRECISION = 0.55
    const val MIN_REJECT_PRECISION = 0.55
    const val MIN_ACCURACY_GAIN = 0.005
    const val MIN_BRIER_GAIN = 0.002
    const val MAX_DOMINANT_ENGINE_SHARE = 0.85
    const val MIN_DISTINCT_ENGINES = 2

    /** Strict gate for the one-time locked holdout. */
    fun evaluate(
        candidate: HistoricalCorpusTrainer.Metrics?,
        production: HistoricalCorpusTrainer.Metrics?,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
        holdoutOpened: Boolean,
    ): Decision = evaluateEvidence(
        candidate = candidate,
        production = production,
        coverage = coverage,
        corpusSamples = corpusSamples,
        evidenceAvailable = holdoutOpened,
        minimumLabels = MIN_HOLDOUT_LABELS,
        evidenceName = "locked-holdout",
    )

    /**
     * Pre-holdout qualification used by adaptive historical evolution.
     * Only walk-forward/development metrics reach this gate; locked test evidence is
     * deliberately unavailable here, preventing test-set tuning across generations.
     */
    fun evaluateDevelopment(
        candidate: HistoricalCorpusTrainer.Metrics?,
        production: HistoricalCorpusTrainer.Metrics?,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
    ): Decision = evaluateEvidence(
        candidate = candidate,
        production = production,
        coverage = coverage,
        corpusSamples = corpusSamples,
        evidenceAvailable = candidate != null && production != null,
        minimumLabels = MIN_HOLDOUT_LABELS,
        evidenceName = "walk-forward validation",
    )

    private fun evaluateEvidence(
        candidate: HistoricalCorpusTrainer.Metrics?,
        production: HistoricalCorpusTrainer.Metrics?,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
        evidenceAvailable: Boolean,
        minimumLabels: Long,
        evidenceName: String,
    ): Decision {
        if (!evidenceAvailable || candidate == null || production == null) {
            return Decision(Status.CLOSED, listOf("$evidenceName evidence is not available"))
        }

        val requiredTake = requiredActionSamples(candidate.labels)
        val requiredReject = requiredActionSamples(candidate.labels)
        val requiredEngine = requiredEngineSamples(corpusSamples)
        val engineCounts = listOf(
            "E1" to coverage.engine1Samples,
            "E2" to coverage.engine2Samples,
            "E3" to coverage.engine3Samples,
        )
        val representedEngines = engineCounts.count { it.second >= requiredEngine }
        val dominant = engineCounts.maxByOrNull { it.second }
        val dominantShare = if (corpusSamples <= 0) 0.0 else (dominant?.second ?: 0).toDouble() / corpusSamples

        val insufficient = mutableListOf<String>()
        if (candidate.labels < minimumLabels) {
            insufficient += "Need at least $minimumLabels $evidenceName labels; found ${candidate.labels}"
        }
        if (candidate.takeSamples < requiredTake) {
            insufficient += "Need at least $requiredTake TAKE decisions; found ${candidate.takeSamples}"
        }
        if (candidate.rejectSamples < requiredReject) {
            insufficient += "Need at least $requiredReject REJECT decisions; found ${candidate.rejectSamples}"
        }
        if (representedEngines < MIN_DISTINCT_ENGINES) {
            insufficient += "Need at least $MIN_DISTINCT_ENGINES engine proxies with ≥$requiredEngine samples; found $representedEngines"
        }
        if (insufficient.isNotEmpty()) {
            return Decision(
                status = Status.INSUFFICIENT_DATA,
                reasons = insufficient,
                requiredTakeSamples = requiredTake,
                requiredRejectSamples = requiredReject,
                requiredEngineSamples = requiredEngine,
                dominantEngineShare = dominantShare,
            )
        }

        val failures = mutableListOf<String>()
        val accuracyGain = candidate.accuracy - production.accuracy
        val brierGain = production.brier - candidate.brier
        if (accuracyGain < MIN_ACCURACY_GAIN && brierGain < MIN_BRIER_GAIN) {
            failures += "Candidate must improve Production by ≥${pct(MIN_ACCURACY_GAIN)} accuracy or ≥${"%.4f".format(MIN_BRIER_GAIN)} Brier"
        }
        if (candidate.takePrecision < MIN_TAKE_PRECISION) {
            failures += "TAKE precision ${pct(candidate.takePrecision)} is below ${pct(MIN_TAKE_PRECISION)}"
        }
        if (candidate.takeAverageNetReturn <= 0.0) {
            failures += "TAKE average net return ${pctSigned(candidate.takeAverageNetReturn)} must be positive after costs"
        }
        if (candidate.rejectPrecision < MIN_REJECT_PRECISION) {
            failures += "REJECT precision ${pct(candidate.rejectPrecision)} is below ${pct(MIN_REJECT_PRECISION)}"
        }
        if (dominantShare > MAX_DOMINANT_ENGINE_SHARE) {
            failures += "${dominant?.first ?: "One engine"} dominates ${pct(dominantShare)} of corpus; maximum allowed is ${pct(MAX_DOMINANT_ENGINE_SHARE)}"
        }

        return Decision(
            status = if (failures.isEmpty()) Status.PASS else Status.FAIL,
            reasons = if (failures.isEmpty()) listOf("All historical governance checks passed") else failures,
            requiredTakeSamples = requiredTake,
            requiredRejectSamples = requiredReject,
            requiredEngineSamples = requiredEngine,
            dominantEngineShare = dominantShare,
        )
    }

    fun requiredActionSamples(labels: Long): Long =
        max(10L, min(100L, ceil(labels.coerceAtLeast(0L) * 0.05).toLong()))

    fun requiredEngineSamples(corpusSamples: Int): Int =
        max(10, ceil(corpusSamples.coerceAtLeast(0) * 0.05).toInt())

    private fun pct(value: Double): String = "%.1f%%".format(value * 100.0)
    private fun pctSigned(value: Double): String = "%+.2f%%".format(value * 100.0)
}
