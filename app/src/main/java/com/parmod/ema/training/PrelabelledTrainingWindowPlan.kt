package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.max

/**
 * Defines leakage-resistant chronological roles over the compact pre-labelled shards.
 *
 * The imported files may be contract-grouped, therefore their TRAIN/VALIDATION/TEST
 * names are treated as storage shards for research. Model roles are derived from
 * timestamps across all shards. FULL uses the complete available date range;
 * 1/3/6/12M are trailing windows anchored to the corpus' latest timestamp.
 */
object PrelabelledTrainingWindowPlan {
    const val FULL = 0
    val ALLOWED_MONTHS: Set<Int> = setOf(FULL, 1, 3, 6, 12)

    data class Source(
        val splits: List<AimlHistoricalOptionCorpusV1Store.Split>,
        val markets: Set<MarketIndex>,
        val fromMs: Long,
        val toMs: Long,
    ) {
        init { require(fromMs <= toMs) }
        fun accepts(r: AimlHistoricalOptionCorpusV1Store.Record): Boolean =
            r.index in markets && r.timestampMs in fromMs..toMs
    }

    data class Plan(
        val requestedMonths: Int,
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val window: Source,
        val train: Source,
        val calibration: Source,
        val scoring: Source,
        val test: Source,
        val trainBoundaryMs: Long,
        val calibrationBoundaryMs: Long,
        val testBoundaryMs: Long,
        val embargoMs: Long = EMBARGO_MS,
    ) {
        val label: String get() = label(requestedMonths)
    }

    data class Analysis(
        val windowRows: Long,
        val trainRows: Long,
        val calibrationRows: Long,
        val scoringRows: Long,
        val testRows: Long,
        val trainByMarket: Map<MarketIndex, Long>,
        val calibrationByMarket: Map<MarketIndex, Long>,
        val scoringByMarket: Map<MarketIndex, Long>,
        val testByMarket: Map<MarketIndex, Long>,
        val coverage: HistoricalCorpusTrainer.Coverage,
        val contracts: Int,
        val expiries: Int,
        val averageMfe: Double,
        val averageMae: Double,
        val averageNet: Double,
    )

    fun build(
        store: AimlHistoricalOptionCorpusV1Store,
        months: Int,
        markets: Set<MarketIndex>,
        shouldCancel: () -> Boolean = { false },
    ): Plan {
        require(months in ALLOWED_MONTHS) { "Historical window must be 1M, 3M, 6M, 12M or FULL" }
        require(markets.isNotEmpty())
        val allSplits = AimlHistoricalOptionCorpusV1Store.Split.entries.toList()
        var globalMin = Long.MAX_VALUE
        var globalMax = Long.MIN_VALUE
        allSplits.forEach { split ->
            store.count(split, shouldCancel) { r ->
                if (r.index !in markets) false else {
                    globalMin = minOf(globalMin, r.timestampMs)
                    globalMax = maxOf(globalMax, r.timestampMs)
                    true
                }
            }
        }
        require(globalMin != Long.MAX_VALUE && globalMax != Long.MIN_VALUE) { "No rows for selected market scope" }

        val endDate = Instant.ofEpochMilli(globalMax).atOffset(IST).toLocalDate()
        val requestedStart = if (months == FULL) globalMin else {
            val startDate = endDate.minusMonths(months.toLong())
            startDate.atStartOfDay().toInstant(IST).toEpochMilli()
        }
        val startMs = max(globalMin, requestedStart)
        val span = globalMax - startMs
        require(span >= MIN_WINDOW_MS) { "Selected historical window is too short for chronological validation" }

        // 70% fit, 15% development validation, 15% locked test.
        val trainBoundary = startMs + span * TRAIN_PERCENT / 100L
        val testBoundary = startMs + span * (TRAIN_PERCENT + VALIDATION_PERCENT) / 100L
        val validationSpan = testBoundary - trainBoundary
        val calibrationBoundary = trainBoundary + (validationSpan * CALIBRATION_FRACTION).toLong()

        // Labels inspect five future minutes; every preceding role ends six minutes
        // before the next role starts. Rows inside those gaps are deliberately unused.
        val trainEnd = trainBoundary - EMBARGO_MS
        val calibrationEnd = calibrationBoundary - EMBARGO_MS
        val scoringEnd = testBoundary - EMBARGO_MS
        require(trainEnd > startMs) { "Training partition is empty after leakage embargo" }
        require(calibrationEnd > trainBoundary) { "Calibration partition is empty after leakage embargo" }
        require(scoringEnd > calibrationBoundary) { "Scoring partition is empty after leakage embargo" }
        require(globalMax > testBoundary) { "Locked test partition is empty" }

        return Plan(
            requestedMonths = months,
            fromDate = Instant.ofEpochMilli(startMs).atOffset(IST).toLocalDate(),
            toDate = endDate,
            window = Source(allSplits, markets, startMs, globalMax),
            train = Source(allSplits, markets, startMs, trainEnd),
            calibration = Source(allSplits, markets, trainBoundary, calibrationEnd),
            scoring = Source(allSplits, markets, calibrationBoundary, scoringEnd),
            test = Source(allSplits, markets, testBoundary, globalMax),
            trainBoundaryMs = trainBoundary,
            calibrationBoundaryMs = calibrationBoundary,
            testBoundaryMs = testBoundary,
        )
    }

