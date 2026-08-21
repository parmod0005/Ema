package com.parmod.ema.training

/**
 * Shared fail-closed safeguards for chronological model research.
 *
 * Historical option labels inspect bars after entry. A simple chronological split is
 * therefore not sufficient: the final labels on the earlier slice can overlap the
 * first observations in the next slice. This helper creates a purge/embargo wider
 * than the label horizon and validates that TRAIN -> CALIBRATION -> SCORE -> TEST
 * remain truly separated.
 */
object TrainingLeakageGuard {
    data class Report(
        val passed: Boolean,
        val embargoMillis: Long,
        val reasons: List<String>,
    ) {
        val label: String get() = if (passed) "PASS" else "FAIL"
    }

    /** Label horizon plus one full bar of safety. */
    fun embargoMillis(interval: String, horizonBars: Int, extraBars: Int = 1): Long {
        require(horizonBars >= 1)
        require(extraBars >= 0)
        val minutes = intervalMinutes(interval)
        return (horizonBars.toLong() + extraBars.toLong()) * minutes * 60_000L
    }

    fun intervalMinutes(interval: String): Long {
        val normalized = interval.trim().lowercase()
        val number = normalized.takeWhile { it.isDigit() }.toLongOrNull()
        return when {
            normalized.endsWith("minute") && number != null -> number.coerceAtLeast(1L)
            normalized.endsWith("minutes") && number != null -> number.coerceAtLeast(1L)
            normalized == "minute" || normalized == "1m" -> 1L
            normalized.endsWith("m") && normalized.dropLast(1).toLongOrNull() != null -> normalized.dropLast(1).toLong().coerceAtLeast(1L)
            else -> 1L
        }
    }

    /**
     * Removes observations whose forward label horizon could touch the next slice.
     * Rows are expected to be chronological; ordering is checked separately.
     */
    fun <T> purgeBeforeBoundary(
        rows: List<T>,
        boundaryTimestamp: Long,
        embargoMillis: Long,
        timestamp: (T) -> Long,
    ): List<T> {
        if (rows.isEmpty()) return rows
        require(embargoMillis >= 0L)
        val cutoff = boundaryTimestamp - embargoMillis
        return rows.takeWhile { timestamp(it) < cutoff }
    }

    /** Returns the first row at or after boundary + embargo. */
    fun <T> purgeAfterBoundary(
        rows: List<T>,
        boundaryTimestamp: Long,
        embargoMillis: Long,
        timestamp: (T) -> Long,
    ): List<T> {
        if (rows.isEmpty()) return rows
        require(embargoMillis >= 0L)
        val cutoff = boundaryTimestamp + embargoMillis
        return rows.dropWhile { timestamp(it) < cutoff }
    }

    fun <T> validateOrderedSlices(
        train: List<T>,
        calibration: List<T>,
        scoring: List<T>,
        holdout: List<T> = emptyList(),
        embargoMillis: Long,
        timestamp: (T) -> Long,
    ): Report {
        val reasons = mutableListOf<String>()
        val named = listOf(
            "TRAIN" to train,
            "CALIBRATION" to calibration,
            "SCORING" to scoring,
            "HOLDOUT" to holdout,
        ).filter { it.second.isNotEmpty() }

        named.forEach { (name, rows) ->
            var prior = Long.MIN_VALUE
            rows.forEach { row ->
                val current = timestamp(row)
                if (current < prior) {
                    reasons += "$name is not chronological"
                    return@forEach
                }
                prior = current
            }
        }

        named.zipWithNext().forEach { (left, right) ->
            val leftLast = timestamp(left.second.last())
            val rightFirst = timestamp(right.second.first())
            if (rightFirst <= leftLast) reasons += "${left.first}/${right.first} timestamps overlap"
            if (rightFirst - leftLast < embargoMillis) {
                reasons += "${left.first}/${right.first} embargo is shorter than ${embargoMillis}ms"
            }
        }
        return Report(reasons.isEmpty(), embargoMillis, reasons.distinct())
    }

    /** Exact IDs must never appear in more than one research partition/source. */
    fun duplicateIds(vararg partitions: Iterable<String>): Set<String> {
        val seen = HashSet<String>()
        val duplicates = linkedSetOf<String>()
        partitions.forEach { partition ->
            partition.forEach { id ->
                if (!seen.add(id)) duplicates += id
            }
        }
        return duplicates
    }
}
