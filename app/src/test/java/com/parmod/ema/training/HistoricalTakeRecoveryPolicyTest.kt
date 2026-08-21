package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalTakeRecoveryPolicyTest {
    @Test
    fun increaseTakesExploresBelowOldFiftyOnePercentFloor() {
        val seen = mutableSetOf<String>()
        val parent = NumericalMetaBrain.HyperParameters(
            learningRate = 0.005,
            l2 = 0.0007,
            takeThreshold = 0.51,
            rejectThreshold = 0.36,
        )
        seen += HistoricalAdaptiveCandidateSearch.signature(parent)
        val batch = HistoricalAdaptiveCandidateSearch.nextBatch(
            parent = parent,
            generation = 1,
            seenSignatures = seen,
            guidance = HistoricalAdaptiveCandidateSearch.Guidance.INCREASE_TAKES,
        )
        assertTrue(batch.candidates.size == HistoricalAdaptiveCandidateSearch.CANDIDATES_PER_GENERATION)
        assertTrue(batch.candidates.any { it.takeThreshold < 0.51 })
        assertTrue(batch.candidates.all { it.takeThreshold >= BinaryTrainingPolicy.MIN_TAKE_THRESHOLD })
        assertTrue(batch.candidates.all { it.rejectThreshold <= it.takeThreshold - BinaryTrainingPolicy.MIN_THRESHOLD_GAP + 1e-9 })
    }

    @Test
    fun calibrationCanSelectPositiveNetTakePolicyBelowFiftyPercent() {
        val points = buildList {
            repeat(12) { add(BinaryTrainingPolicy.CalibrationPoint(0.46 + it * 0.005, true, 0.035)) }
            repeat(8) { add(BinaryTrainingPolicy.CalibrationPoint(0.42 + it * 0.003, false, -0.030)) }
            repeat(20) { add(BinaryTrainingPolicy.CalibrationPoint(0.18 + it * 0.004, false, -0.040)) }
        }
        val result = BinaryTrainingPolicy.calibrate(
            points = points,
            fallback = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42),
            minimumTake = 5,
            minimumReject = 5,
        )
        assertTrue(result.takeSamples >= 5)
        assertTrue(result.takeAverageNetReturn > 0.0)
        assertTrue(result.takeThreshold < 0.51)
        assertTrue(result.rejectThreshold <= result.takeThreshold - BinaryTrainingPolicy.MIN_THRESHOLD_GAP + 1e-9)
    }
}
