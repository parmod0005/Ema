package com.parmod.ema.training

import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Single-market research over the compact pre-labelled corpus.
 *
 * 1/3/6/12M and FULL are real timestamp windows. Imported split names are storage
 * shards only; a fresh chronological 70/15/15 fit/development/locked-test partition
 * is derived inside the requested window. Five-minute labels are separated by a
 * six-minute embargo and TEST never participates in fitting/calibration/search.
 */
class AimlHistoricalOptionCorpusV1Trainer(
    private val store: AimlHistoricalOptionCorpusV1Store,
    private val productionBaseline: NumericalMetaBrain.ModelState,
) {
    private data class Sample(
        val features: NumericalMetaBrain.Features,
        val success: Boolean,
        val weight: Double,
        val net: Double,
    )

    private data class Acc(
        var labels: Long = 0,
        var correct: Long = 0,
        var brierSum: Double = 0.0,
        var take: Long = 0,
        var takeWins: Long = 0,
        var reject: Long = 0,
        var rejectLosses: Long = 0,
        var takeNet: Double = 0.0,
    ) {
        fun add(prediction: NumericalMetaBrain.Prediction, sample: Sample) {
            val y = if (sample.success) 1.0 else 0.0
            labels++
            if ((prediction.probabilitySuccess >= 0.50) == sample.success) correct++
            brierSum += (prediction.probabilitySuccess - y).pow(2)
            when (prediction.decision) {
                NumericalMetaBrain.Decision.TAKE -> {
                    take++
                    if (sample.success) takeWins++
                    takeNet += sample.net
                }
                NumericalMetaBrain.Decision.REJECT -> {
                    reject++
                    if (!sample.success) rejectLosses++
                }
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
        index: MarketIndex,
        monthsLabel: Long = 12,
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): HistoricalCorpusTrainer.Result {
        require(store.ready()) { "Pre-labelled historical corpus is not ready" }
        val months = monthsLabel.toInt()
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val corpusMarket = store.metadata().getProperty("market", "NIFTY")
        if ((index == MarketIndex.NIFTY && corpusMarket !in setOf("NIFTY", "MIXED")) ||
            (index == MarketIndex.SENSEX && corpusMarket !in setOf("SENSEX", "MIXED"))) {
            return emptyResult(index, monthsLabel, "Imported pre-labelled corpus contains $corpusMarket, not ${index.name}")
        }

        onProgress(HistoricalCorpusTrainer.Progress("WINDOW_PLAN", 0, 1, "Planning ${PrelabelledTrainingWindowPlan.label(months)} ${index.name} chronological window · no indicators computed"))
        val plan = PrelabelledTrainingWindowPlan.build(store, months, setOf(index), shouldCancel)
        val analysis = PrelabelledTrainingWindowPlan.analyze(store, plan, shouldCancel)
        if (analysis.trainRows < MIN_TRAIN_ROWS || analysis.calibrationRows < MIN_CALIBRATION_ROWS ||
            analysis.scoringRows < MIN_SCORING_ROWS || analysis.testRows < MIN_TEST_ROWS) {
            return result(
                index, monthsLabel, plan, analysis, 0, null, false, false, null, null, null,
                listOf("${plan.label} evidence insufficient after chronological embargo · train ${analysis.trainRows} · calibration ${analysis.calibrationRows} · scoring ${analysis.scoringRows} · test ${analysis.testRows}"),
                "${plan.label} window did not meet minimum leakage-resistant role sizes; Production unchanged.",
            )
        }

        val trainStride = ceil(analysis.trainRows.toDouble() / MAX_SEARCH_TRAIN_ROWS).toInt().coerceAtLeast(1)
        val validationStride = ceil((analysis.calibrationRows + analysis.scoringRows).toDouble() / MAX_SEARCH_VALIDATION_ROWS)
            .toInt().coerceAtLeast(1)
        val sampledCalibration = sampledCount(analysis.calibrationRows, validationStride)
        val sampledScoring = sampledCount(analysis.scoringRows, validationStride)
        if (sampledCalibration < MIN_CALIBRATION_ROWS || sampledScoring < MIN_SCORING_ROWS) {
            return result(
                index, monthsLabel, plan, analysis, 0, null, false, false, null, null, null,
                listOf("Sampled validation insufficient · calibration $sampledCalibration · scoring $sampledScoring"),
                "${plan.label} window blocked before model search; Production unchanged.",
            )
        }

        onProgress(
            HistoricalCorpusTrainer.Progress(
                "WINDOW_READY",
                1,
                1,
                "${plan.label} ${plan.fromDate}→${plan.toDate} · train ${analysis.trainRows} · calibration ${analysis.calibrationRows} · scoring ${analysis.scoringRows} · locked test ${analysis.testRows} · ${PrelabelledTrainingWindowPlan.EMBARGO_MINUTES}m embargo",
            ),
        )

        val seedHypers = candidateHyperParameters()
        val seen = seedHypers.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }
        val evaluations = ArrayList<HistoricalCorpusTrainer.CandidateEvaluation>()

        fun trainAndEvaluateBatch(
            hypers: List<NumericalMetaBrain.HyperParameters>,
            generation: Int,
            guidance: HistoricalAdaptiveCandidateSearch.Guidance?,
        ): List<HistoricalCorpusTrainer.CandidateEvaluation> {
            val brains = hypers.map(::brainFromBaseline)
            val prefix = if (generation == 0) "PRELABELLED_SEED" else "PRELABELLED_ADAPT_G$generation"
            var trained = 0L
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "${prefix}_TRAIN",
                    0,
                    max(1, sampledCount(analysis.trainRows, trainStride).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    "${plan.label} · ${hypers.size} Candidates · chronological TRAIN only · stride $trainStride · deferred 43-feature materialization",
                ),
            )
            PrelabelledTrainingWindowPlan.forEach(store, plan.train, trainStride, shouldCancel) { r ->
                val s = sample(r)
                brains.forEach { it.learn(s.features, s.success, s.weight) }
                trained++
                if (trained % PROGRESS_ROWS == 0L) {
                    onProgress(HistoricalCorpusTrainer.Progress("${prefix}_TRAIN", trained.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sampledCount(analysis.trainRows, trainStride).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "G$generation · $trained sampled TRAIN rows · ${hypers.size} Candidates"))
                }
            }

            val calibration = Array(hypers.size) { BinaryTrainingPolicy.StreamingCalibration() }
            var calibrationUsed = 0L
            onProgress(HistoricalCorpusTrainer.Progress("${prefix}_CALIBRATION", 0, sampledCalibration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "Timestamp-early VALIDATION calibrates cost-aware policy · locked TEST untouched"))
            PrelabelledTrainingWindowPlan.forEach(store, plan.calibration, validationStride, shouldCancel) { r ->
                val s = sample(r)
                brains.forEachIndexed { i, brain ->
                    val p = brain.predict(s.features)
                    calibration[i].add(p.probabilitySuccess, s.success, s.net)
                }
                calibrationUsed++
                if (calibrationUsed % PROGRESS_ROWS == 0L) onProgress(HistoricalCorpusTrainer.Progress("${prefix}_CALIBRATION", calibrationUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sampledCalibration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "G$generation · calibration $calibrationUsed/$sampledCalibration"))
            }

            val calibrated = arrayOfNulls<BinaryTrainingPolicy.CalibrationResult>(hypers.size)
            brains.forEachIndexed { i, brain -> calibrated[i] = BinaryTrainingPolicy.applyCalibration(brain, calibration[i]) }
            onProgress(HistoricalCorpusTrainer.Progress("${prefix}_POLICY_FIXED", 1, 1, "Policy frozen before timestamp-later scoring · no threshold changes on scoring/TEST"))

            val candidateAcc = Array(hypers.size) { Acc() }
            val productionAcc = Acc()
            val foldCandidate = Array(hypers.size) { Array(FOLDS) { Acc() } }
            val foldProduction = Array(FOLDS) { Acc() }
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            var scoringUsed = 0L
            PrelabelledTrainingWindowPlan.forEach(store, plan.scoring, validationStride, shouldCancel) { r ->
                val s = sample(r)
                val pp = production.predict(s.features)
                productionAcc.add(pp, s)
                val fold = timeFold(r.timestampMs, plan.scoring.fromMs, plan.scoring.toMs, FOLDS)
                foldProduction[fold].add(pp, s)
                brains.forEachIndexed { i, brain ->
                    val cp = brain.predict(s.features)
                    candidateAcc[i].add(cp, s)
                    foldCandidate[i][fold].add(cp, s)
                }
                scoringUsed++
                if (scoringUsed % PROGRESS_ROWS == 0L) onProgress(HistoricalCorpusTrainer.Progress("${prefix}_SCORING", scoringUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sampledScoring.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "G$generation · later VALIDATION scoring $scoringUsed/$sampledScoring · policy frozen · TEST locked"))
            }

            val prodValidation = productionAcc.metrics()
            return hypers.indices.map { i ->
                val cm = candidateAcc[i].metrics()
                var foldsWon = 0
                var foldsRun = 0
                repeat(FOLDS) { f ->
                    val c = foldCandidate[i][f].metrics()
                    val p = foldProduction[f].metrics()
                    if (c.labels > 0 && p.labels > 0) {
                        foldsRun++
                        if (score(c, p) > 0.0) foldsWon++
                    }
                }
                val aggregateScore = score(cm, prodValidation) +
                    0.08 * ((if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun) - 0.50) +
                    0.10 * (calibrated[i]?.score ?: 0.0)
                val policy = calibrated[i]
                val calibratedHyper = if (policy == null) hypers[i] else hypers[i].copy(takeThreshold = policy.takeThreshold, rejectThreshold = policy.rejectThreshold)
                HistoricalCorpusTrainer.CandidateEvaluation(
                    HistoricalAdaptiveCandidateSearch.bounded(calibratedHyper), foldsRun, foldsWon, cm, prodValidation, aggregateScore,
                    foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && aggregateScore > 0.0,
                )
            }
        }

        evaluations += trainAndEvaluateBatch(seedHypers, 0, null)
        var best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
        var developmentGovernance = best?.let { HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
            ?: HistoricalCandidateGovernance.Decision(HistoricalCandidateGovernance.Status.CLOSED, listOf("No validation Candidate"))
        var generation = 0
        while ((best?.robust != true || !developmentGovernance.passed) && generation < HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS) {
            val parent = best ?: break
            generation++
            val guidance = HistoricalAdaptiveCandidateSearch.guidance(parent)
            val next = HistoricalAdaptiveCandidateSearch.nextBatch(parent.hyperParameters, generation, seen, guidance)
            onProgress(HistoricalCorpusTrainer.Progress("PRELABELLED_EVOLVE", generation, HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS, "${plan.label} G$generation ${guidance.name} · development only · locked TEST untouched"))
            evaluations += trainAndEvaluateBatch(next.candidates, generation, guidance)
            best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
            developmentGovernance = best?.let { HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) } ?: developmentGovernance
        }

        if (best == null || !best.robust || !developmentGovernance.passed) {
            return result(
                index, monthsLabel, plan, analysis, evaluations.size, best, false, false, null, null, null, emptyList(),
                "${plan.label} chronological search exhausted G$generation · ${developmentGovernance.label}: ${developmentGovernance.reasons.joinToString("; ")} · locked TEST never opened.",
            )
        }

        val champion = brainFromBaseline(best.hyperParameters)
        var fullTrain = 0L
        onProgress(HistoricalCorpusTrainer.Progress("PRELABELLED_REFIT", 0, analysis.trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "Development-qualified · refitting on complete safe TRAIN partition · calibrated policy frozen"))
        PrelabelledTrainingWindowPlan.forEach(store, plan.train, 1, shouldCancel) { r ->
            val s = sample(r)
            champion.learn(s.features, s.success, s.weight)
            fullTrain++
            if (fullTrain % 100_000L == 0L) onProgress(HistoricalCorpusTrainer.Progress("PRELABELLED_REFIT", fullTrain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), analysis.trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "Safe TRAIN refit · $fullTrain/${analysis.trainRows}"))
        }

        val candidateTest = Acc()
        val productionTest = Acc()
        val production = brainFromBaseline(productionBaseline.hyperParameters)
        var testUsed = 0L
        onProgress(HistoricalCorpusTrainer.Progress("LOCKED_HOLDOUT", 0, analysis.testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "Opening ${plan.label} locked chronological TEST ONCE · thresholds frozen"))
        PrelabelledTrainingWindowPlan.forEach(store, plan.test, 1, shouldCancel) { r ->
            val s = sample(r)
            candidateTest.add(champion.predict(s.features), s)
            productionTest.add(production.predict(s.features), s)
            testUsed++
            if (testUsed % 100_000L == 0L) onProgress(HistoricalCorpusTrainer.Progress("LOCKED_HOLDOUT", testUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), analysis.testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "Locked TEST · $testUsed/${analysis.testRows}"))
        }
        val cm = candidateTest.metrics()
        val pm = productionTest.metrics()
        val governance = HistoricalCandidateGovernance.evaluate(cm, pm, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), true)
        val state = if (governance.passed) champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        onProgress(HistoricalCorpusTrainer.Progress("COMPLETE", evaluations.size, evaluations.size, when (governance.status) {
            HistoricalCandidateGovernance.Status.PASS -> "${plan.label} locked TEST + governance PASS · champion ready as Candidate only"
            HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "${plan.label} locked TEST insufficient · cycle stops; TEST not reused"
            HistoricalCandidateGovernance.Status.FAIL -> "${plan.label} locked TEST FAIL · cycle stops; TEST not reused"
            HistoricalCandidateGovernance.Status.CLOSED -> "Locked TEST stayed closed"
        }))
        return result(
            index, monthsLabel, plan, analysis, evaluations.size, best, true, governance.passed, cm, pm, state, emptyList(),
            "${plan.label} ${plan.fromDate}→${plan.toDate} · derived chronological 70/15/15 roles across all storage shards · ${PrelabelledTrainingWindowPlan.EMBARGO_MINUTES}m label embargo · cost-aware calibration · locked TEST opened once · deferred causal materialization · Governance ${governance.label}: ${governance.reasons.joinToString("; ")}.",
        )
    }

    private fun sample(r: AimlHistoricalOptionCorpusV1Store.Record): Sample {
        val range = max(r.high - r.low, 0.01)
        val orderFlow = ((r.close - r.open) / range).coerceIn(-1.0, 1.0)
        val relative = AimlHistoricalOptionCorpusV1Store.relativeActivity(r.volume)
        val engine = when (AimlHistoricalOptionCorpusV1Store.proxyEngine(r)) {
            2 -> EngineId.ENGINE_2_AVWAP_LIQUIDITY
            3 -> EngineId.ENGINE_3_V76_SCALPER
            else -> EngineId.ENGINE_1_TREND
        }
        val side = if (r.optionType == "CE") PositionSide.CE else PositionSide.PE
        val closeness = (1.0 - abs(r.signedMoneynessSteps) / 5.0).coerceIn(0.0, 1.0)
        val body = (abs(r.close - r.open) / range).coerceIn(0.0, 1.0)
        val local = Instant.ofEpochMilli(r.timestampMs).atOffset(IST)
        val features = NumericalMetaBrain.Features(
            engine, r.index, side,
            (72.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 100.0),
            (34.0 + 20.0 * abs(orderFlow) + 6.0 * closeness).coerceIn(0.0, 60.0),
            (18.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 40.0),
            orderFlow, relative, 0.0, (orderFlow * min(relative / 2.0, 1.0)).coerceIn(-1.0, 1.0), 0.0,
            abs(r.signedMoneynessSteps).coerceIn(0.0, 6.0), 0.0, 0.0, 0.0, 0.0, 0.0,
            (local.hour * 60 + local.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble(), 50.0, 1.0,
        )
        val stop = r.mae5 <= -0.075
        val target = r.mfe5 >= 0.10 && !stop
        return Sample(features, AimlHistoricalOptionCorpusV1Store.success5(r), if (stop || target) 1.25 else 0.75, AimlHistoricalOptionCorpusV1Store.netReturn5(r))
    }

    private fun score(c: HistoricalCorpusTrainer.Metrics, p: HistoricalCorpusTrainer.Metrics): Double {
        val requiredTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val requiredReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val takeCoverage = (c.takeSamples.toDouble() / requiredTake).coerceIn(0.0, 1.0)
        val rejectCoverage = (c.rejectSamples.toDouble() / requiredReject).coerceIn(0.0, 1.0)
        val takeBonus = if (c.takeSamples > 0) c.takePrecision - 0.50 else -0.50
        val rejectBonus = if (c.rejectSamples > 0) c.rejectPrecision - 0.50 else -0.25
        return (c.accuracy - p.accuracy) + (p.brier - c.brier) + 0.22 * takeBonus + 0.10 * rejectBonus +
            0.35 * c.takeAverageNetReturn + 0.18 * takeCoverage + 0.05 * rejectCoverage - 0.45 * (1.0 - takeCoverage) - 0.10 * (1.0 - rejectCoverage)
    }

    private fun brainFromBaseline(h: NumericalMetaBrain.HyperParameters): NumericalMetaBrain = NumericalMetaBrain().apply {
        restore(productionBaseline.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = HistoricalAdaptiveCandidateSearch.bounded(h)))
    }

    private fun candidateHyperParameters(): List<NumericalMetaBrain.HyperParameters> {
        val all = ArrayList<NumericalMetaBrain.HyperParameters>()
        MetaBrainRuntime.CandidateProfile.entries.map { HistoricalAdaptiveCandidateSearch.bounded(it.hyper) }.forEach { base ->
            all += base
            all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(learningRate = base.learningRate * 0.80, l2 = base.l2 * 0.70, takeThreshold = base.takeThreshold + 0.015, rejectThreshold = base.rejectThreshold - 0.015))
            all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(learningRate = base.learningRate * 1.20, l2 = base.l2 * 1.35, takeThreshold = base.takeThreshold - 0.015, rejectThreshold = base.rejectThreshold + 0.015))
        }
        return all.distinctBy { HistoricalAdaptiveCandidateSearch.signature(it) }
    }

    private fun result(
        index: MarketIndex,
        months: Long,
        plan: PrelabelledTrainingWindowPlan.Plan,
        a: PrelabelledTrainingWindowPlan.Analysis,
        candidates: Int,
        best: HistoricalCorpusTrainer.CandidateEvaluation?,
        opened: Boolean,
        passed: Boolean,
        candidateHoldout: HistoricalCorpusTrainer.Metrics?,
        productionHoldout: HistoricalCorpusTrainer.Metrics?,
        champion: NumericalMetaBrain.ModelState?,
        errors: List<String>,
        note: String,
    ) = HistoricalCorpusTrainer.Result(
        index = index, months = months, fromDate = plan.fromDate, toDate = plan.toDate,
        expiries = a.expiries, contractsDownloaded = a.contracts,
        corpusSamples = a.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), coverage = a.coverage,
        averageMfeReturn = a.averageMfe, averageMaeReturn = a.averageMae, averageNetReturn = a.averageNet,
        candidatesEvaluated = candidates, bestWalkForward = best, lockedHoldoutOpened = opened, lockedHoldoutPassed = passed,
        holdoutCandidate = candidateHoldout, holdoutProduction = productionHoldout, championState = champion,
        errors = errors, nativeHistoricalDepthAvailable = false, note = note,
    )

    private fun emptyResult(index: MarketIndex, months: Long, error: String) = HistoricalCorpusTrainer.Result(
        index, months, LocalDate.now().minusMonths(if (months <= 0) 12 else months), LocalDate.now(), 0, 0, 0,
        HistoricalCorpusTrainer.Coverage(0, 0, 0, 0, 0, 0), 0.0, 0.0, 0.0, 0, null, false, false, null, null, null,
        listOf(error), false, "Pre-labelled corpus not compatible with selected market/window.",
    )

    companion object {
        private const val MAX_SEARCH_TRAIN_ROWS = 300_000L
        private const val MAX_SEARCH_VALIDATION_ROWS = 200_000L
        private const val FOLDS = 4
        private const val MIN_TRAIN_ROWS = 100L
        private const val MIN_CALIBRATION_ROWS = 40L
        private const val MIN_SCORING_ROWS = 80L
        private const val MIN_TEST_ROWS = 50L
        private const val PROGRESS_ROWS = 25_000L
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

        internal fun sampledCount(rows: Long, stride: Int): Long = if (rows <= 0L) 0L else (rows + stride - 1L) / stride
        internal fun timeFold(timestampMs: Long, startMs: Long, endMs: Long, folds: Int): Int {
            if (folds <= 1 || endMs <= startMs) return 0
            val span = (endMs - startMs + 1L).coerceAtLeast(1L)
            val offset = (timestampMs - startMs).coerceIn(0L, span - 1L)
            return ((offset * folds) / span).toInt().coerceIn(0, folds - 1)
        }
    }
}
