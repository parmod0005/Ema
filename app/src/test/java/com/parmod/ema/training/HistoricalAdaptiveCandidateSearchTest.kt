package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalAdaptiveCandidateSearchTest {
    @Test
    fun takeStarvationGenerationLowersThresholdAndStaysUnique() {
        val parent = NumericalMetaBrain.HyperParameters(
            learningRate = 0.005,
            l2 = 0.00070,
            takeThreshold = 0.80,
            rejectThreshold = 0.289,
        )
        val seen = linkedSetOf(HistoricalAdaptiveCandidateSearch.signature(parent))
        val batch = HistoricalAdaptiveCandidateSearch.nextBatch(
            parent = parent,
            generation = 1,
            seenSignatures = seen,
            guidance = HistoricalAdaptiveCandidateSearch.Guidance.INCREASE_TAKES,
        )

        assertEquals(1, batch.generation)
        assertEquals(HistoricalAdaptiveCandidateSearch.CANDIDATES_PER_GENERATION, batch.candidates.size)
        assertEquals(batch.candidates.size, batch.candidates.map(HistoricalAdaptiveCandidateSearch::signature).distinct().size)
        assertTrue(batch.candidates.all { it.learningRate in 0.003..0.060 })
        assertTrue(batch.candidates.all { it.l2 in 0.00003..0.00800 })
        assertTrue(batch.candidates.all { it.takeThreshold in 0.51..0.82 })
        assertTrue(batch.candidates.all { it.rejectThreshold in 0.20..0.49 })
        assertTrue(batch.candidates.all { it.takeThreshold - it.rejectThreshold >= 0.039999 })
        assertTrue(batch.candidates.any { it.takeThreshold <= 0.68 })
        assertTrue(batch.candidates.none { it.takeThreshold >= parent.takeThreshold })
    }

    @Test
    fun laterGenerationAvoidsEarlierExactCombinations() {
        val parent = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42)
        val seen = linkedSetOf(HistoricalAdaptiveCandidateSearch.signature(parent))
        val first = HistoricalAdaptiveCandidateSearch.nextBatch(
            parent, 1, seen, HistoricalAdaptiveCandidateSearch.Guidance.BALANCED,
        )
        val beforeSecond = seen.toSet()
        val second = HistoricalAdaptiveCandidateSearch.nextBatch(
            first.candidates.first(), 2, seen, HistoricalAdaptiveCandidateSearch.Guidance.BALANCED,
        )

        assertTrue(second.candidates.none { HistoricalAdaptiveCandidateSearch.signature(it) in beforeSecond })
        assertEquals(HistoricalAdaptiveCandidateSearch.CANDIDATES_PER_GENERATION, second.candidates.size)
    }

    @Test
    fun zeroTakeCandidateGetsIncreaseTakesGuidanceAndHeavyRankingPenalty() {
        val starved = evaluation(
            score = 0.25,
            takeSamples = 0,
            takePrecision = 0.0,
            takeNet = 0.0,
            rejectSamples = 150_000,
            rejectPrecision = 0.65,
        )
        val actionable = evaluation(
            score = 0.12,
            takeSamples = 5_000,
            takePrecision = 0.58,
            takeNet = 0.012,
            rejectSamples = 100_000,
            rejectPrecision = 0.58,
        )

        assertEquals(HistoricalAdaptiveCandidateSearch.Guidance.INCREASE_TAKES, HistoricalAdaptiveCandidateSearch.guidance(starved))
        assertTrue(
            HistoricalAdaptiveCandidateSearch.developmentSelectionScore(actionable) >
                HistoricalAdaptiveCandidateSearch.developmentSelectionScore(starved),
        )
        assertEquals(actionable, HistoricalAdaptiveCandidateSearch.selectBest(listOf(starved, actionable)))
    }

    private fun evaluation(
        score: Double,
        takeSamples: Long,
        takePrecision: Double,
        takeNet: Double,
        rejectSamples: Long,
        rejectPrecision: Double,
    ) = HistoricalCorpusTrainer.CandidateEvaluation(
        hyperParameters = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42),
        foldsRun = 4,
        foldsWon = 4,
        candidate = HistoricalCorpusTrainer.Metrics(
            labels = 200_000,
            accuracy = 0.65,
            brier = 0.23,
            takeSamples = takeSamples,
            takePrecision = takePrecision,
            rejectSamples = rejectSamples,
            rejectPrecision = rejectPrecision,
            takeAverageNetReturn = takeNet,
        ),
        production = HistoricalCorpusTrainer.Metrics(
            labels = 200_000,
            accuracy = 0.505,
            brier = 0.248,
        ),
        score = score,
        robust = true,
    )
}
