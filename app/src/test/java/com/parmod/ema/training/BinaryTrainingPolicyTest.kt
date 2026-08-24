package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryTrainingPolicyTest {
    private val fallback = NumericalMetaBrain.HyperParameters(
        learningRate = 0.015,
        l2 = 0.0005,
        takeThreshold = 0.66,
        rejectThreshold = 0.42,
    )

    @Test
    fun profitable_policy_below_fifty_percent_can_be_selected() {
        val stream = BinaryTrainingPolicy.StreamingCalibration()
        repeat(70) { stream.add(0.44, true, +0.045) }
        repeat(30) { stream.add(0.29, false, -0.075) }

        val result = BinaryTrainingPolicy.calibrate(stream, fallback)

        assertTrue(result.viable)
        assertTrue(result.takeThreshold < 0.50)
        assertTrue(result.takePrecision >= HistoricalCandidateGovernance.MIN_TAKE_PRECISION)
        assertTrue(result.takeAverageNetReturn > 0.0)
        assertTrue(result.rejectPrecision >= HistoricalCandidateGovernance.MIN_REJECT_PRECISION)
    }

    @Test
    fun negative_take_expectancy_is_not_viable() {
        val stream = BinaryTrainingPolicy.StreamingCalibration()
        repeat(60) { stream.add(0.72, true, -0.010) }
        repeat(40) { stream.add(0.28, false, -0.075) }

        val result = BinaryTrainingPolicy.calibrate(stream, fallback)

        assertFalse(result.viable)
    }

    @Test
    fun joint_policy_must_work_on_every_market_segment() {
        val overall = BinaryTrainingPolicy.StreamingCalibration()
        val nifty = BinaryTrainingPolicy.StreamingCalibration()
        val sensex = BinaryTrainingPolicy.StreamingCalibration()

        repeat(60) {
            nifty.add(0.70, true, +0.040)
            overall.add(0.70, true, +0.040)
        }
        repeat(40) {
            nifty.add(0.25, false, -0.075)
            overall.add(0.25, false, -0.075)
        }

        // SENSEX high-probability setups are deliberately bad, so a policy that looks
        // good overall/NIFTY must not be considered jointly viable.
        repeat(60) {
            sensex.add(0.70, false, -0.075)
            overall.add(0.70, false, -0.075)
        }
        repeat(40) {
            sensex.add(0.25, false, -0.020)
            overall.add(0.25, false, -0.020)
        }

        val result = BinaryTrainingPolicy.calibrateJoint(overall, listOf(nifty, sensex), fallback)

        assertFalse(result.viable)
    }

    @Test
    fun profitable_high_precision_policy_is_viable() {
        val stream = BinaryTrainingPolicy.StreamingCalibration()
        repeat(65) { stream.add(0.78, true, +0.035) }
        repeat(10) { stream.add(0.78, false, -0.075) }
        repeat(50) { stream.add(0.22, false, -0.050) }

        val result = BinaryTrainingPolicy.calibrate(stream, fallback)

        assertTrue(result.viable)
        assertTrue(result.takeSamples >= BinaryTrainingPolicy.requiredActions(stream.labels.toInt()))
        assertTrue(result.takePrecision >= 0.55)
        assertTrue(result.takeAverageNetReturn > 0.0)
    }
}