    /** One lightweight pass over the selected window; no causal indicators are built. */
    fun analyze(
        store: AimlHistoricalOptionCorpusV1Store,
        plan: Plan,
        shouldCancel: () -> Boolean = { false },
    ): Analysis {
        var windowRows = 0L
        var trainRows = 0L
        var calibrationRows = 0L
        var scoringRows = 0L
        var testRows = 0L
        var ce = 0L
        var pe = 0L
        var e1 = 0L
        var e2 = 0L
        var e3 = 0L
        var mfe = 0.0
        var mae = 0.0
        var net = 0.0
        val contracts = HashSet<String>()
        val expiries = HashSet<Int>()
        val trainBy = MarketIndex.entries.associateWith { 0L }.toMutableMap()
        val calibrationBy = MarketIndex.entries.associateWith { 0L }.toMutableMap()
        val scoringBy = MarketIndex.entries.associateWith { 0L }.toMutableMap()
        val testBy = MarketIndex.entries.associateWith { 0L }.toMutableMap()

        plan.window.splits.forEach { split ->
            store.count(split, shouldCancel) { r ->
                if (!plan.window.accepts(r)) return@count false
                windowRows++
                if (r.optionType == "CE") ce++ else pe++
                when (AimlHistoricalOptionCorpusV1Store.proxyEngine(r)) {
                    2 -> e2++
                    3 -> e3++
                    else -> e1++
                }
                mfe += r.mfe5
                mae += r.mae5
                net += AimlHistoricalOptionCorpusV1Store.netReturn5(r)
                contracts += "${r.index.name}|${r.expiryEpochDay}|${r.strike}|${r.optionType}"
                expiries += r.expiryEpochDay
                when {
                    plan.train.accepts(r) -> {
                        trainRows++
                        trainBy[r.index] = trainBy.getValue(r.index) + 1L
                    }
                    plan.calibration.accepts(r) -> {
                        calibrationRows++
                        calibrationBy[r.index] = calibrationBy.getValue(r.index) + 1L
                    }
                    plan.scoring.accepts(r) -> {
                        scoringRows++
                        scoringBy[r.index] = scoringBy.getValue(r.index) + 1L
                    }
                    plan.test.accepts(r) -> {
                        testRows++
                        testBy[r.index] = testBy.getValue(r.index) + 1L
                    }
                }
                true
            }
        }
        val denominator = windowRows.coerceAtLeast(1L).toDouble()
        return Analysis(
            windowRows = windowRows,
            trainRows = trainRows,
            calibrationRows = calibrationRows,
            scoringRows = scoringRows,
            testRows = testRows,
            trainByMarket = trainBy,
            calibrationByMarket = calibrationBy,
            scoringByMarket = scoringBy,
            testByMarket = testBy,
            coverage = HistoricalCorpusTrainer.Coverage(
                ceSamples = ce.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                peSamples = pe.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                engine1Samples = e1.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                engine2Samples = e2.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                engine3Samples = e3.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                nativeDepthSamples = 0,
            ),
            contracts = contracts.size,
            expiries = expiries.size,
            averageMfe = mfe / denominator,
            averageMae = mae / denominator,
            averageNet = net / denominator,
        )
    }

    fun forEach(
        store: AimlHistoricalOptionCorpusV1Store,
        source: Source,
        stride: Int = 1,
        shouldCancel: () -> Boolean = { false },
        action: (AimlHistoricalOptionCorpusV1Store.Record) -> Unit,
    ): Long {
        var emitted = 0L
        source.splits.forEach { split ->
            emitted += store.forEach(
                split = split,
                stride = stride,
                shouldCancel = shouldCancel,
                accept = { source.accepts(it) },
                action = action,
            )
        }
        return emitted
    }

    fun label(months: Int): String = if (months == FULL) "FULL" else "${months}M"

    private const val TRAIN_PERCENT = 70L
    private const val VALIDATION_PERCENT = 15L
    private const val CALIBRATION_FRACTION = 0.35
    const val EMBARGO_MINUTES = 6L
    const val EMBARGO_MS = EMBARGO_MINUTES * 60_000L
    private const val MIN_WINDOW_MS = 3L * 24L * 60L * 60L * 1000L
    private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
}
