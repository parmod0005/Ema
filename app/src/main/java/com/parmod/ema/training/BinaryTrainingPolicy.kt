package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared binary-training policy for historical/live research.
 *
 * It addresses two common failure modes in trading classifiers:
 * 1) majority-class collapse (high accuracy by rejecting everything), and
 * 2) arbitrary probability cutoffs that are unrelated to cost-adjusted trade value.
 *
 * Class weights are derived from the fitting partition only. Decision thresholds are
 * calibrated on a later development/calibration partition only. Forward validation
 * and locked holdout data are never used to fit weights or thresholds.
 */
object BinaryTrainingPolicy {
    data class Balance(
        val positives: Long,
        val negatives: Long,
        val positiveWeight: Double,
        val negativeWeight: Double,
        val positiveRate: Double,
    )

    data class CalibrationPoint(
        val probability: Double,
        val success: Boolean,
        val netReturn: Double,
    )

    data class CalibrationResult(
        val takeThreshold: Double,
        val rejectThreshold: Double,
        val takeSamples: Int,
        val takePrecision: Double,
        val takeAverageNetReturn: Double,
        val rejectSamples: Int,
        val rejectPrecision: Double,
        val score: Double,
        val viable: Boolean,
    )

    fun balance(positives: Long, negatives: Long): Balance {
        val p = positives.coerceAtLeast(0L)
        val n = negatives.coerceAtLeast(0L)
        val total = p + n
        if (total <= 0L || p == 0L || n == 0L) {
            return Balance(p, n, 1.0, 1.0, if (total == 0L) 0.5 else p.toDouble() / total)
        }
        // Inverse-frequency weights with conservative clipping. Mean contribution of
        // each class is approximately equal without allowing rare labels to explode.
        val rawPositive = total.toDouble() / (2.0 * p)
        val rawNegative = total.toDouble() / (2.0 * n)
        val positiveWeight = rawPositive.coerceIn(MIN_CLASS_WEIGHT, MAX_CLASS_WEIGHT)
        val negativeWeight = rawNegative.coerceIn(MIN_CLASS_WEIGHT, MAX_CLASS_WEIGHT)
        return Balance(p, n, positiveWeight, negativeWeight, p.toDouble() / total)
    }

    fun balance(successes: Iterable<Boolean>): Balance {
        var p = 0L
        var n = 0L
        successes.forEach { if (it) p++ else n++ }
        return balance(p, n)
    }

    fun sampleWeight(success: Boolean, baseWeight: Double, balance: Balance): Double {
        val classWeight = if (success) balance.positiveWeight else balance.negativeWeight
        return (baseWeight * classWeight).coerceIn(0.10, 5.0)
    }

    /**
     * Chooses TAKE/REJECT cutoffs from the probability distribution observed on a
     * development calibration slice. Threshold candidates are probability quantiles,
     * so a well-ranked but conservatively calibrated model is not forced into zero TAKEs.
     * A threshold is considered viable only when TAKE average net return is positive.
     */
    fun calibrate(
        points: List<CalibrationPoint>,
        fallback: NumericalMetaBrain.HyperParameters,
        minimumTake: Int = requiredActions(points.size),
        minimumReject: Int = requiredActions(points.size),
    ): CalibrationResult {
        if (points.size < MIN_CALIBRATION_POINTS) {
            return evaluateThresholds(points, fallback.takeThreshold, fallback.rejectThreshold, minimumTake, minimumReject)
        }
        val probabilities = points.map { it.probability.coerceIn(0.0, 1.0) }.sorted()
        val takeCandidates = linkedSetOf<Double>()
        val rejectCandidates = linkedSetOf<Double>()
        TAKE_QUANTILES.forEach { q -> takeCandidates += quantile(probabilities, q) }
        REJECT_QUANTILES.forEach { q -> rejectCandidates += quantile(probabilities, q) }
        // Keep the configured thresholds in the search as anchors.
        takeCandidates += fallback.takeThreshold
        rejectCandidates += fallback.rejectThreshold

        var best: CalibrationResult? = null
        for (takeRaw in takeCandidates) {
            val take = takeRaw.coerceIn(MIN_TAKE_THRESHOLD, MAX_TAKE_THRESHOLD)
            for (rejectRaw in rejectCandidates) {
                val reject = rejectRaw.coerceIn(MIN_REJECT_THRESHOLD, MAX_REJECT_THRESHOLD)
                if (reject > take - MIN_THRESHOLD_GAP) continue
                val candidate = evaluateThresholds(points, take, reject, minimumTake, minimumReject)
                if (best == null || candidate.score > best!!.score) best = candidate
            }
        }
        return best ?: evaluateThresholds(points, fallback.takeThreshold, fallback.rejectThreshold, minimumTake, minimumReject)
    }

