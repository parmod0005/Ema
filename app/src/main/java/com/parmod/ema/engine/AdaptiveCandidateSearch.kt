package com.parmod.ema.engine

import kotlin.math.roundToInt

/**
 * Deterministic, bounded hyper-parameter evolution for VARDHANI Candidate search.
 *
 * Production is never modified here. Seed profiles remain conservative, while the
 * wider policy bounds deliberately preserve a threshold that was calibrated and
 * governance-approved by historical development. Live unseen validation is the next
 * gate; it must not silently clamp a historical champion back to 60% TAKE.
 */
object AdaptiveCandidateSearch {
    data class GeneratedCandidate(
        val hyperParameters: NumericalMetaBrain.HyperParameters,
        val generation: Int,
        val mutationIndex: Int,
    )

    private data class Mutation(
        val learningRateFactor: Double,
        val l2Factor: Double,
        val takeDelta: Double,
        val rejectDelta: Double,
    )

    private val mutations = listOf(
        Mutation(0.82, 1.20, +0.010, -0.010),
        Mutation(1.18, 0.85, -0.010, +0.010),
        Mutation(0.90, 0.70, +0.020, -0.005),
        Mutation(1.10, 1.35, -0.020, +0.005),
        Mutation(1.30, 1.00, 0.000, -0.020),
        Mutation(0.72, 1.00, +0.025, -0.015),
        Mutation(1.00, 0.55, +0.015, +0.005),
        Mutation(1.00, 1.65, -0.015, -0.005),
    )

    fun next(
        parent: NumericalMetaBrain.HyperParameters,
        generation: Int,
        startMutationIndex: Int,
        seenSignatures: Set<String>,
    ): GeneratedCandidate {
        val safeParent = bounded(parent)
        repeat(mutations.size) { offset ->
            val index = Math.floorMod(startMutationIndex + offset, mutations.size)
            val proposed = mutate(safeParent, mutations[index])
            if (signature(proposed) !in seenSignatures) {
                return GeneratedCandidate(proposed, generation.coerceAtLeast(1), index)
            }
        }

        repeat(32) { attempt ->
            val step = generation.coerceAtLeast(1) + attempt
            val lrFactor = 1.0 + ((step % 9) - 4) * 0.025
            val l2Factor = 1.0 + ((step % 7) - 3) * 0.08
            val takeDelta = ((step % 5) - 2) * 0.004
            val rejectDelta = (((step + 2) % 5) - 2) * 0.004
            val proposed = bounded(
                safeParent.copy(
                    learningRate = safeParent.learningRate * lrFactor,
                    l2 = safeParent.l2 * l2Factor,
                    takeThreshold = safeParent.takeThreshold + takeDelta,
                    rejectThreshold = safeParent.rejectThreshold + rejectDelta,
                ),
            )
            if (signature(proposed) !in seenSignatures) {
                return GeneratedCandidate(proposed, generation.coerceAtLeast(1), mutations.size + attempt)
            }
        }

        val fallback = bounded(
            safeParent.copy(
                learningRate = safeParent.learningRate + 0.0005,
                takeThreshold = safeParent.takeThreshold + 0.002,
                rejectThreshold = safeParent.rejectThreshold - 0.002,
            ),
        )
        return GeneratedCandidate(fallback, generation.coerceAtLeast(1), mutations.size + 32)
    }

    fun signature(hyper: NumericalMetaBrain.HyperParameters): String {
        val h = bounded(hyper)
        val lr = (h.learningRate * 1_000_000.0).roundToInt()
        val l2 = (h.l2 * 1_000_000.0).roundToInt()
        val take = (h.takeThreshold * 100_000.0).roundToInt()
        val reject = (h.rejectThreshold * 100_000.0).roundToInt()
        return "$lr:$l2:$take:$reject"
    }

    fun bounded(hyper: NumericalMetaBrain.HyperParameters): NumericalMetaBrain.HyperParameters {
        val lr = hyper.learningRate.coerceIn(MIN_LR, MAX_LR)
        val l2 = hyper.l2.coerceIn(MIN_L2, MAX_L2)
        val take = hyper.takeThreshold.coerceIn(MIN_TAKE, MAX_TAKE)
        val reject = hyper.rejectThreshold
            .coerceIn(MIN_REJECT, MAX_REJECT)
            .coerceAtMost(take - MIN_THRESHOLD_GAP)
        return NumericalMetaBrain.HyperParameters(lr, l2, take, reject).sanitized()
    }

    private fun mutate(
        parent: NumericalMetaBrain.HyperParameters,
        mutation: Mutation,
    ): NumericalMetaBrain.HyperParameters = bounded(
        parent.copy(
            learningRate = parent.learningRate * mutation.learningRateFactor,
            l2 = parent.l2 * mutation.l2Factor,
            takeThreshold = parent.takeThreshold + mutation.takeDelta,
            rejectThreshold = parent.rejectThreshold + mutation.rejectDelta,
        ),
    )

    private const val MIN_LR = 0.005
    private const val MAX_LR = 0.050
    private const val MIN_L2 = 0.00005
    private const val MAX_L2 = 0.00500
    private const val MIN_TAKE = 0.25
    private const val MAX_TAKE = 0.90
    private const val MIN_REJECT = 0.05
    private const val MAX_REJECT = 0.60
    private const val MIN_THRESHOLD_GAP = 0.05
}
