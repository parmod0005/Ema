package com.parmod.ema.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingLeakageGuardTest {
    private data class Row(val ts: Long)

    @Test
    fun five_bar_one_minute_label_uses_six_minute_embargo() {
        assertEquals(6L * 60_000L, TrainingLeakageGuard.embargoMillis("1minute", 5))
        assertEquals(18L * 60_000L, TrainingLeakageGuard.embargoMillis("3minute", 5))
    }

    @Test
    fun purge_removes_rows_whose_forward_label_can_touch_next_slice() {
        val minute = 60_000L
        val rows = (0L..20L).map { Row(it * minute) }
        val kept = TrainingLeakageGuard.purgeBeforeBoundary(
            rows = rows,
            boundaryTimestamp = 20L * minute,
            embargoMillis = 6L * minute,
            timestamp = { it.ts },
        )
        assertEquals((0L..13L).toList(), kept.map { it.ts / minute })
    }

    @Test
    fun validation_fails_when_calibration_and_scoring_are_not_embargoed() {
        val minute = 60_000L
        val report = TrainingLeakageGuard.validateOrderedSlices(
            train = listOf(Row(0), Row(1 * minute)),
            calibration = listOf(Row(10 * minute), Row(11 * minute)),
            scoring = listOf(Row(12 * minute), Row(13 * minute)),
            embargoMillis = 6 * minute,
            timestamp = { it.ts },
        )
        assertFalse(report.passed)
        assertTrue(report.reasons.any { "CALIBRATION/SCORING embargo" in it })
    }

    @Test
    fun validation_passes_for_strictly_separated_slices() {
        val minute = 60_000L
        val report = TrainingLeakageGuard.validateOrderedSlices(
            train = listOf(Row(0), Row(1 * minute)),
            calibration = listOf(Row(8 * minute), Row(9 * minute)),
            scoring = listOf(Row(16 * minute), Row(17 * minute)),
            holdout = listOf(Row(24 * minute), Row(25 * minute)),
            embargoMillis = 6 * minute,
            timestamp = { it.ts },
        )
        assertTrue(report.reasons.joinToString(), report.passed)
    }

    @Test
    fun exact_duplicate_evidence_is_detected_across_sources() {
        val duplicates = TrainingLeakageGuard.duplicateIds(
            listOf("a", "b", "c"),
            listOf("d", "b"),
            listOf("e", "a"),
        )
        assertEquals(setOf("a", "b"), duplicates)
    }
}
