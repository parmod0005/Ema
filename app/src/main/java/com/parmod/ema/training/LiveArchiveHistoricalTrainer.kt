package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

/**
 * Chronological refinement/training over preserved live observations and their actual resolved outcomes.
 * The archived 43-value vector is replayed exactly. TRAIN fits weights, early validation calibrates
 * thresholds, later validation scores governance, and locked TEST is opened only after development passes.
 */
class LiveArchiveHistoricalTrainer(private val productionBaseline: NumericalMetaBrain.ModelState) {
    private data class Acc(
        var labels: Long = 0, var correct: Long = 0, var brierSum: Double = 0.0,
        var take: Long = 0, var takeWins: Long = 0, var reject: Long = 0, var rejectLosses: Long = 0, var takeNet: Double = 0.0,
    ) {
        fun add(p: NumericalMetaBrain.Prediction, r: LiveArchiveTrainingStore.Record) {
            val y = if (r.success) 1.0 else 0.0
            labels++
            if ((p.probabilitySuccess >= 0.50) == r.success) correct++
            brierSum += (p.probabilitySuccess - y).pow(2)
            when (p.decision) {
                NumericalMetaBrain.Decision.TAKE -> { take++; if (r.success) takeWins++; takeNet += r.netReturn }
                NumericalMetaBrain.Decision.REJECT -> { reject++; if (!r.success) rejectLosses++ }
                NumericalMetaBrain.Decision.CAUTION -> Unit
            }
        }
        fun metrics() = HistoricalCorpusTrainer.Metrics(
            labels = labels,
            accuracy = if (labels == 0L) 0.0 else correct.toDouble() / labels,
            brier = if (labels == 0L) 1.0 else brierSum / labels,
            takeSamples = take,
            takePrecision = if (take == 0L) 0.0 else takeWins.toDouble() / take,
            rejectSamples = reject,
            rejectPrecision = if (reject == 0L) 0.0 else rejectLosses.toDouble() / reject,
            takeAverageNetReturn = if (take == 0L) 0.0 else takeNet / take,
        )
    }

