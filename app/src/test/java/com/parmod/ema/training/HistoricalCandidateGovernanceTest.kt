package com.parmod.ema.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalCandidateGovernanceTest {
    private fun metrics(
        labels: Long = 100,
        accuracy: Double = 0.70,
        brier: Double = 0.18,
        takeSamples: Long = 20,
        takePrecision: Double = 0.60,
        rejectSamples: Long = 30,
        rejectPrecision: Double = 0.60,
        takeAverageNetReturn: Double = 0.02,
    ) = HistoricalCorpusTrainer.Metrics(
        labels = labels,
        accuracy = accuracy,
        brier = brier,
        takeSamples = takeSamples,
        takePrecision = takePrecision,
        rejectSamples = rejectSamples,
        rejectPrecision = rejectPrecision,
        takeAverageNetReturn = takeAverageNetReturn,
    )

    private fun coverage(e1: Int = 40, e2: Int = 50, e3: Int = 10) = HistoricalCorpusTrainer.Coverage(
        ceSamples = 50,
        peSamples = 50,
        engine1Samples = e1,
        engine2Samples = e2,
        engine3Samples = e3,
        nativeDepthSamples = 0,
    )

    @Test
    fun smallHoldoutIsInsufficientNotFail() {
        val decision = HistoricalCandidateGovernance.evaluate(
            candidate = metrics(labels = 28, takeSamples = 10, rejectSamples = 10),
            production = metrics(labels = 28, accuracy = 0.60, brier = 0.23),
            coverage = coverage(),
            corpusSamples = 100,
            holdoutOpened = true,
        )
        assertEquals(HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA, decision.status)
    }

    @Test
    fun zeroTakeCannotPass() {
        val decision = HistoricalCandidateGovernance.evaluate(
            candidate = metrics(takeSamples = 0, takePrecision = 0.0),
            production = metrics(accuracy = 0.60, brier = 0.23),
            coverage = coverage(),
            corpusSamples = 100,
            holdoutOpened = true,
        )
        assertEquals(HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA, decision.status)
    }

    @Test
    fun negativeTakeNetReturnFails() {
        val decision = HistoricalCandidateGovernance.evaluate(
            candidate = metrics(takeAverageNetReturn = -0.001),
            production = metrics(accuracy = 0.60, brier = 0.23),
            coverage = coverage(),
            corpusSamples = 100,
            holdoutOpened = true,
        )
        assertEquals(HistoricalCandidateGovernance.Status.FAIL, decision.status)
        assertTrue(decision.reasons.any { "net return" in it })
    }

    @Test
    fun dominantEngineFails() {
        val decision = HistoricalCandidateGovernance.evaluate(
            candidate = metrics(),
            production = metrics(accuracy = 0.60, brier = 0.23),
            coverage = coverage(e1 = 5, e2 = 90, e3 = 5),
            corpusSamples = 100,
            holdoutOpened = true,
        )
        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { "dominates" in it || "engine proxies" in it })
    }

    @Test
    fun balancedProfitableCandidatePasses() {
        val decision = HistoricalCandidateGovernance.evaluate(
            candidate = metrics(),
            production = metrics(accuracy = 0.60, brier = 0.23),
            coverage = coverage(),
            corpusSamples = 100,
            holdoutOpened = true,
        )
        assertEquals(HistoricalCandidateGovernance.Status.PASS, decision.status)
        assertTrue(decision.passed)
    }
}
