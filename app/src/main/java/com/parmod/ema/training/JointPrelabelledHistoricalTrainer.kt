package com.parmod.ema.training

import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Joint NIFTY+SENSEX pre-labelled research with shared windows and safe resume. */
class JointPrelabelledHistoricalTrainer(
    private val store: AimlHistoricalOptionCorpusV1Store,
    private val productionBaseline: NumericalMetaBrain.ModelState,
) {
    private data class Sample(val index: MarketIndex, val features: NumericalMetaBrain.Features, val success: Boolean, val weight: Double, val net: Double)

    private data class Acc(
        var labels: Long = 0, var correct: Long = 0, var brierSum: Double = 0.0,
        var take: Long = 0, var takeWins: Long = 0, var reject: Long = 0, var rejectLosses: Long = 0, var takeNet: Double = 0.0,
    ) {
        fun add(p: NumericalMetaBrain.Prediction, s: Sample) {
            val y = if (s.success) 1.0 else 0.0; labels++
            if ((p.probabilitySuccess >= 0.50) == s.success) correct++
            brierSum += (p.probabilitySuccess - y).pow(2)
            when (p.decision) {
                NumericalMetaBrain.Decision.TAKE -> { take++; if (s.success) takeWins++; takeNet += s.net }
                NumericalMetaBrain.Decision.REJECT -> { reject++; if (!s.success) rejectLosses++ }
                NumericalMetaBrain.Decision.CAUTION -> Unit
            }
        }
        fun metrics() = HistoricalCorpusTrainer.Metrics(
            labels, if (labels == 0L) 0.0 else correct.toDouble() / labels,
            if (labels == 0L) 1.0 else brierSum / labels,
            take, if (take == 0L) 0.0 else takeWins.toDouble() / take,
            reject, if (reject == 0L) 0.0 else rejectLosses.toDouble() / reject,
            if (take == 0L) 0.0 else takeNet / take,
        )
    }

    private data class JointEvaluation(
        val summary: HistoricalCorpusTrainer.CandidateEvaluation,
        val candidateByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        val productionByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>,
        val governance: DualMarketHistoricalGovernance.Decision,
    )

    fun run(
        monthsLabel: Long = 12,
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
        checkpointStore: HistoricalTrainingCheckpointStore? = null,
    ): HistoricalCorpusTrainer.Result {
        require(store.ready()) { "Pre-labelled historical corpus is not ready" }
        val months = monthsLabel.toInt(); require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val plan = PrelabelledTrainingWindowPlan.build(store, months, MarketIndex.entries.toSet(), shouldCancel)
        onProgress(HistoricalCorpusTrainer.Progress("JOINT_WINDOW_PLAN", 0, 1, "Planning ${plan.label} BOTH chronological window · shared timestamps"))
        val analysis = PrelabelledTrainingWindowPlan.analyze(store, plan, shouldCancel)
        val missingRoleMarket = MarketIndex.entries.firstOrNull { m ->
            analysis.trainByMarket.getValue(m) < MIN_MARKET_TRAIN_ROWS ||
                analysis.calibrationByMarket.getValue(m) < MIN_MARKET_CALIBRATION_ROWS ||
                analysis.scoringByMarket.getValue(m) < MIN_MARKET_SCORING_ROWS ||
                analysis.testByMarket.getValue(m) < MIN_MARKET_TEST_ROWS
        }
        if (analysis.trainRows < MIN_TRAIN_ROWS || analysis.calibrationRows < MIN_CALIBRATION_ROWS || analysis.scoringRows < MIN_SCORING_ROWS || analysis.testRows < MIN_TEST_ROWS || missingRoleMarket != null) {
            return result(monthsLabel, plan, analysis, 0, null, false, false, null, null, null,
                listOf("${plan.label} BOTH evidence insufficient${missingRoleMarket?.let { " · ${it.name} lacks one or more chronological roles" } ?: ""}"),
                "${plan.label} BOTH window blocked before model search · locked TEST never opened.")
        }

        val trainStride = ceil(analysis.trainRows.toDouble() / MAX_SEARCH_TRAIN_ROWS).toInt().coerceAtLeast(1)
        val validationStride = ceil((analysis.calibrationRows + analysis.scoringRows).toDouble() / MAX_SEARCH_VALIDATION_ROWS).toInt().coerceAtLeast(1)
        val sampledCalibration = AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.calibrationRows, validationStride)
        val sampledScoring = AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.scoringRows, validationStride)
        val sampledMarketSafe = MarketIndex.entries.all { m ->
            AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.calibrationByMarket.getValue(m), validationStride) >= MIN_MARKET_SAMPLED_CALIBRATION &&
                AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.scoringByMarket.getValue(m), validationStride) >= MIN_MARKET_SAMPLED_SCORING
        }
        if (sampledCalibration < MIN_CALIBRATION_ROWS || sampledScoring < MIN_SCORING_ROWS || !sampledMarketSafe) {
            return result(monthsLabel, plan, analysis, 0, null, false, false, null, null, null,
                listOf("BOTH sampled development evidence insufficient after stride $validationStride"),
                "${plan.label} BOTH search blocked before fitting; Production unchanged.")
        }

        val checkpointScope = "joint_both"
        val checkpointIdentity = checkpointStore?.identity(store, plan, checkpointScope, productionBaseline)
        val resume = if (checkpointIdentity == null) null else checkpointStore.load(checkpointScope, months, checkpointIdentity)
        val seeds = candidateHyperParameters(); val seen = seeds.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }

        fun batch(hypers: List<NumericalMetaBrain.HyperParameters>, generation: Int, guidance: HistoricalAdaptiveCandidateSearch.Guidance?): List<JointEvaluation> {
            val brains = hypers.map(::brainFromBaseline); val prefix = if (generation == 0) "JOINT_PRELABELLED_SEED" else "JOINT_PRELABELLED_G$generation"
            var trained = 0L
            onProgress(HistoricalCorpusTrainer.Progress("${prefix}_TRAIN", 0, AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.trainRows, trainStride).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "${plan.label} BOTH · ${hypers.size} Candidates · shared chronological TRAIN · deferred features"))
            PrelabelledTrainingWindowPlan.forEach(store, plan.train, trainStride, shouldCancel) { r ->
                val s = sample(r); brains.forEach { it.learn(s.features, s.success, s.weight) }; trained++
                if (trained % PROGRESS_ROWS == 0L) onProgress(HistoricalCorpusTrainer.Progress("${prefix}_TRAIN", trained.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), AimlHistoricalOptionCorpusV1Trainer.sampledCount(analysis.trainRows, trainStride).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH G$generation · $trained sampled TRAIN rows"))
            }

            val overallCalibration = Array(hypers.size) { BinaryTrainingPolicy.StreamingCalibration() }
            val marketCalibration = Array(hypers.size) { MarketIndex.entries.associateWith { BinaryTrainingPolicy.StreamingCalibration() }.toMutableMap() }
            var calibrationUsed = 0L
            PrelabelledTrainingWindowPlan.forEach(store, plan.calibration, validationStride, shouldCancel) { r ->
                val s = sample(r)
                brains.forEachIndexed { i, brain -> val p = brain.predict(s.features); overallCalibration[i].add(p.probabilitySuccess, s.success, s.net); marketCalibration[i].getValue(s.index).add(p.probabilitySuccess, s.success, s.net) }
                calibrationUsed++
                if (calibrationUsed % PROGRESS_ROWS == 0L) onProgress(HistoricalCorpusTrainer.Progress("${prefix}_CALIBRATION", calibrationUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sampledCalibration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH G$generation · calibration $calibrationUsed/$sampledCalibration"))
            }
            val calibrated = arrayOfNulls<BinaryTrainingPolicy.CalibrationResult>(hypers.size)
            brains.forEachIndexed { i, brain ->
                val current = brain.currentHyperParameters(); val policy = BinaryTrainingPolicy.calibrateJoint(overallCalibration[i], marketCalibration[i].values, current)
                calibrated[i] = policy; brain.configure(current.copy(takeThreshold = policy.takeThreshold, rejectThreshold = policy.rejectThreshold), bumpVersion = false)
            }
            onProgress(HistoricalCorpusTrainer.Progress("${prefix}_POLICY_FIXED", 1, 1, "BOTH shared policy frozen · scoring/locked TEST cannot tune thresholds"))

            val production = brainFromBaseline(productionBaseline.hyperParameters)
            val candidateAcc = Array(hypers.size) { Acc() }; val candidateMarket = Array(hypers.size) { MarketIndex.entries.associateWith { Acc() }.toMutableMap() }
            val productionAcc = Acc(); val productionMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
            val foldCandidate = Array(hypers.size) { Array(FOLDS) { Acc() } }; val foldProduction = Array(FOLDS) { Acc() }
            var scoringUsed = 0L
            PrelabelledTrainingWindowPlan.forEach(store, plan.scoring, validationStride, shouldCancel) { r ->
                val s = sample(r); val pp = production.predict(s.features); productionAcc.add(pp, s); productionMarket.getValue(s.index).add(pp, s)
                val fold = AimlHistoricalOptionCorpusV1Trainer.timeFold(r.timestampMs, plan.scoring.fromMs, plan.scoring.toMs, FOLDS); foldProduction[fold].add(pp, s)
                brains.forEachIndexed { i, brain -> val cp = brain.predict(s.features); candidateAcc[i].add(cp, s); candidateMarket[i].getValue(s.index).add(cp, s); foldCandidate[i][fold].add(cp, s) }
                scoringUsed++
                if (scoringUsed % PROGRESS_ROWS == 0L) onProgress(HistoricalCorpusTrainer.Progress("${prefix}_SCORING", scoringUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), sampledScoring.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH G$generation · later scoring $scoringUsed/$sampledScoring · TEST locked"))
            }

            val pm = productionAcc.metrics(); val pBy = productionMarket.mapValues { it.value.metrics() }
            return hypers.indices.map { i ->
                val cm = candidateAcc[i].metrics(); var foldsRun = 0; var foldsWon = 0
                repeat(FOLDS) { f -> val c = foldCandidate[i][f].metrics(); val p = foldProduction[f].metrics(); if (c.labels > 0 && p.labels > 0) { foldsRun++; if (score(c, p) > 0.0) foldsWon++ } }
                val policy = calibrated[i]; val aggregateScore = score(cm, pm) + 0.08 * ((if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun) - 0.50) + 0.10 * (policy?.score ?: 0.0)
                val h = if (policy == null) hypers[i] else hypers[i].copy(takeThreshold = policy.takeThreshold, rejectThreshold = policy.rejectThreshold)
                val summary = HistoricalCorpusTrainer.CandidateEvaluation(HistoricalAdaptiveCandidateSearch.bounded(h), foldsRun, foldsWon, cm, pm, aggregateScore, foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && aggregateScore > 0.0)
                val cBy = candidateMarket[i].mapValues { it.value.metrics() }
                JointEvaluation(summary, cBy, pBy, DualMarketHistoricalGovernance.evaluateDevelopment(cm, pm, cBy, pBy, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            }
        }

        var best: JointEvaluation?
        var generation: Int
        var candidatesEvaluated: Int
        var championFromCheckpoint: NumericalMetaBrain.ModelState? = null
        if (resume != null && resume.candidateByMarket.isNotEmpty() && resume.productionByMarket.isNotEmpty()) {
            seen += resume.seenSignatures
            val governance = DualMarketHistoricalGovernance.evaluateDevelopment(resume.best.candidate, resume.best.production, resume.candidateByMarket, resume.productionByMarket, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            best = JointEvaluation(resume.best, resume.candidateByMarket, resume.productionByMarket, governance)
            generation = resume.generation; candidatesEvaluated = resume.candidatesEvaluated
            championFromCheckpoint = if (resume.stage == HistoricalTrainingCheckpointStore.Stage.REFIT_COMPLETE) resume.champion else null
            onProgress(HistoricalCorpusTrainer.Progress("RESUME_CHECKPOINT", generation, HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS, "Resuming ${plan.label} BOTH from ${resume.stage.name} · G$generation · $candidatesEvaluated Candidates already evaluated"))
        } else {
            val seedResults = batch(seeds, 0, null); candidatesEvaluated = seedResults.size; best = selectBest(seedResults); generation = 0
            best?.let { checkpointStore?.save(HistoricalTrainingCheckpointStore.State(checkpointIdentity!!, checkpointScope, months, HistoricalTrainingCheckpointStore.Stage.SEARCH_GENERATION_COMPLETE, generation, candidatesEvaluated, seen.toList(), it.summary, it.candidateByMarket, it.productionByMarket)) }
        }

        while (championFromCheckpoint == null && (best?.summary?.robust != true || best.governance.passed.not()) && generation < HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS) {
            val parent = best ?: break; generation++
            val guidance = HistoricalAdaptiveCandidateSearch.guidance(parent.summary)
            val next = HistoricalAdaptiveCandidateSearch.nextBatch(parent.summary.hyperParameters, generation, seen, guidance)
            onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_EVOLVE", generation, HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS, "${plan.label} BOTH G$generation ${guidance.name} · development only · TEST untouched"))
            val nextResults = batch(next.candidates, generation, guidance); candidatesEvaluated += nextResults.size
            best = selectBest(listOf(parent) + nextResults)
            best?.let { checkpointStore?.save(HistoricalTrainingCheckpointStore.State(checkpointIdentity!!, checkpointScope, months, HistoricalTrainingCheckpointStore.Stage.SEARCH_GENERATION_COMPLETE, generation, candidatesEvaluated, seen.toList(), it.summary, it.candidateByMarket, it.productionByMarket)) }
        }

        if (best == null || !best.summary.robust || !best.governance.passed) {
            checkpointStore?.clear(checkpointScope, months)
            return result(monthsLabel, plan, analysis, candidatesEvaluated, best?.summary, false, false, null, null, null, emptyList(), "${plan.label} BOTH search exhausted G$generation · ${best?.governance?.label ?: "CLOSED"}: ${best?.governance?.reasons?.joinToString("; ") ?: "no candidate"} · locked TEST never opened.")
        }

        val champion = if (championFromCheckpoint != null) brainFromState(championFromCheckpoint) else {
            val fitted = brainFromBaseline(best.summary.hyperParameters); var fullTrain = 0L
            onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_REFIT", 0, analysis.trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH development-qualified · refitting full safe TRAIN · shared policy frozen"))
            PrelabelledTrainingWindowPlan.forEach(store, plan.train, 1, shouldCancel) { r ->
                val s = sample(r); fitted.learn(s.features, s.success, s.weight); fullTrain++
                if (fullTrain % 100_000L == 0L) onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_REFIT", fullTrain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), analysis.trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH safe TRAIN · $fullTrain/${analysis.trainRows}"))
            }
            checkpointStore?.save(HistoricalTrainingCheckpointStore.State(checkpointIdentity!!, checkpointScope, months, HistoricalTrainingCheckpointStore.Stage.REFIT_COMPLETE, generation, candidatesEvaluated, seen.toList(), best.summary, best.candidateByMarket, best.productionByMarket, fitted.snapshot()))
            fitted
        }

        val production = brainFromBaseline(productionBaseline.hyperParameters); val cAcc = Acc(); val pAcc = Acc()
        val cMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap(); val pMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap(); var testUsed = 0L
        onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_LOCKED_TEST", 0, analysis.testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), if (championFromCheckpoint != null) "Resumed after completed BOTH refit · deterministic locked TEST scoring · no learning" else "Opening shared ${plan.label} chronological TEST ONCE · NIFTY + SENSEX required"))
        PrelabelledTrainingWindowPlan.forEach(store, plan.test, 1, shouldCancel) { r ->
            val s = sample(r); val cp = champion.predict(s.features); val pp = production.predict(s.features)
            cAcc.add(cp, s); pAcc.add(pp, s); cMarket.getValue(s.index).add(cp, s); pMarket.getValue(s.index).add(pp, s); testUsed++
            if (testUsed % 100_000L == 0L) onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_LOCKED_TEST", testUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), analysis.testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), "BOTH locked TEST · $testUsed/${analysis.testRows} · no learning"))
        }
        val cm = cAcc.metrics(); val pm = pAcc.metrics(); val cBy = cMarket.mapValues { it.value.metrics() }; val pBy = pMarket.mapValues { it.value.metrics() }
        val governance = DualMarketHistoricalGovernance.evaluateHoldout(cm, pm, cBy, pBy, analysis.coverage, analysis.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        checkpointStore?.clear(checkpointScope, months)
        val state = if (governance.passed) champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        return result(monthsLabel, plan, analysis, candidatesEvaluated, best.summary, true, governance.passed, cm, pm, state, emptyList(), "${plan.label} ${plan.fromDate}→${plan.toDate} · BOTH shared chronological 70/15/15 roles · ${PrelabelledTrainingWindowPlan.EMBARGO_MINUTES}m label embargo · generation/refit checkpoints contain no TEST metrics · locked TEST performs no learning · governance ${governance.label}: ${governance.reasons.joinToString("; ")} · NIFTY test ${cBy[MarketIndex.NIFTY]?.labels ?: 0} · SENSEX test ${cBy[MarketIndex.SENSEX]?.labels ?: 0}.")
    }

    private fun sample(r: AimlHistoricalOptionCorpusV1Store.Record): Sample {
        val range = max(r.high - r.low, 0.01); val orderFlow = ((r.close - r.open) / range).coerceIn(-1.0, 1.0); val relative = AimlHistoricalOptionCorpusV1Store.relativeActivity(r.volume)
        val engine = when (AimlHistoricalOptionCorpusV1Store.proxyEngine(r)) { 2 -> EngineId.ENGINE_2_AVWAP_LIQUIDITY; 3 -> EngineId.ENGINE_3_V76_SCALPER; else -> EngineId.ENGINE_1_TREND }
        val side = if (r.optionType == "CE") PositionSide.CE else PositionSide.PE; val closeness = (1.0 - abs(r.signedMoneynessSteps) / 5.0).coerceIn(0.0, 1.0); val body = (abs(r.close - r.open) / range).coerceIn(0.0, 1.0); val local = Instant.ofEpochMilli(r.timestampMs).atOffset(IST)
        val features = NumericalMetaBrain.Features(engine, r.index, side, (72.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 100.0), (34.0 + 20.0 * abs(orderFlow) + 6.0 * closeness).coerceIn(0.0, 60.0), (18.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 40.0), orderFlow, relative, 0.0, (orderFlow * min(relative / 2.0, 1.0)).coerceIn(-1.0, 1.0), 0.0, abs(r.signedMoneynessSteps).coerceIn(0.0, 6.0), 0.0, 0.0, 0.0, 0.0, 0.0, (local.hour * 60 + local.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble(), 50.0, 1.0)
        val stop = r.mae5 <= -0.075; val target = r.mfe5 >= 0.10 && !stop
        return Sample(r.index, features, AimlHistoricalOptionCorpusV1Store.success5(r), if (stop || target) 1.25 else 0.75, AimlHistoricalOptionCorpusV1Store.netReturn5(r))
    }

    private fun selectBest(evals: List<JointEvaluation>): JointEvaluation? {
        if (evals.isEmpty()) return null
        val passed = evals.filter { it.summary.robust && it.governance.passed }
        return (passed.ifEmpty { evals }).maxByOrNull { e ->
            var value = HistoricalAdaptiveCandidateSearch.developmentSelectionScore(e.summary)
            for (m in MarketIndex.entries) {
                val c = e.candidateByMarket[m] ?: HistoricalCorpusTrainer.Metrics(); val p = e.productionByMarket[m] ?: HistoricalCorpusTrainer.Metrics(); val required = DualMarketHistoricalGovernance.requiredActions(c.labels).coerceAtLeast(1)
                value += 0.12 * min(c.takeSamples.toDouble() / required, 1.0) + 0.08 * (c.accuracy - p.accuracy) + 0.08 * (p.brier - c.brier)
                if (c.labels < DualMarketHistoricalGovernance.MIN_MARKET_LABELS) value -= 0.20
            }
            if (e.governance.passed) value += 0.30; value
        }
    }

    private fun score(c: HistoricalCorpusTrainer.Metrics, p: HistoricalCorpusTrainer.Metrics): Double {
        val reqTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1); val reqReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1)
        val takeCoverage = (c.takeSamples.toDouble() / reqTake).coerceIn(0.0, 1.0); val rejectCoverage = (c.rejectSamples.toDouble() / reqReject).coerceIn(0.0, 1.0)
        val takeBonus = if (c.takeSamples > 0) c.takePrecision - 0.50 else -0.50; val rejectBonus = if (c.rejectSamples > 0) c.rejectPrecision - 0.50 else -0.25
        return (c.accuracy - p.accuracy) + (p.brier - c.brier) + 0.22 * takeBonus + 0.10 * rejectBonus + 0.35 * c.takeAverageNetReturn + 0.18 * takeCoverage + 0.05 * rejectCoverage - 0.45 * (1.0 - takeCoverage) - 0.10 * (1.0 - rejectCoverage)
    }

    private fun brainFromBaseline(h: NumericalMetaBrain.HyperParameters): NumericalMetaBrain = NumericalMetaBrain().apply { restore(productionBaseline.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = HistoricalAdaptiveCandidateSearch.bounded(h))) }
    private fun brainFromState(s: NumericalMetaBrain.ModelState): NumericalMetaBrain = NumericalMetaBrain().apply { restore(s.copy(mode = NumericalMetaBrain.Mode.SHADOW)) }

    private fun candidateHyperParameters(): List<NumericalMetaBrain.HyperParameters> {
        val all = ArrayList<NumericalMetaBrain.HyperParameters>()
        MetaBrainRuntime.CandidateProfile.entries.map { HistoricalAdaptiveCandidateSearch.bounded(it.hyper) }.forEach { base ->
            all += base; all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(learningRate = base.learningRate * 0.80, l2 = base.l2 * 0.70, takeThreshold = base.takeThreshold + 0.015, rejectThreshold = base.rejectThreshold - 0.015)); all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(learningRate = base.learningRate * 1.20, l2 = base.l2 * 1.35, takeThreshold = base.takeThreshold - 0.015, rejectThreshold = base.rejectThreshold + 0.015))
        }
        return all.distinctBy { HistoricalAdaptiveCandidateSearch.signature(it) }
    }

    private fun result(months: Long, plan: PrelabelledTrainingWindowPlan.Plan, a: PrelabelledTrainingWindowPlan.Analysis, candidates: Int, best: HistoricalCorpusTrainer.CandidateEvaluation?, opened: Boolean, passed: Boolean, candidateHoldout: HistoricalCorpusTrainer.Metrics?, productionHoldout: HistoricalCorpusTrainer.Metrics?, champion: NumericalMetaBrain.ModelState?, errors: List<String>, note: String) = HistoricalCorpusTrainer.Result(
        index = MarketIndex.NIFTY, months = months, fromDate = plan.fromDate, toDate = plan.toDate, expiries = a.expiries, contractsDownloaded = a.contracts, corpusSamples = a.windowRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), coverage = a.coverage,
        averageMfeReturn = a.averageMfe, averageMaeReturn = a.averageMae, averageNetReturn = a.averageNet, candidatesEvaluated = candidates, bestWalkForward = best, lockedHoldoutOpened = opened, lockedHoldoutPassed = passed, holdoutCandidate = candidateHoldout, holdoutProduction = productionHoldout, championState = champion, errors = errors, nativeHistoricalDepthAvailable = false, note = note,
    )

    companion object {
        private const val MAX_SEARCH_TRAIN_ROWS = 300_000L; private const val MAX_SEARCH_VALIDATION_ROWS = 200_000L; private const val FOLDS = 4
        private const val MIN_TRAIN_ROWS = 240L; private const val MIN_CALIBRATION_ROWS = 80L; private const val MIN_SCORING_ROWS = 120L; private const val MIN_TEST_ROWS = 100L
        private const val MIN_MARKET_TRAIN_ROWS = 80L; private const val MIN_MARKET_CALIBRATION_ROWS = 30L; private const val MIN_MARKET_SCORING_ROWS = 40L; private const val MIN_MARKET_TEST_ROWS = 40L
        private const val MIN_MARKET_SAMPLED_CALIBRATION = 10L; private const val MIN_MARKET_SAMPLED_SCORING = 15L; private const val PROGRESS_ROWS = 25_000L
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
    }
}
