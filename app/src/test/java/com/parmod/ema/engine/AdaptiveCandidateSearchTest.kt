package com.parmod.ema.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveCandidateSearchTest {
    @Test
    fun generated_candidates_stay_inside_guardrails() {
        val base = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42)
        var cursor = 0
        repeat(24) { generation ->
            val generated = AdaptiveCandidateSearch.next(base, generation + 1, cursor, emptySet())
            val h = generated.hyperParameters
            assertTrue(h.learningRate in 0.005..0.050)
            assertTrue(h.l2 in 0.00005..0.00500)
            assertTrue(h.takeThreshold in 0.25..0.90)
            assertTrue(h.rejectThreshold in 0.05..0.60)
            assertTrue(h.takeThreshold - h.rejectThreshold >= 0.049999)
            cursor = generated.mutationIndex + 1
        }
    }

    @Test
    fun preserves_evidence_calibrated_historical_thresholds() {
        val calibrated = NumericalMetaBrain.HyperParameters(0.018, 0.0007, 0.42, 0.24)
        val bounded = AdaptiveCandidateSearch.bounded(calibrated)
        assertEquals(0.42, bounded.takeThreshold, 1e-9)
        assertEquals(0.24, bounded.rejectThreshold, 1e-9)
    }

    @Test
    fun search_skips_already_tested_neighbour() {
        val base = NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42)
        val first = AdaptiveCandidateSearch.next(base, 1, 0, emptySet())
        val second = AdaptiveCandidateSearch.next(
            base,
            1,
            0,
            setOf(AdaptiveCandidateSearch.signature(first.hyperParameters)),
        )
        assertNotEquals(
            AdaptiveCandidateSearch.signature(first.hyperParameters),
            AdaptiveCandidateSearch.signature(second.hyperParameters),
        )
    }

    @Test
    fun same_inputs_generate_same_candidate() {
        val base = NumericalMetaBrain.HyperParameters(0.020, 0.0008, 0.69, 0.39)
        val a = AdaptiveCandidateSearch.next(base, 3, 4, emptySet())
        val b = AdaptiveCandidateSearch.next(base, 3, 4, emptySet())
        assertEquals(AdaptiveCandidateSearch.signature(a.hyperParameters), AdaptiveCandidateSearch.signature(b.hyperParameters))
        assertEquals(a.mutationIndex, b.mutationIndex)
    }
}