    fun applyCalibration(
        brain: NumericalMetaBrain,
        points: List<CalibrationPoint>,
        minimumTake: Int = requiredActions(points.size),
        minimumReject: Int = requiredActions(points.size),
    ): CalibrationResult {
        val current = brain.currentHyperParameters()
        val result = calibrate(points, current, minimumTake, minimumReject)
        brain.configure(
            current.copy(
                takeThreshold = result.takeThreshold,
                rejectThreshold = result.rejectThreshold,
            ),
            bumpVersion = false,
        )
        return result
    }

    fun requiredActions(labels: Int): Int =
        max(MIN_ACTIONS, min(MAX_ACTIONS, kotlin.math.ceil(labels * ACTION_FRACTION).toInt()))

    private fun evaluateThresholds(
        points: List<CalibrationPoint>,
        take: Double,
        reject: Double,
        minimumTake: Int,
        minimumReject: Int,
    ): CalibrationResult {
        var takeCount = 0
        var takeWins = 0
        var takeNet = 0.0
        var rejectCount = 0
        var rejectLosses = 0
        points.forEach { p ->
            when {
                p.probability >= take -> {
                    takeCount++
                    if (p.success) takeWins++
                    takeNet += p.netReturn
                }
                p.probability <= reject -> {
                    rejectCount++
                    if (!p.success) rejectLosses++
                }
            }
        }
        val takePrecision = if (takeCount == 0) 0.0 else takeWins.toDouble() / takeCount
        val takeAverageNet = if (takeCount == 0) 0.0 else takeNet / takeCount
        val rejectPrecision = if (rejectCount == 0) 0.0 else rejectLosses.toDouble() / rejectCount
        val takeCoverage = if (minimumTake <= 0) 1.0 else min(takeCount.toDouble() / minimumTake, 1.0)
        val rejectCoverage = if (minimumReject <= 0) 1.0 else min(rejectCount.toDouble() / minimumReject, 1.0)
        val viable = takeCount >= minimumTake && rejectCount >= minimumReject && takeAverageNet > 0.0
        val starvationPenalty = 0.50 * (1.0 - takeCoverage) + 0.10 * (1.0 - rejectCoverage)
        val quality = 0.45 * (takePrecision - 0.50) + 0.20 * (rejectPrecision - 0.50) +
            1.75 * takeAverageNet + 0.12 * takeCoverage + 0.05 * rejectCoverage
        val viabilityBonus = if (viable) 0.25 else 0.0
        val score = quality + viabilityBonus - starvationPenalty - 0.01 * abs(take - reject)
        return CalibrationResult(take, reject, takeCount, takePrecision, takeAverageNet, rejectCount, rejectPrecision, score, viable)
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.5
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.lastIndex)
        val lo = pos.toInt()
        val hi = min(lo + 1, sorted.lastIndex)
        val frac = pos - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    const val MIN_TAKE_THRESHOLD = 0.25
    const val MAX_TAKE_THRESHOLD = 0.90
    const val MIN_REJECT_THRESHOLD = 0.05
    const val MAX_REJECT_THRESHOLD = 0.60
    const val MIN_THRESHOLD_GAP = 0.05
    private const val MIN_CLASS_WEIGHT = 0.50
    private const val MAX_CLASS_WEIGHT = 3.00
    private const val MIN_CALIBRATION_POINTS = 20
    private const val ACTION_FRACTION = 0.05
    private const val MIN_ACTIONS = 5
    private const val MAX_ACTIONS = 100
    private val TAKE_QUANTILES = doubleArrayOf(0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90, 0.93, 0.95)
    private val REJECT_QUANTILES = doubleArrayOf(0.05, 0.10, 0.15, 0.20, 0.25, 0.30, 0.40, 0.50)
}
