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
 * Joint NIFTY+SENSEX trainer for `aiml-historical-option-row-v1` mixed corpora.
 * TRAIN fits model weights, early VALIDATION calibrates one shared cost-aware policy,
 * later VALIDATION scores robustness/governance, and TEST remains locked until both
 * markets qualify. The same calibrated thresholds must be viable on NIFTY + SENSEX.
 */
class JointPrelabelledHistoricalTrainer(
    private val store: AimlHistoricalOptionCorpusV1Store,
    private val productionBaseline: NumericalMetaBrain.ModelState,
) {
    private data class Sample(
        val index: MarketIndex,
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
        fun add(p: NumericalMetaBrain.Prediction, s: Sample) {
            val y = if (s.success) 1.0 else 0.0
            labels++
            if ((p.probabilitySuccess >= 0.50) == s.success) correct++
            brierSum += (p.probabilitySuccess - y).pow(2)
            when (p.decision) {
                NumericalMetaBrain.Decision.TAKE -> {
                    take++
                    if (s.success) takeWins++
                    takeNet += s.net
                }
                NumericalMetaBrain.Decision.REJECT -> {
                    reject++
                    if (!s.success) rejectLosses++
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
    ): HistoricalCorpusTrainer.Result {
        require(store.ready()) { "Pre-labelled historical corpus is not ready" }
        val meta = store.metadata()
        val trainRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.TRAIN)
        val validationRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION)
        val testRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.TEST)
        val coverage = coverage(meta)
        val corpusSamples = corpusSamples(meta)
        val trainStride = ceil(trainRows.toDouble() / MAX_SEARCH_TRAIN_ROWS).toInt().coerceAtLeast(1)
        val validationStride = ceil(validationRows.toDouble() / MAX_SEARCH_VALIDATION_ROWS).toInt().coerceAtLeast(1)

        var sampledValidationRows = 0L
        val sampledByMarket = MarketIndex.entries.associateWith { 0L }.toMutableMap()
        store.forEach(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION, stride = validationStride, shouldCancel = shouldCancel) { r ->
            sampledValidationRows++
            sampledByMarket[r.index] = (sampledByMarket[r.index] ?: 0L) + 1L
        }
        if (sampledValidationRows < MIN_VALIDATION_ROWS || MarketIndex.entries.any { (sampledByMarket[it] ?: 0L) < MIN_MARKET_VALIDATION_ROWS }) {
            return result(
                meta, monthsLabel, 0, null, false, false, null, null, null,
                "BOTH pre-labelled validation insufficient · total $sampledValidationRows · NIFTY ${sampledByMarket[MarketIndex.NIFTY]} · SENSEX ${sampledByMarket[MarketIndex.SENSEX]} · TEST never opened.",
            )
        }
        val calibrationRows = max(MIN_CALIBRATION_ROWS, (sampledValidationRows * CALIBRATION_FRACTION).toLong())
            .coerceAtMost(sampledValidationRows - MIN_SCORING_ROWS)
        val scoringRows = sampledValidationRows - calibrationRows

        val seeds = candidateHyperParameters()
        val seen = seeds.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }
        val evaluations = ArrayList<JointEvaluation>()

        fun batch(hypers: List<NumericalMetaBrain.HyperParameters>, generation: Int, guidance: HistoricalAdaptiveCandidateSearch.Guidance?) {
            val brains = hypers.map(::brainFromBaseline)
            var trained = 0L
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    if (generation == 0) "JOINT_PRELABELLED_SEED_TRAIN" else "JOINT_PRELABELLED_G${generation}_TRAIN",
                    0,
                    hypers.size,
                    "BOTH · fitting ${hypers.size} models on original TRAIN · stride $trainStride",
                ),
            )
            store.forEach(AimlHistoricalOptionCorpusV1Store.Split.TRAIN, stride = trainStride, shouldCancel = shouldCancel) { r ->
                val s = sample(r)
                brains.forEach { it.learn(s.features, s.success, s.weight) }
                trained++
                if (trained % 25_000L == 0L) {
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "JOINT_PRELABELLED_TRAIN",
                            (trained / 25_000L).toInt(),
                            max(1, (MAX_SEARCH_TRAIN_ROWS / 25_000L).toInt()),
                            "BOTH G$generation · $trained sampled TRAIN rows",
                        ),
                    )
                }
            }

            val overallCalibration = Array(hypers.size) { BinaryTrainingPolicy.StreamingCalibration() }
            val marketCalibration = Array(hypers.size) {
                MarketIndex.entries.associateWith { BinaryTrainingPolicy.StreamingCalibration() }.toMutableMap()
            }
            val calibrated = arrayOfNulls<BinaryTrainingPolicy.CalibrationResult>(hypers.size)
            val prod = brainFromBaseline(productionBaseline.hyperParameters)
            val candidateAcc = Array(hypers.size) { Acc() }
            val candidateMarket = Array(hypers.size) { MarketIndex.entries.associateWith { Acc() }.toMutableMap() }
            val prodAcc = Acc()
            val prodMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
            val foldCandidate = Array(hypers.size) { Array(FOLDS) { Acc() } }
            val foldProd = Array(FOLDS) { Acc() }
            var used = 0L
            var scoringUsed = 0L
            var policyApplied = false

            onProgress(
                HistoricalCorpusTrainer.Progress(
                    if (generation == 0) "JOINT_PRELABELLED_SEED_CALIBRATION" else "JOINT_PRELABELLED_G${generation}_CALIBRATION",
                    0,
                    FOLDS,
                    "BOTH · early VALIDATION calibrates one policy viable overall + NIFTY + SENSEX · later VALIDATION scores it · TEST locked",
                ),
            )
            store.forEach(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION, stride = validationStride, shouldCancel = shouldCancel) { r ->
                val s = sample(r)
                if (used < calibrationRows) {
                    brains.forEachIndexed { i, brain ->
                        val p = brain.predict(s.features)
                        overallCalibration[i].add(p.probabilitySuccess, s.success, s.net)
                        marketCalibration[i].getValue(s.index).add(p.probabilitySuccess, s.success, s.net)
                    }
                    used++
                    return@forEach
                }

                if (!policyApplied) {
                    brains.forEachIndexed { i, brain ->
                        val current = brain.currentHyperParameters()
                        val result = BinaryTrainingPolicy.calibrateJoint(
                            overall = overallCalibration[i],
                            segments = marketCalibration[i].values,
                            fallback = current,
                        )
                        calibrated[i] = result
                        brain.configure(
                            current.copy(takeThreshold = result.takeThreshold, rejectThreshold = result.rejectThreshold),
                            bumpVersion = false,
                        )
                    }
                    policyApplied = true
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "JOINT_PRELABELLED_POLICY_FIXED",
                            1,
                            1,
                            "BOTH shared policy fixed from early VALIDATION before scoring · later VALIDATION/TEST cannot tune it",
                        ),
                    )
                }

                val pp = prod.predict(s.features)
                prodAcc.add(pp, s)
                prodMarket.getValue(s.index).add(pp, s)
                val fold = ((scoringUsed * FOLDS) / max(1L, scoringRows)).toInt().coerceIn(0, FOLDS - 1)
                foldProd[fold].add(pp, s)
                brains.forEachIndexed { i, brain ->
                    val cp = brain.predict(s.features)
                    candidateAcc[i].add(cp, s)
                    candidateMarket[i].getValue(s.index).add(cp, s)
                    foldCandidate[i][fold].add(cp, s)
                }
                scoringUsed++
                used++
            }

            if (!policyApplied) {
                brains.forEachIndexed { i, brain ->
                    val current = brain.currentHyperParameters()
                    val result = BinaryTrainingPolicy.calibrateJoint(overallCalibration[i], marketCalibration[i].values, current)
                    calibrated[i] = result
                    brain.configure(current.copy(takeThreshold = result.takeThreshold, rejectThreshold = result.rejectThreshold), bumpVersion = false)
                }
            }

            val prodMetrics = prodAcc.metrics()
            val prodBy = prodMarket.mapValues { it.value.metrics() }
            hypers.indices.forEach { i ->
                val cm = candidateAcc[i].metrics()
                var foldsRun = 0
                var foldsWon = 0
                repeat(FOLDS) { f ->
                    val c = foldCandidate[i][f].metrics()
                    val p = foldProd[f].metrics()
                    if (c.labels > 0 && p.labels > 0) {
                        foldsRun++
                        if (score(c, p) > 0.0) foldsWon++
                    }
                }
                val policy = calibrated[i]
                val aggregateScore = score(cm, prodMetrics) +
                    0.08 * ((if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun) - 0.50) +
                    0.10 * (policy?.score ?: 0.0)
                val calibratedHyper = if (policy == null) hypers[i] else hypers[i].copy(
                    takeThreshold = policy.takeThreshold,
                    rejectThreshold = policy.rejectThreshold,
                )
                val summary = HistoricalCorpusTrainer.CandidateEvaluation(
                    HistoricalAdaptiveCandidateSearch.bounded(calibratedHyper),
                    foldsRun,
                    foldsWon,
                    cm,
                    prodMetrics,
                    aggregateScore,
                    foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && aggregateScore > 0.0,
                )
                val cBy = candidateMarket[i].mapValues { it.value.metrics() }
                val governance = DualMarketHistoricalGovernance.evaluateDevelopment(cm, prodMetrics, cBy, prodBy, coverage, corpusSamples)
                evaluations += JointEvaluation(summary, cBy, prodBy, governance)
            }
        }

        batch(seeds, 0, null)
        var best = selectBest(evaluations)
        var generation = 0
        while ((best?.summary?.robust != true || best.governance.passed.not()) && generation < HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS) {
            val parent = best ?: break
            generation++
            val guidance = HistoricalAdaptiveCandidateSearch.guidance(parent.summary)
            val next = HistoricalAdaptiveCandidateSearch.nextBatch(parent.summary.hyperParameters, generation, seen, guidance)
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "JOINT_PRELABELLED_EVOLVE",
                    generation,
                    HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS,
                    "BOTH G$generation ${guidance.name} · model evolves; policy is recalibrated on early VALIDATION · TEST remains locked",
                ),
            )
            batch(next.candidates, generation, guidance)
            best = selectBest(evaluations)
        }

        if (best == null || !best.summary.robust || !best.governance.passed) {
            return result(
                meta,
                monthsLabel,
                evaluations.size,
                best?.summary,
                false,
                false,
                null,
                null,
                null,
                "BOTH pre-labelled model + policy search exhausted G$generation · ${best?.governance?.label ?: "CLOSED"}: ${best?.governance?.reasons?.joinToString("; ") ?: "no candidate"} · TRAIN fit + early VALIDATION calibration + later VALIDATION scoring · original TEST never opened.",
            )
        }

        val champion = brainFromBaseline(best.summary.hyperParameters)
        var fullTrain = 0L
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "JOINT_PRELABELLED_REFIT",
                0,
                1,
                "BOTH development-qualified · refitting weights on full original TRAIN · calibrated shared policy frozen",
            ),
        )
        store.forEach(AimlHistoricalOptionCorpusV1Store.Split.TRAIN, shouldCancel = shouldCancel) { r ->
            val s = sample(r)
            champion.learn(s.features, s.success, s.weight)
            fullTrain++
            if (fullTrain % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "JOINT_PRELABELLED_REFIT",
                        fullTrain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "BOTH full TRAIN · $fullTrain/$trainRows · policy fixed ${"%.1f".format(best.summary.hyperParameters.takeThreshold * 100)}%/${"%.1f".format(best.summary.hyperParameters.rejectThreshold * 100)}%",
                    ),
                )
            }
        }

        val prod = brainFromBaseline(productionBaseline.hyperParameters)
        val cAcc = Acc()
        val pAcc = Acc()
        val cMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        val pMarket = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        var testUsed = 0L
        onProgress(HistoricalCorpusTrainer.Progress("JOINT_PRELABELLED_LOCKED_TEST", 0, 1, "BOTH model + shared policy qualified · opening original TEST ONCE"))
        store.forEach(AimlHistoricalOptionCorpusV1Store.Split.TEST, shouldCancel = shouldCancel) { r ->
            val s = sample(r)
            val cp = champion.predict(s.features)
            val pp = prod.predict(s.features)
            cAcc.add(cp, s)
            pAcc.add(pp, s)
            cMarket.getValue(s.index).add(cp, s)
            pMarket.getValue(s.index).add(pp, s)
            testUsed++
            if (testUsed % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "JOINT_PRELABELLED_LOCKED_TEST",
                        testUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "BOTH locked TEST · $testUsed/$testRows",
                    ),
                )
            }
        }
        val cm = cAcc.metrics()
        val pm = pAcc.metrics()
        val cBy = cMarket.mapValues { it.value.metrics() }
        val pBy = pMarket.mapValues { it.value.metrics() }
        val governance = DualMarketHistoricalGovernance.evaluateHoldout(cm, pm, cBy, pBy, coverage, corpusSamples)
        val state = if (governance.passed) champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        val note = "aiml-historical-option-row-v1 · BOTH NIFTY+SENSEX · TRAIN fits weights · early VALIDATION calibrates one shared cost-aware policy viable on both markets · later VALIDATION scores governance · ${seeds.size} seeds + ${evaluations.size - seeds.size} evolved across G$generation · original TEST opened once · governance ${governance.label}: ${governance.reasons.joinToString("; ")} · NIFTY test ${cBy[MarketIndex.NIFTY]?.labels ?: 0} · SENSEX test ${cBy[MarketIndex.SENSEX]?.labels ?: 0}."
        return result(meta, monthsLabel, evaluations.size, best.summary, true, governance.passed, cm, pm, state, note)
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
            engine = engine,
            index = r.index,
            side = side,
            engineConfidence = (72.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 100.0),
            directionScore = (34.0 + 20.0 * abs(orderFlow) + 6.0 * closeness).coerceIn(0.0, 60.0),
            entryQualityScore = (18.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 40.0),
            orderFlow = orderFlow,
            relativeActivity = relative,
            oiImpulse = 0.0,
            optionFlow = (orderFlow * min(relative / 2.0, 1.0)).coerceIn(-1.0, 1.0),
            acceleration = 0.0,
            extensionAtr = abs(r.signedMoneynessSteps).coerceIn(0.0, 6.0),
            depthImbalance = 0.0,
            micropricePressure = 0.0,
            totalBookPressure = 0.0,
            wallPressure = 0.0,
            depthLevels = 0.0,
            minutesFromOpen = (local.hour * 60 + local.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble(),
            recentEngineWinRate = 50.0,
            recentEngineProfitFactor = 1.0,
        )
        val success = AimlHistoricalOptionCorpusV1Store.success5(r)
        val stop = r.mae5 <= -0.075
        val target = r.mfe5 >= 0.10 && !stop
        return Sample(r.index, features, success, if (stop || target) 1.25 else 0.75, AimlHistoricalOptionCorpusV1Store.netReturn5(r))
    }

    private fun selectBest(evals: List<JointEvaluation>): JointEvaluation? {
        if (evals.isEmpty()) return null
        val passed = evals.filter { it.summary.robust && it.governance.passed }
        return (passed.ifEmpty { evals }).maxByOrNull { e ->
            var value = HistoricalAdaptiveCandidateSearch.developmentSelectionScore(e.summary)
            for (m in MarketIndex.entries) {
                val c = e.candidateByMarket[m] ?: HistoricalCorpusTrainer.Metrics()
                val p = e.productionByMarket[m] ?: HistoricalCorpusTrainer.Metrics()
                val required = DualMarketHistoricalGovernance.requiredActions(c.labels).coerceAtLeast(1)
                value += 0.12 * min(c.takeSamples.toDouble() / required, 1.0)
                value += 0.08 * (c.accuracy - p.accuracy) + 0.08 * (p.brier - c.brier)
                if (c.labels < DualMarketHistoricalGovernance.MIN_MARKET_LABELS) value -= 0.20
            }
            if (e.governance.passed) value += 0.30
            value
        }
    }

    private fun score(c: HistoricalCorpusTrainer.Metrics, p: HistoricalCorpusTrainer.Metrics): Double {
        val accuracyGain = c.accuracy - p.accuracy
        val brierGain = p.brier - c.brier
        val reqTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1)
        val reqReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1)
        val takeCoverage = (c.takeSamples.toDouble() / reqTake).coerceIn(0.0, 1.0)
        val rejectCoverage = (c.rejectSamples.toDouble() / reqReject).coerceIn(0.0, 1.0)
        val takeBonus = if (c.takeSamples > 0) c.takePrecision - 0.50 else -0.50
        val rejectBonus = if (c.rejectSamples > 0) c.rejectPrecision - 0.50 else -0.25
        return accuracyGain + brierGain + 0.22 * takeBonus + 0.10 * rejectBonus + 0.35 * c.takeAverageNetReturn + 0.18 * takeCoverage + 0.05 * rejectCoverage - 0.45 * (1.0 - takeCoverage) - 0.10 * (1.0 - rejectCoverage)
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

    private fun coverage(p: java.util.Properties) = HistoricalCorpusTrainer.Coverage(
        p.longToInt("ceRows"), p.longToInt("peRows"), p.longToInt("e1Rows"), p.longToInt("e2Rows"), p.longToInt("e3Rows"), 0,
    )

    private fun corpusSamples(p: java.util.Properties): Int = (p.getProperty("accepted")?.toLongOrNull() ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun java.util.Properties.longToInt(key: String): Int = (getProperty(key)?.toLongOrNull() ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun result(
        p: java.util.Properties,
        months: Long,
        candidates: Int,
        best: HistoricalCorpusTrainer.CandidateEvaluation?,
        opened: Boolean,
        passed: Boolean,
        candidateHoldout: HistoricalCorpusTrainer.Metrics?,
        productionHoldout: HistoricalCorpusTrainer.Metrics?,
        champion: NumericalMetaBrain.ModelState?,
        note: String,
    ) = HistoricalCorpusTrainer.Result(
        index = MarketIndex.NIFTY,
        months = months,
        fromDate = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().minusMonths(12),
        toDate = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        expiries = p.getProperty("expiries")?.toIntOrNull() ?: 0,
        contractsDownloaded = p.getProperty("contracts")?.toIntOrNull() ?: 0,
        corpusSamples = corpusSamples(p),
        coverage = coverage(p),
        averageMfeReturn = p.getProperty("avgMfe5")?.toDoubleOrNull() ?: 0.0,
        averageMaeReturn = p.getProperty("avgMae5")?.toDoubleOrNull() ?: 0.0,
        averageNetReturn = p.getProperty("avgNet5")?.toDoubleOrNull() ?: 0.0,
        candidatesEvaluated = candidates,
        bestWalkForward = best,
        lockedHoldoutOpened = opened,
        lockedHoldoutPassed = passed,
        holdoutCandidate = candidateHoldout,
        holdoutProduction = productionHoldout,
        championState = champion,
        errors = emptyList(),
        nativeHistoricalDepthAvailable = false,
        note = note,
    )

    companion object {
        private const val MAX_SEARCH_TRAIN_ROWS = 300_000L
        private const val MAX_SEARCH_VALIDATION_ROWS = 200_000L
        private const val FOLDS = 4
        private const val CALIBRATION_FRACTION = 0.35
        private const val MIN_CALIBRATION_ROWS = 80L
        private const val MIN_SCORING_ROWS = 120L
        private const val MIN_VALIDATION_ROWS = MIN_CALIBRATION_ROWS + MIN_SCORING_ROWS
        private const val MIN_MARKET_VALIDATION_ROWS = 60L
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
    }
}
