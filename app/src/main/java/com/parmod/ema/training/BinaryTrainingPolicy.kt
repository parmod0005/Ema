package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared binary-training policy for historical/live research.
 *
 * Industrial research rules:
 * - class weights are estimated from fitting data only;
 * - TAKE/REJECT thresholds are calibrated on a later development slice only;
 * - calibration optimizes cost-adjusted TAKE expectancy, not raw accuracy;
 * - joint calibration can require the same policy to work on every market segment;
 * - forward scoring and locked holdout data are never used to fit weights or thresholds.
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
        val expectedContribution: Double = 0.0,
    )

    /**
     * Fixed-size streaming probability histogram. A multi-million-row validation split
     * therefore costs a few small arrays instead of millions of Prediction objects.
     */
    class StreamingCalibration {
        private val counts = LongArray(PROBABILITY_BINS)
        private val wins = LongArray(PROBABILITY_BINS)
        private val net = DoubleArray(PROBABILITY_BINS)
        private var cached: Prefix? = null
        var labels: Long = 0L
            private set

        fun add(probability: Double, success: Boolean, netReturn: Double) {
            val p = probability.coerceIn(0.0, 1.0)
            val bin = (p * LAST_BIN).roundToInt().coerceIn(0, LAST_BIN)
            counts[bin]++
            if (success) wins[bin]++
            net[bin] += netReturn
            labels++
            cached = null
        }

        fun add(point: CalibrationPoint) = add(point.probability, point.success, point.netReturn)

        fun evaluate(
            takeThreshold: Double,
            rejectThreshold: Double,
            minimumTake: Int = requiredActions(labels.toInt()),
            minimumReject: Int = requiredActions(labels.toInt()),
            minimumTakePrecision: Double = HistoricalCandidateGovernance.MIN_TAKE_PRECISION,
            minimumRejectPrecision: Double = HistoricalCandidateGovernance.MIN_REJECT_PRECISION,
        ): CalibrationResult {
            val take = takeThreshold.coerceIn(MIN_TAKE_THRESHOLD, MAX_TAKE_THRESHOLD)
            val reject = rejectThreshold.coerceIn(MIN_REJECT_THRESHOLD, MAX_REJECT_THRESHOLD)
                .coerceAtMost(take - MIN_THRESHOLD_GAP)
            val p = prefix()
            val takeBin = ceil(take * LAST_BIN).toInt().coerceIn(0, LAST_BIN)
            val rejectBin = floor(reject * LAST_BIN).toInt().coerceIn(0, LAST_BIN)

            val beforeTake = if (takeBin <= 0) 0L else p.count[takeBin - 1]
            val beforeTakeWins = if (takeBin <= 0) 0L else p.wins[takeBin - 1]
            val beforeTakeNet = if (takeBin <= 0) 0.0 else p.net[takeBin - 1]
            val takeCount = (p.count[LAST_BIN] - beforeTake).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val takeWins = (p.wins[LAST_BIN] - beforeTakeWins).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val takeNet = p.net[LAST_BIN] - beforeTakeNet
            val rejectCount = p.count[rejectBin].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val rejectWins = p.wins[rejectBin].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val rejectLosses = rejectCount - rejectWins

            val takePrecision = if (takeCount == 0) 0.0 else takeWins.toDouble() / takeCount
            val takeAverageNet = if (takeCount == 0) 0.0 else takeNet / takeCount
            val rejectPrecision = if (rejectCount == 0) 0.0 else rejectLosses.toDouble() / rejectCount
            val minTake = minimumTake.coerceAtLeast(1)
            val minReject = minimumReject.coerceAtLeast(1)
            val takeEvidence = min(takeCount.toDouble() / minTake, 1.0)
            val rejectEvidence = min(rejectCount.toDouble() / minReject, 1.0)
            val expectedContribution = if (labels <= 0L) 0.0 else takeNet / labels
            val viable = takeCount >= minTake && rejectCount >= minReject &&
                takePrecision >= minimumTakePrecision &&
                rejectPrecision >= minimumRejectPrecision &&
                takeAverageNet > 0.0

            // Trading utility dominates. Accuracy is deliberately absent because a
            // reject-only classifier can have excellent accuracy while never trading.
            val utility = 8.0 * expectedContribution +
                0.55 * (takePrecision - minimumTakePrecision) +
                0.18 * (rejectPrecision - minimumRejectPrecision) +
                0.10 * takeEvidence + 0.04 * rejectEvidence +
                0.30 * takeAverageNet
            val starvationPenalty = 0.80 * (1.0 - takeEvidence) + 0.15 * (1.0 - rejectEvidence)
            val viabilityBonus = if (viable) 2.0 else 0.0
            val score = utility + viabilityBonus - starvationPenalty

            return CalibrationResult(
                takeThreshold = take,
                rejectThreshold = reject,
                takeSamples = takeCount,
                takePrecision = takePrecision,
                takeAverageNetReturn = takeAverageNet,
                rejectSamples = rejectCount,
                rejectPrecision = rejectPrecision,
                score = score,
                viable = viable,
                expectedContribution = expectedContribution,
            )
        }

        private fun prefix(): Prefix {
            cached?.let { return it }
            val c = LongArray(PROBABILITY_BINS)
            val w = LongArray(PROBABILITY_BINS)
            val n = DoubleArray(PROBABILITY_BINS)
            var runningCount = 0L
            var runningWins = 0L
            var runningNet = 0.0
            for (i in 0..LAST_BIN) {
                runningCount += counts[i]
                runningWins += wins[i]
                runningNet += net[i]
                c[i] = runningCount
                w[i] = runningWins
                n[i] = runningNet
            }
            return Prefix(c, w, n).also { cached = it }
        }
    }

    private data class Prefix(
        val count: LongArray,
        val wins: LongArray,
        val net: DoubleArray,
    )

    fun balance(positives: Long, negatives: Long): Balance {
        val p = positives.coerceAtLeast(0L)
        val n = negatives.coerceAtLeast(0L)
        val total = p + n
        if (total <= 0L || p == 0L || n == 0L) {
            return Balance(p, n, 1.0, 1.0, if (total == 0L) 0.5 else p.toDouble() / total)
        }
        // Square-root inverse-frequency weighting is intentionally milder than full
        // inverse-frequency reweighting, preserving probability calibration better.
        val rawPositive = sqrt(total.toDouble() / (2.0 * p))
        val rawNegative = sqrt(total.toDouble() / (2.0 * n))
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

    fun calibrate(
        points: List<CalibrationPoint>,
        fallback: NumericalMetaBrain.HyperParameters,
        minimumTake: Int = requiredActions(points.size),
        minimumReject: Int = requiredActions(points.size),
    ): CalibrationResult {
        val stream = StreamingCalibration()
        points.forEach(stream::add)
        return calibrate(stream, fallback, minimumTake, minimumReject)
    }

    fun calibrate(
        stream: StreamingCalibration,
        fallback: NumericalMetaBrain.HyperParameters,
        minimumTake: Int = requiredActions(stream.labels.toInt()),
        minimumReject: Int = requiredActions(stream.labels.toInt()),
    ): CalibrationResult = selectPolicy(
        overall = stream,
        segments = emptyList(),
        fallback = fallback,
        minimumTake = minimumTake,
        minimumReject = minimumReject,
    )

    /** Same TAKE/REJECT policy must satisfy the overall stream and every segment. */
    fun calibrateJoint(
        overall: StreamingCalibration,
        segments: Collection<StreamingCalibration>,
        fallback: NumericalMetaBrain.HyperParameters,
        minimumTake: Int = requiredActions(overall.labels.toInt()),
        minimumReject: Int = requiredActions(overall.labels.toInt()),
    ): CalibrationResult = selectPolicy(overall, segments.toList(), fallback, minimumTake, minimumReject)

    fun applyCalibration(
        brain: NumericalMetaBrain,
        stream: StreamingCalibration,
        minimumTake: Int = requiredActions(stream.labels.toInt()),
        minimumReject: Int = requiredActions(stream.labels.toInt()),
    ): CalibrationResult {
        val current = brain.currentHyperParameters()
        val result = calibrate(stream, current, minimumTake, minimumReject)
        brain.configure(current.copy(takeThreshold = result.takeThreshold, rejectThreshold = result.rejectThreshold), bumpVersion = false)
        return result
    }

    private fun selectPolicy(
        overall: StreamingCalibration,
        segments: List<StreamingCalibration>,
        fallback: NumericalMetaBrain.HyperParameters,
        minimumTake: Int,
        minimumReject: Int,
    ): CalibrationResult {
        if (overall.labels <= 0L) {
            return overall.evaluate(fallback.takeThreshold, fallback.rejectThreshold, minimumTake, minimumReject)
        }
        val thresholds = policyThresholds(fallback)
        var bestOverall: CalibrationResult? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (take in thresholds.first) {
            for (reject in thresholds.second) {
                if (reject > take - MIN_THRESHOLD_GAP) continue
                val o = overall.evaluate(take, reject, minimumTake, minimumReject)
                var segmentScore = 0.0
                var segmentsViable = true
                for (segment in segments) {
                    val required = requiredActions(segment.labels.toInt())
                    val s = segment.evaluate(take, reject, required, required)
                    segmentScore += s.score
                    if (!s.viable) segmentsViable = false
                }
                val fullyViable = o.viable && segmentsViable
                val normalizedSegment = if (segments.isEmpty()) 0.0 else segmentScore / segments.size
                val combined = o.score + 0.45 * normalizedSegment + if (fullyViable) 3.0 else 0.0
                val currentBestViable = bestOverall?.viable == true
                if ((fullyViable && !currentBestViable) || (fullyViable == currentBestViable && combined > bestScore)) {
                    bestOverall = o.copy(viable = fullyViable, score = combined)
                    bestScore = combined
                }
            }
        }
        return bestOverall ?: overall.evaluate(fallback.takeThreshold, fallback.rejectThreshold, minimumTake, minimumReject)
    }

    private fun policyThresholds(fallback: NumericalMetaBrain.HyperParameters): Pair<List<Double>, List<Double>> {
        val takes = linkedSetOf<Double>()
        val rejects = linkedSetOf<Double>()
        var t = MIN_TAKE_THRESHOLD
        while (t <= MAX_TAKE_THRESHOLD + 1e-9) {
            takes += round3(t)
            t += THRESHOLD_STEP
        }
        var r = MIN_REJECT_THRESHOLD
        while (r <= MAX_REJECT_THRESHOLD + 1e-9) {
            rejects += round3(r)
            r += THRESHOLD_STEP
        }
        takes += fallback.takeThreshold.coerceIn(MIN_TAKE_THRESHOLD, MAX_TAKE_THRESHOLD)
        rejects += fallback.rejectThreshold.coerceIn(MIN_REJECT_THRESHOLD, MAX_REJECT_THRESHOLD)
        return takes.sorted() to rejects.sorted()
    }

    fun requiredActions(labels: Int): Int =
        max(MIN_ACTIONS, min(MAX_ACTIONS, ceil(labels.coerceAtLeast(0) * ACTION_FRACTION).toInt()))

    private fun round3(value: Double): Double = (value * 1_000.0).roundToInt() / 1_000.0

    const val MIN_TAKE_THRESHOLD = 0.25
    const val MAX_TAKE_THRESHOLD = 0.90
    const val MIN_REJECT_THRESHOLD = 0.05
    const val MAX_REJECT_THRESHOLD = 0.60
    const val MIN_THRESHOLD_GAP = 0.05
    private const val MIN_CLASS_WEIGHT = 0.70
    private const val MAX_CLASS_WEIGHT = 1.80
    private const val ACTION_FRACTION = 0.05
    private const val MIN_ACTIONS = 5
    private const val MAX_ACTIONS = 100
    private const val PROBABILITY_BINS = 201
    private const val LAST_BIN = PROBABILITY_BINS - 1
    private const val THRESHOLD_STEP = 0.01
}