    fun run(
        records: List<LiveArchiveTrainingStore.Record>,
        scope: HistoricalMarketScope,
        months: Int,
        sourceLabel: String = "LIVE ARCHIVE",
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): HistoricalCorpusTrainer.Result {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val markets = scope.singleIndexOrNull()?.let(::setOf) ?: MarketIndex.entries.toSet()
        val data = records.asSequence().filter { it.index in markets }.sortedBy { it.observationTimestamp }.toList()
        if (data.isEmpty()) return empty(scope, months, "No completed $sourceLabel observations for ${scope.label}")
        if (scope == HistoricalMarketScope.BOTH && MarketIndex.entries.any { m -> data.none { it.index == m } }) {
            return empty(scope, months, "$sourceLabel BOTH requires completed NIFTY and SENSEX observations")
        }

        val from = data.first().observationTimestamp
        val to = data.last().observationTimestamp
        val span = to - from
        if (span < MIN_TIME_SPAN_MS) return empty(scope, months, "$sourceLabel time span is too short for a leakage-resistant split")
        val trainBoundary = from + span * 70L / 100L
        val testBoundary = from + span * 85L / 100L
        val calibrationBoundary = trainBoundary + ((testBoundary - trainBoundary) * 35L / 100L)
        val train = data.filter { it.observationTimestamp < trainBoundary - EMBARGO_MS }
        val calibration = data.filter { it.observationTimestamp >= trainBoundary && it.observationTimestamp < calibrationBoundary - EMBARGO_MS }
        val scoring = data.filter { it.observationTimestamp >= calibrationBoundary && it.observationTimestamp < testBoundary - EMBARGO_MS }
        val test = data.filter { it.observationTimestamp >= testBoundary }
        val coverage = coverage(data)

        val roleError = validateRoleSizes(scope, train, calibration, scoring, test)
        if (roleError != null) return result(scope, months, data, coverage, null, false, false, null, null, null, listOf(roleError), "$sourceLabel locked TEST never opened")

        val balance = BinaryTrainingPolicy.balance(train.map { it.success })
        val candidate = brainFromBaseline(productionBaseline.hyperParameters)
        onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_TRAIN", 0, train.size, "$sourceLabel · fitting ${train.size} exact archived observations · Production frozen"))
        train.forEachIndexed { i, r ->
            if (shouldCancel()) error("Training cancelled")
            candidate.learn(r.features(), r.success, BinaryTrainingPolicy.sampleWeight(r.success, sampleWeight(r), balance))
            if ((i + 1) % PROGRESS_EVERY == 0) onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_TRAIN", i + 1, train.size, "$sourceLabel · fitted ${i + 1}/${train.size}"))
        }

        val overallCalibration = BinaryTrainingPolicy.StreamingCalibration()
        val marketCalibration = MarketIndex.entries.associateWith { BinaryTrainingPolicy.StreamingCalibration() }.toMutableMap()
        onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_CALIBRATION", 0, calibration.size, "$sourceLabel · timestamp-early validation calibrates cost-aware policy · ${EMBARGO_MINUTES}m embargo"))
        calibration.forEachIndexed { i, r ->
            if (shouldCancel()) error("Training cancelled")
            val p = candidate.predict(r.features())
            overallCalibration.add(p.probabilitySuccess, r.success, r.netReturn)
            marketCalibration.getValue(r.index).add(p.probabilitySuccess, r.success, r.netReturn)
            if ((i + 1) % PROGRESS_EVERY == 0) onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_CALIBRATION", i + 1, calibration.size, "$sourceLabel · calibrated ${i + 1}/${calibration.size}"))
        }
        val current = candidate.currentHyperParameters()
        val policy = if (scope == HistoricalMarketScope.BOTH) {
            BinaryTrainingPolicy.calibrateJoint(overallCalibration, MarketIndex.entries.map { marketCalibration.getValue(it) }, current)
        } else BinaryTrainingPolicy.calibrate(overallCalibration, current)
        candidate.configure(current.copy(takeThreshold = policy.takeThreshold, rejectThreshold = policy.rejectThreshold), bumpVersion = false)
        onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_POLICY_FIXED", 1, 1, "$sourceLabel policy frozen · TAKE ${"%.1f".format(policy.takeThreshold * 100)}% / REJECT ${"%.1f".format(policy.rejectThreshold * 100)}% · later validation/TEST cannot tune it"))

        val production = brainFromBaseline(productionBaseline.hyperParameters)
        val cAcc = Acc(); val pAcc = Acc()
        val cMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        val pMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        val foldC = Array(FOLDS) { Acc() }; val foldP = Array(FOLDS) { Acc() }
        onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_SCORING", 0, scoring.size, "$sourceLabel · timestamp-later validation scoring · locked TEST closed"))
        scoring.forEachIndexed { i, r ->
            if (shouldCancel()) error("Training cancelled")
            val f = r.features(); val cp = candidate.predict(f); val pp = production.predict(f)
            cAcc.add(cp, r); pAcc.add(pp, r); cMarket.getValue(r.index).add(cp, r); pMarket.getValue(r.index).add(pp, r)
            val fold = timeFold(r.observationTimestamp, scoring.first().observationTimestamp, scoring.last().observationTimestamp)
            foldC[fold].add(cp, r); foldP[fold].add(pp, r)
            if ((i + 1) % PROGRESS_EVERY == 0) onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_SCORING", i + 1, scoring.size, "$sourceLabel · scored ${i + 1}/${scoring.size}"))
        }
        var foldsRun = 0; var foldsWon = 0
        repeat(FOLDS) { i ->
            val cm = foldC[i].metrics(); val pm = foldP[i].metrics()
            if (cm.labels > 0 && pm.labels > 0) { foldsRun++; if (modelScore(cm, pm) > 0.0) foldsWon++ }
        }
        val cm = cAcc.metrics(); val pm = pAcc.metrics()
        val evaluation = HistoricalCorpusTrainer.CandidateEvaluation(
            hyperParameters = candidate.currentHyperParameters(),
            foldsRun = foldsRun,
            foldsWon = foldsWon,
            candidate = cm,
            production = pm,
            score = modelScore(cm, pm) + 0.10 * policy.score,
            robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && modelScore(cm, pm) > 0.0,
        )
        val devGovernancePassed = if (scope == HistoricalMarketScope.BOTH) {
            DualMarketHistoricalGovernance.evaluateDevelopment(
                cm, pm, cMarket.mapValues { it.value.metrics() }, pMarket.mapValues { it.value.metrics() }, coverage, data.size,
            ).passed
        } else HistoricalCandidateGovernance.evaluateDevelopment(cm, pm, coverage, data.size).passed
        if (!evaluation.robust || !devGovernancePassed) {
            return result(scope, months, data, coverage, evaluation, false, false, null, null, null, emptyList(), "$sourceLabel development governance did not pass · locked TEST remained closed")
        }

        val testC = Acc(); val testP = Acc()
        val testCMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        val testPMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_LOCKED_TEST", 0, test.size, "$sourceLabel development PASS · opening locked TEST once · model/policy frozen"))
        test.forEachIndexed { i, r ->
            if (shouldCancel()) error("Training cancelled")
            val f = r.features(); val cp = candidate.predict(f); val pp = production.predict(f)
            testC.add(cp, r); testP.add(pp, r); testCMarket.getValue(r.index).add(cp, r); testPMarket.getValue(r.index).add(pp, r)
            if ((i + 1) % PROGRESS_EVERY == 0) onProgress(HistoricalCorpusTrainer.Progress("LIVE_ARCHIVE_LOCKED_TEST", i + 1, test.size, "$sourceLabel locked TEST ${i + 1}/${test.size}"))
        }
        val holdC = testC.metrics(); val holdP = testP.metrics()
        val holdPassed = if (scope == HistoricalMarketScope.BOTH) {
            DualMarketHistoricalGovernance.evaluateHoldout(
                holdC, holdP, testCMarket.mapValues { it.value.metrics() }, testPMarket.mapValues { it.value.metrics() }, coverage, data.size,
            ).passed
        } else HistoricalCandidateGovernance.evaluate(holdC, holdP, coverage, data.size, true).passed
        val champion = if (holdPassed) candidate.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        return result(
            scope, months, data, coverage, evaluation, true, holdPassed, holdC, holdP, champion, emptyList(),
            "$sourceLabel · exact archived feature vectors + actual resolved outcomes · duplicate IDs/canonical observations removed · ${EMBARGO_MINUTES}m purge bands · locked TEST opened once · ${if (holdPassed) "governance PASS; Candidate only" else "governance not passed; Production unchanged"}.",
        )
    }

