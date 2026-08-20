package com.parmod.ema.training

import com.parmod.ema.engine.AdaptiveCandidateSearch
import com.parmod.ema.engine.NumericalMetaBrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalAdaptiveCandidateSearchTest {
    @Test
    fun generationProducesUniqueBoundedCandidates() {
        val parent = NumericalMetaBrain.HyperParameters(
            learningRate = 0.036,
            l2 = 0.00054,
            takeThreshold = 0.625,
            rejectThreshold = 0.445,
        )
        val seen = linkedSetOf(AdaptiveCandidateSearch.signature(parent))
        val batch = HistoricalAdaptiveCandidateSearch.nextBatch(parent, 1, seen)

        assertEquals(1, batch.generation)
        assertEquals(HistoricalAdaptiveCandidateSearch.CANDIDATES_PER_GENERATION, batch.candidates.size)
        assertEquals(batch.candidates.size, batch.candidates.map(AdaptiveCandidateSearch::signature).distinct().size)
        assertTrue(batch.candidates.all { it.learningRate in 0.005..0.050 })
        assertTrue(batch.candidates.all { it.l2 in 0.00005..0.00500 })
        assertTrue(batch.candidates.all { it.takeThreshold in 0.60..0.80 })
        assertTrue(batch.candidates.all { it.rejectThreshold in 0.25..0.48 })
        assertTrue(batch.candidates.all { it.takeThreshold - it.rejectThreshold >= 0.079999 })
    }

    @Test
    fun laterGenerationAvoidsEarlierExactCombinations() {
        val parent = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42)
        val seen = linkedSetOf(AdaptiveCandidateSearch.signature(parent))
        val first = HistoricalAdaptiveCandidateSearch.nextBatch(parent, 1, seen)
        val beforeSecond = seen.toSet()
        val second = HistoricalAdaptiveCandidateSearch.nextBatch(first.candidates.first(), 2, seen)

        assertTrue(second.candidates.none { AdaptiveCandidateSearch.signature(it) in beforeSecond })
        assertEquals(HistoricalAdaptiveCandidateSearch.CANDIDATES_PER_GENERATION, second.candidates.size)
    }
}
