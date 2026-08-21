package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bounded historical-only Candidate evolution.
 *
 * Historical search must not collapse into a model that wins only by REJECTing.
 * This helper treats decision coverage and cost-adjusted TAKE quality as first-class
 * search signals. Historical TAKE thresholds are allowed below 50% because the
 * +target/-stop payoff is asymmetric; strict positive-net/precision governance is
 * what determines whether such a threshold is usable. Locked holdout results are
 * never fed back here.
 */
object HistoricalAdaptiveCandidateSearch {
    const val MAX_ADAPTIVE_GENERATIONS = 6
    const val CANDIDATES_PER_GENERATION = 12

    enum class Guidance {
        INCREASE_TAKES,
        IMPROVE_TAKE_QUALITY,
        INCREASE_REJECTS,
        IMPROVE_REJECT_QUALITY,
        BALANCED,
    }

    data class Batch(
        val generation: Int,
        val parent: NumericalMetaBrain.HyperParameters,
        val guidance: Guidance,
        val candidates: List<NumericalMetaBrain.HyperParameters>,
    )

    private data class Mutation(
        val lrFactor: Double,
        val l2Factor: Double,
        val takeDelta: Double,
        val rejectDelta: Double,
    )

    fun guidance(evaluation: HistoricalCorpusTrainer.CandidateEvaluation): Guidance {
        val c = evaluation.candidate
        val requiredTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels)
        val requiredReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels)
        return when {
            c.takeSamples < requiredTake -> Guidance.INCREASE_TAKES
            c.takePrecision < HistoricalCandidateGovernance.MIN_TAKE_PRECISION || c.takeAverageNetReturn <= 0.0 -> Guidance.IMPROVE_TAKE_QUALITY
            c.rejectSamples < requiredReject -> Guidance.INCREASE_REJECTS
            c.rejectPrecision < HistoricalCandidateGovernance.MIN_REJECT_PRECISION -> Guidance.IMPROVE_REJECT_QUALITY
            else -> Guidance.BALANCED
        }
    }

    fun developmentSelectionScore(e: HistoricalCorpusTrainer.CandidateEvaluation): Double {
        val c = e.candidate
        val requiredTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val requiredReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val takeCoverage = (c.takeSamples.toDouble() / requiredTake).coerceIn(0.0, 1.0)
        val rejectCoverage = (c.rejectSamples.toDouble() / requiredReject).coerceIn(0.0, 1.0)
        val takePrecisionQuality = if (c.takeSamples > 0) (c.takePrecision - 0.50) else -0.50
        val rejectPrecisionQuality = if (c.rejectSamples > 0) (c.rejectPrecision - 0.50) else -0.25
        val takeReturnQuality = c.takeAverageNetReturn.coerceIn(-0.25, 0.25)
        val actionPenalty = 0.90 * (1.0 - takeCoverage) + 0.25 * (1.0 - rejectCoverage)
        val actionReward = 0.30 * takePrecisionQuality + 0.12 * rejectPrecisionQuality + 0.50 * takeReturnQuality
        val robustBonus = if (e.robust) 0.08 else 0.0
        return e.score + actionReward + robustBonus - actionPenalty
    }

    fun selectBest(evaluations: List<HistoricalCorpusTrainer.CandidateEvaluation>): HistoricalCorpusTrainer.CandidateEvaluation? {
        if (evaluations.isEmpty()) return null
        val actionReady = evaluations.filter { e ->
            val c = e.candidate
            val required = HistoricalCandidateGovernance.requiredActionSamples(c.labels)
            e.robust && c.takeSamples >= required && c.rejectSamples >= required &&
                c.takePrecision >= HistoricalCandidateGovernance.MIN_TAKE_PRECISION &&
                c.takeAverageNetReturn > 0.0 &&
                c.rejectPrecision >= HistoricalCandidateGovernance.MIN_REJECT_PRECISION
        }
        return (actionReady.ifEmpty { evaluations }).maxByOrNull(::developmentSelectionScore)
    }

    fun nextBatch(
        parent: NumericalMetaBrain.HyperParameters,
        generation: Int,
        seenSignatures: MutableSet<String>,
        guidance: Guidance,
    ): Batch {
        val safeGeneration = generation.coerceAtLeast(1)
        val safeParent = bounded(parent)
        val mutations = mutationsFor(guidance)
        val candidates = ArrayList<NumericalMetaBrain.HyperParameters>(CANDIDATES_PER_GENERATION)
        var cursor = (safeGeneration - 1) * CANDIDATES_PER_GENERATION
        var attempts = 0
        while (candidates.size < CANDIDATES_PER_GENERATION && attempts < 512) {
            val mutation = mutations[Math.floorMod(cursor, mutations.size)]
            val cycle = cursor / mutations.size
            val generationNudge = ((safeGeneration + cycle) % 5 - 2) * 0.0025
            val proposed = bounded(
                safeParent.copy(
                    learningRate = safeParent.learningRate * mutation.lrFactor * (1.0 + generationNudge),
                    l2 = safeParent.l2 * mutation.l2Factor,
                    takeThreshold = safeParent.takeThreshold + mutation.takeDelta + if (guidance == Guidance.INCREASE_TAKES) -abs(generationNudge) else generationNudge,
                    rejectThreshold = safeParent.rejectThreshold + mutation.rejectDelta,
                ),
            )
            val sig = signature(proposed)
            if (seenSignatures.add(sig)) candidates += proposed
            cursor++
            attempts++
        }

        var fallbackStep = 0
        while (candidates.size < CANDIDATES_PER_GENERATION && fallbackStep < 192) {
            val take = when (guidance) {
                Guidance.INCREASE_TAKES -> 0.25 + (fallbackStep % 14) * 0.025
                Guidance.IMPROVE_TAKE_QUALITY -> 0.35 + (fallbackStep % 14) * 0.025
                else -> HIST_MIN_TAKE + (fallbackStep % 18) * 0.025
            }
            val reject = when (guidance) {
                Guidance.INCREASE_REJECTS -> safeParent.rejectThreshold + 0.015 * (1 + fallbackStep % 6)
                Guidance.IMPROVE_REJECT_QUALITY -> safeParent.rejectThreshold - 0.015 * (1 + fallbackStep % 5)
                else -> safeParent.rejectThreshold + ((fallbackStep % 7) - 3) * 0.01
            }
            val proposed = bounded(
                safeParent.copy(
                    learningRate = safeParent.learningRate * (0.80 + (fallbackStep % 9) * 0.05),
                    l2 = safeParent.l2 * (0.65 + (fallbackStep % 7) * 0.15),
                    takeThreshold = take,
                    rejectThreshold = reject,
                ),
            )
            if (seenSignatures.add(signature(proposed))) candidates += proposed
            fallbackStep++
        }
        check(candidates.size == CANDIDATES_PER_GENERATION) { "Could not generate a unique historical Candidate batch" }
        return Batch(safeGeneration, safeParent, guidance, candidates)
    }

    fun bounded(h: NumericalMetaBrain.HyperParameters): NumericalMetaBrain.HyperParameters {
        val take = h.takeThreshold.coerceIn(HIST_MIN_TAKE, HIST_MAX_TAKE)
        val reject = h.rejectThreshold.coerceIn(HIST_MIN_REJECT, HIST_MAX_REJECT).coerceAtMost(take - HIST_MIN_GAP)
        return NumericalMetaBrain.HyperParameters(
            learningRate = h.learningRate.coerceIn(HIST_MIN_LR, HIST_MAX_LR),
            l2 = h.l2.coerceIn(HIST_MIN_L2, HIST_MAX_L2),
            takeThreshold = take,
            rejectThreshold = reject,
        ).sanitized()
    }

    fun signature(h: NumericalMetaBrain.HyperParameters): String {
        val b = bounded(h)
        return listOf(
            (b.learningRate * 1_000_000.0).roundToInt(),
            (b.l2 * 10_000_000.0).roundToInt(),
            (b.takeThreshold * 100_000.0).roundToInt(),
            (b.rejectThreshold * 100_000.0).roundToInt(),
        ).joinToString(":")
    }

    private fun mutationsFor(guidance: Guidance): List<Mutation> = when (guidance) {
        Guidance.INCREASE_TAKES -> listOf(
            Mutation(0.85, 1.00, -0.030, 0.000), Mutation(1.15, 1.00, -0.060, 0.000),
            Mutation(0.90, 0.70, -0.090, +0.005), Mutation(1.10, 1.30, -0.120, -0.005),
            Mutation(0.75, 1.20, -0.150, 0.000), Mutation(1.25, 0.80, -0.180, +0.010),
            Mutation(1.00, 0.55, -0.070, +0.015), Mutation(1.00, 1.60, -0.110, -0.010),
            Mutation(0.82, 0.85, -0.140, +0.005), Mutation(1.18, 1.15, -0.200, -0.005),
            Mutation(0.95, 1.40, -0.080, +0.010), Mutation(1.05, 0.65, -0.160, 0.000),
        )
        Guidance.IMPROVE_TAKE_QUALITY -> listOf(
            Mutation(0.85, 1.25, +0.010, -0.005), Mutation(1.15, 0.80, +0.020, 0.000),
            Mutation(0.90, 1.50, +0.030, -0.010), Mutation(1.10, 0.65, +0.040, 0.000),
            Mutation(0.75, 1.00, +0.050, -0.005), Mutation(1.25, 1.00, +0.060, -0.010),
            Mutation(1.00, 0.55, +0.015, +0.005), Mutation(1.00, 1.70, +0.025, -0.015),
            Mutation(0.82, 0.90, +0.035, 0.000), Mutation(1.18, 1.20, +0.045, -0.005),
            Mutation(0.95, 1.35, +0.055, -0.010), Mutation(1.05, 0.75, +0.005, +0.005),
        )
        Guidance.INCREASE_REJECTS -> listOf(
            Mutation(0.90, 1.00, 0.000, +0.015), Mutation(1.10, 1.00, 0.000, +0.025),
            Mutation(0.80, 0.70, -0.010, +0.035), Mutation(1.20, 1.30, +0.010, +0.045),
            Mutation(1.00, 0.55, -0.015, +0.055), Mutation(1.00, 1.60, +0.015, +0.065),
            Mutation(0.85, 1.25, 0.000, +0.075), Mutation(1.15, 0.80, 0.000, +0.085),
            Mutation(0.95, 1.40, -0.020, +0.030), Mutation(1.05, 0.65, +0.020, +0.040),
            Mutation(0.75, 1.10, -0.010, +0.050), Mutation(1.25, 0.90, +0.010, +0.060),
        )
        Guidance.IMPROVE_REJECT_QUALITY -> listOf(
            Mutation(0.90, 1.20, 0.000, -0.010), Mutation(1.10, 0.80, 0.000, -0.020),
            Mutation(0.80, 1.50, +0.010, -0.030), Mutation(1.20, 0.65, -0.010, -0.040),
            Mutation(1.00, 0.55, +0.015, -0.050), Mutation(1.00, 1.70, -0.015, -0.060),
            Mutation(0.85, 1.25, 0.000, -0.025), Mutation(1.15, 0.80, 0.000, -0.035),
            Mutation(0.95, 1.40, +0.020, -0.045), Mutation(1.05, 0.65, -0.020, -0.055),
            Mutation(0.75, 1.10, +0.010, -0.015), Mutation(1.25, 0.90, -0.010, -0.065),
        )
        Guidance.BALANCED -> listOf(
            Mutation(0.82, 1.20, +0.010, -0.010), Mutation(1.18, 0.85, -0.010, +0.010),
            Mutation(0.90, 0.70, +0.020, -0.005), Mutation(1.10, 1.35, -0.020, +0.005),
            Mutation(1.30, 1.00, 0.000, -0.020), Mutation(0.72, 1.00, +0.025, -0.015),
            Mutation(1.00, 0.55, +0.015, +0.005), Mutation(1.00, 1.65, -0.015, -0.005),
            Mutation(0.88, 1.15, -0.030, +0.015), Mutation(1.12, 0.90, +0.030, -0.015),
            Mutation(0.78, 0.80, -0.045, +0.010), Mutation(1.22, 1.25, +0.045, -0.010),
        )
    }

    private const val HIST_MIN_LR = 0.003
    private const val HIST_MAX_LR = 0.060
    private const val HIST_MIN_L2 = 0.00003
    private const val HIST_MAX_L2 = 0.00800
    private const val HIST_MIN_TAKE = 0.25
    private const val HIST_MAX_TAKE = 0.90
    private const val HIST_MIN_REJECT = 0.05
    private const val HIST_MAX_REJECT = 0.60
    private const val HIST_MIN_GAP = 0.05
}