    private fun validateRoleSizes(
        scope: HistoricalMarketScope,
        train: List<LiveArchiveTrainingStore.Record>,
        calibration: List<LiveArchiveTrainingStore.Record>,
        scoring: List<LiveArchiveTrainingStore.Record>,
        test: List<LiveArchiveTrainingStore.Record>,
    ): String? {
        if (train.size < MIN_TRAIN || calibration.size < MIN_CALIBRATION || scoring.size < MIN_SCORING || test.size < MIN_TEST) {
            return "LIVE ARCHIVE evidence insufficient after embargo · train ${train.size} · calibration ${calibration.size} · scoring ${scoring.size} · test ${test.size}"
        }
        if (scope == HistoricalMarketScope.BOTH) {
            for (m in MarketIndex.entries) {
                if (train.count { it.index == m } < MIN_MARKET_TRAIN || calibration.count { it.index == m } < MIN_MARKET_CALIBRATION ||
                    scoring.count { it.index == m } < MIN_MARKET_SCORING || test.count { it.index == m } < MIN_MARKET_TEST) {
                    return "LIVE ARCHIVE BOTH insufficient ${m.name} evidence in one or more chronological roles"
                }
            }
        }
        return null
    }

    private fun sampleWeight(r: LiveArchiveTrainingStore.Record): Double =
        (0.90 + kotlin.math.abs(r.netReturn).coerceAtMost(0.30) * 2.0 + if (kotlin.math.abs(r.mfeReturn) >= 0.10 || kotlin.math.abs(r.maeReturn) >= 0.075) 0.20 else 0.0).coerceIn(0.75, 1.75)

    private fun coverage(records: List<LiveArchiveTrainingStore.Record>) = HistoricalCorpusTrainer.Coverage(
        ceSamples = records.count { it.side == PositionSide.CE },
        peSamples = records.count { it.side == PositionSide.PE },
        engine1Samples = records.count { it.engine == EngineId.ENGINE_1_TREND },
        engine2Samples = records.count { it.engine == EngineId.ENGINE_2_AVWAP_LIQUIDITY },
        engine3Samples = records.count { it.engine == EngineId.ENGINE_3_V76_SCALPER },
        nativeDepthSamples = records.count { it.vector.getOrElse(17) { 0.0 } > 0.0 },
    )

    private fun brainFromBaseline(h: NumericalMetaBrain.HyperParameters) = NumericalMetaBrain().apply {
        restore(productionBaseline.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = h.sanitized()))
    }

    private fun modelScore(c: HistoricalCorpusTrainer.Metrics, p: HistoricalCorpusTrainer.Metrics): Double {
        val take = if (c.takeSamples > 0) c.takePrecision - 0.50 else -0.50
        val reject = if (c.rejectSamples > 0) c.rejectPrecision - 0.50 else -0.25
        return (c.accuracy - p.accuracy) + (p.brier - c.brier) + 0.22 * take + 0.10 * reject + 0.35 * c.takeAverageNetReturn
    }

    private fun timeFold(ts: Long, from: Long, to: Long): Int {
        val span = max(1L, to - from + 1L)
        return (((ts - from).coerceAtLeast(0L) * FOLDS) / span).toInt().coerceIn(0, FOLDS - 1)
    }

    private fun result(
        scope: HistoricalMarketScope,
        months: Int,
        data: List<LiveArchiveTrainingStore.Record>,
        coverage: HistoricalCorpusTrainer.Coverage,
        evaluation: HistoricalCorpusTrainer.CandidateEvaluation?,
        opened: Boolean,
        passed: Boolean,
        holdC: HistoricalCorpusTrainer.Metrics?,
        holdP: HistoricalCorpusTrainer.Metrics?,
        champion: NumericalMetaBrain.ModelState?,
        errors: List<String>,
        note: String,
    ): HistoricalCorpusTrainer.Result {
        val zone = ZoneId.of("Asia/Kolkata")
        return HistoricalCorpusTrainer.Result(
            index = scope.singleIndexOrNull() ?: MarketIndex.NIFTY,
            months = months.toLong(),
            fromDate = Instant.ofEpochMilli(data.first().observationTimestamp).atZone(zone).toLocalDate(),
            toDate = Instant.ofEpochMilli(data.last().observationTimestamp).atZone(zone).toLocalDate(),
            expiries = 0,
            contractsDownloaded = data.map { it.instrumentKey }.filter(String::isNotBlank).distinct().size,
            corpusSamples = data.size,
            coverage = coverage,
            averageMfeReturn = data.map { it.mfeReturn }.average(),
            averageMaeReturn = data.map { it.maeReturn }.average(),
            averageNetReturn = data.map { it.netReturn }.average(),
            candidatesEvaluated = if (evaluation == null) 0 else 1,
            bestWalkForward = evaluation,
            lockedHoldoutOpened = opened,
            lockedHoldoutPassed = passed,
            holdoutCandidate = holdC,
            holdoutProduction = holdP,
            championState = champion,
            errors = errors,
            nativeHistoricalDepthAvailable = coverage.nativeDepthSamples > 0,
            note = note,
        )
    }

    private fun empty(scope: HistoricalMarketScope, months: Int, error: String) = HistoricalCorpusTrainer.Result(
        index = scope.singleIndexOrNull() ?: MarketIndex.NIFTY,
        months = months.toLong(), fromDate = java.time.LocalDate.now(), toDate = java.time.LocalDate.now(), expiries = 0,
        contractsDownloaded = 0, corpusSamples = 0, coverage = HistoricalCorpusTrainer.Coverage(0, 0, 0, 0, 0, 0),
        averageMfeReturn = 0.0, averageMaeReturn = 0.0, averageNetReturn = 0.0, candidatesEvaluated = 0,
        bestWalkForward = null, lockedHoldoutOpened = false, lockedHoldoutPassed = false, holdoutCandidate = null,
        holdoutProduction = null, championState = null, errors = listOf(error), nativeHistoricalDepthAvailable = false,
        note = "LIVE ARCHIVE training blocked safely; Production unchanged.",
    )

    companion object {
        private const val FOLDS = 4
        private const val EMBARGO_MINUTES = 6L
        private const val EMBARGO_MS = EMBARGO_MINUTES * 60_000L
        private const val MIN_TIME_SPAN_MS = 3L * 24L * 60L * 60L * 1000L
        private const val MIN_TRAIN = 120
        private const val MIN_CALIBRATION = 30
        private const val MIN_SCORING = 60
        private const val MIN_TEST = 50
        private const val MIN_MARKET_TRAIN = 50
        private const val MIN_MARKET_CALIBRATION = 15
        private const val MIN_MARKET_SCORING = 30
        private const val MIN_MARKET_TEST = 50
        private const val PROGRESS_EVERY = 1_000
    }
}
