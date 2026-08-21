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
 * Candidate research over the leakage-resistant pre-labelled option corpus.
 *
 * TRAIN fits weights. A timestamp-early VALIDATION slice calibrates policy, an
 * embargo separates it from a later timestamp scoring slice, and TEST remains
 * locked until development qualifies. Physical file order is never used as a proxy
 * for chronology because imported compact files can be contract-grouped.
 */
class AimlHistoricalOptionCorpusV1Trainer(
    private val store: AimlHistoricalOptionCorpusV1Store,
    private val productionBaseline: NumericalMetaBrain.ModelState,
) {
    private data class Sample(
        val features: NumericalMetaBrain.Features,
        val success: Boolean,
        val weight: Double,
        val mfe: Double,
        val mae: Double,
        val net: Double,
        val side: PositionSide,
        val engine: EngineId,
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
        val meta = store.metadata()
        val corpusMarket = meta.getProperty("market", "NIFTY")
        if ((index == MarketIndex.NIFTY && corpusMarket != "NIFTY" && corpusMarket != "MIXED") ||
            (index == MarketIndex.SENSEX && corpusMarket != "SENSEX" && corpusMarket != "MIXED")) {
            return emptyResult(index, monthsLabel, "Imported pre-labelled corpus contains $corpusMarket, not ${index.name}")
        }

        val marketAccept: (AimlHistoricalOptionCorpusV1Store.Record) -> Boolean = { it.index == index }
        var trainMin = Long.MAX_VALUE
        var trainMax = Long.MIN_VALUE
        val selectedTrainRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.TRAIN, shouldCancel) { r ->
            if (r.index != index) false else {
                trainMin = minOf(trainMin, r.timestampMs)
                trainMax = maxOf(trainMax, r.timestampMs)
                true
            }
        }
        var validationMin = Long.MAX_VALUE
        var validationMax = Long.MIN_VALUE
        val selectedValidationRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION, shouldCancel) { r ->
            if (r.index != index) false else {
                validationMin = minOf(validationMin, r.timestampMs)
                validationMax = maxOf(validationMax, r.timestampMs)
                true
            }
        }
        val selectedTestRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.TEST, shouldCancel, marketAccept)
        if (selectedTrainRows <= 0L || selectedValidationRows <= 0L || selectedTestRows <= 0L ||
            trainMax == Long.MIN_VALUE || validationMax == Long.MIN_VALUE) {
            return emptyResult(index, monthsLabel, "Selected market does not have complete TRAIN/VALIDATION/TEST evidence")
        }

        // Purge the tail of TRAIN because a 5m label on the last training bars can
        // inspect prices that belong to VALIDATION. Keep one extra minute of safety.
        val safeTrainEndMs = trainMax - EMBARGO_MS
        val safeTrainAccept: (AimlHistoricalOptionCorpusV1Store.Record) -> Boolean = {
            it.index == index && it.timestampMs <= safeTrainEndMs
        }
        val safeTrainRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.TRAIN, shouldCancel, safeTrainAccept)

        // Build time-based calibration/scoring windows. Never split VALIDATION by its
        // physical row order because the compact corpus can be contract-grouped.
        val validationSpan = (validationMax - validationMin).coerceAtLeast(1L)
        val calibrationEndMs = validationMin + (validationSpan * CALIBRATION_FRACTION).toLong()
        val scoringStartMs = calibrationEndMs + EMBARGO_MS
        val safeValidationEndMs = validationMax - EMBARGO_MS
        val calibrationAccept: (AimlHistoricalOptionCorpusV1Store.Record) -> Boolean = {
            it.index == index && it.timestampMs <= calibrationEndMs
        }
        val scoringAccept: (AimlHistoricalOptionCorpusV1Store.Record) -> Boolean = {
            it.index == index && it.timestampMs >= scoringStartMs && it.timestampMs <= safeValidationEndMs
        }
        val calibrationPhysicalRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION, shouldCancel, calibrationAccept)
        val scoringPhysicalRows = store.count(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION, shouldCancel, scoringAccept)

        val trainStride = ceil(safeTrainRows.toDouble() / MAX_SEARCH_TRAIN_ROWS).toInt().coerceAtLeast(1)
        val validationStride = ceil((calibrationPhysicalRows + scoringPhysicalRows).toDouble() / MAX_SEARCH_VALIDATION_ROWS)
            .toInt().coerceAtLeast(1)
        val sampledCalibrationRows = sampledCount(calibrationPhysicalRows, validationStride)
        val sampledScoringRows = sampledCount(scoringPhysicalRows, validationStride)
        if (sampledCalibrationRows < MIN_CALIBRATION_ROWS || sampledScoringRows < MIN_SCORING_ROWS) {
            return emptyResult(
                index,
                monthsLabel,
                "Validation insufficient after time-causal ${EMBARGO_MINUTES}m embargo · calibration $sampledCalibrationRows · scoring $sampledScoringRows",
            )
        }

        val coverage = coverage(meta)
        val corpusSamples = corpusSamples(meta)
        val seedHypers = candidateHyperParameters()
        val seen = seedHypers.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }
        val evaluations = ArrayList<HistoricalCorpusTrainer.CandidateEvaluation>()

        fun trainAndEvaluateBatch(
            hypers: List<NumericalMetaBrain.HyperParameters>,
            generation: Int,
            guidance: HistoricalAdaptiveCandidateSearch.Guidance?,
        ): List<HistoricalCorpusTrainer.CandidateEvaluation> {
            val brains = hypers.map { brainFromBaseline(it) }
            val stagePrefix = if (generation == 0) "PRELABELLED_SEED" else "PRELABELLED_ADAPT_G$generation"
            var searchTrainUsed = 0L
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "${stagePrefix}_TRAIN",
                    0,
                    hypers.size,
                    if (generation == 0) {
                        "Training ${hypers.size} seed Candidates · safe TRAIN $safeTrainRows rows · stride $trainStride · deferred 43-feature materialization"
                    } else {
                        "Adaptive G$generation ${guidance?.name ?: "BALANCED"} · ${hypers.size} Candidates · safe TRAIN only · TEST untouched"
                    },
                ),
            )
            store.forEach(
                split = AimlHistoricalOptionCorpusV1Store.Split.TRAIN,
                stride = trainStride,
                shouldCancel = shouldCancel,
                accept = safeTrainAccept,
            ) { record ->
                val s = sample(record)
                brains.forEach { it.learn(s.features, s.success, s.weight) }
                searchTrainUsed++
                if (searchTrainUsed % 25_000L == 0L) {
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "${stagePrefix}_TRAIN",
                            (searchTrainUsed / 25_000L).toInt(),
                            max(1, (MAX_SEARCH_TRAIN_ROWS / 25_000L).toInt()),
                            "G$generation · $searchTrainUsed sampled TRAIN rows · ${hypers.size} Candidates",
                        ),
                    )
                }
            }

            val calibration = Array(hypers.size) { BinaryTrainingPolicy.StreamingCalibration() }
            val candidateAcc = Array(hypers.size) { Acc() }
            val productionAcc = Acc()
            val foldCandidate = Array(hypers.size) { Array(FOLDS) { Acc() } }
            val foldProduction = Array(FOLDS) { Acc() }
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            val calibrated = arrayOfNulls<BinaryTrainingPolicy.CalibrationResult>(hypers.size)
            var calibrationUsed = 0L
            var scoringUsed = 0L

            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "${stagePrefix}_CALIBRATION",
                    0,
                    sampledCalibrationRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    "Timestamp-early VALIDATION calibration · $sampledCalibrationRows sampled rows · ${EMBARGO_MINUTES}m embargo before scoring · TEST locked",
                ),
            )
            store.forEach(
                split = AimlHistoricalOptionCorpusV1Store.Split.VALIDATION,
                stride = validationStride,
                shouldCancel = shouldCancel,
                accept = calibrationAccept,
            ) { record ->
                val s = sample(record)
                brains.forEachIndexed { i, brain ->
                    val p = brain.predict(s.features)
                    calibration[i].add(p.probabilitySuccess, s.success, s.net)
                }
                calibrationUsed++
                if (calibrationUsed % 25_000L == 0L) {
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "${stagePrefix}_CALIBRATION",
                            calibrationUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            sampledCalibrationRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            "G$generation · calibration $calibrationUsed/$sampledCalibrationRows",
                        ),
                    )
                }
            }

            brains.forEachIndexed { i, brain ->
                calibrated[i] = BinaryTrainingPolicy.applyCalibration(brain, calibration[i])
            }
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "${stagePrefix}_POLICY_FIXED",
                    1,
                    1,
                    "Development policy frozen from timestamp-early VALIDATION · later scoring/TEST cannot tune thresholds",
                ),
            )

            store.forEach(
                split = AimlHistoricalOptionCorpusV1Store.Split.VALIDATION,
                stride = validationStride,
                shouldCancel = shouldCancel,
                accept = scoringAccept,
            ) { record ->
                val s = sample(record)
                val pp = production.predict(s.features)
                productionAcc.add(pp, s)
                val fold = timeFold(record.timestampMs, scoringStartMs, safeValidationEndMs, FOLDS)
                foldProduction[fold].add(pp, s)
                brains.forEachIndexed { i, brain ->
                    val cp = brain.predict(s.features)
                    candidateAcc[i].add(cp, s)
                    foldCandidate[i][fold].add(cp, s)
                }
                scoringUsed++
                if (scoringUsed % 25_000L == 0L) {
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "${stagePrefix}_SCORING",
                            scoringUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            sampledScoringRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            "G$generation · timestamp-later VALIDATION scoring $scoringUsed/$sampledScoringRows · policy frozen · TEST locked",
                        ),
                    )
                }
            }

            val prodValidation = productionAcc.metrics()
            return hypers.indices.map { i ->
                val cm = candidateAcc[i].metrics()
                var foldsWon = 0
                var foldsRun = 0
                repeat(FOLDS) { fold ->
                    val c = foldCandidate[i][fold].metrics()
                    val p = foldProduction[fold].metrics()
                    if (c.labels > 0 && p.labels > 0) {
                        foldsRun++
                        if (score(c, p) > 0.0) foldsWon++
                    }
                }
                val aggregateScore = score(cm, prodValidation) +
                    0.08 * ((if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun) - 0.50) +
                    0.10 * (calibrated[i]?.score ?: 0.0)
                val policy = calibrated[i]
                val calibratedHyper = if (policy == null) hypers[i] else hypers[i].copy(
                    takeThreshold = policy.takeThreshold,
                    rejectThreshold = policy.rejectThreshold,
                )
                HistoricalCorpusTrainer.CandidateEvaluation(
                    hyperParameters = HistoricalAdaptiveCandidateSearch.bounded(calibratedHyper),
                    foldsRun = foldsRun,
                    foldsWon = foldsWon,
                    candidate = cm,
                    production = prodValidation,
                    score = aggregateScore,
                    robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && aggregateScore > 0.0,
                )
            }
        }

        evaluations += trainAndEvaluateBatch(seedHypers, 0, null)
        var best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
        var developmentGovernance = best?.let {
            HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, coverage, corpusSamples)
        } ?: HistoricalCandidateGovernance.Decision(HistoricalCandidateGovernance.Status.CLOSED, listOf("No validation Candidate"))
        var adaptiveGenerations = 0

        while (
            (best?.robust != true || !developmentGovernance.passed) &&
            adaptiveGenerations < HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS
        ) {
            val parent = best ?: break
            adaptiveGenerations++
            val guidance = HistoricalAdaptiveCandidateSearch.guidance(parent)
            val batch = HistoricalAdaptiveCandidateSearch.nextBatch(
                parent = parent.hyperParameters,
                generation = adaptiveGenerations,
                seenSignatures = seen,
                guidance = guidance,
            )
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "PRELABELLED_EVOLVE",
                    adaptiveGenerations,
                    HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS,
                    "G$adaptiveGenerations ${guidance.name} · LR/L2 evolve · policy recalibrates only on timestamp-early VALIDATION · TEST unchanged",
                ),
            )
            evaluations += trainAndEvaluateBatch(batch.candidates, adaptiveGenerations, guidance)
            best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
            developmentGovernance = best?.let {
                HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, coverage, corpusSamples)
            } ?: developmentGovernance
        }

        val developmentQualified = best?.robust == true && developmentGovernance.passed
        if (!developmentQualified || best == null) {
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "COMPLETE",
                    evaluations.size,
                    evaluations.size,
                    "Adaptive model + policy search exhausted G$adaptiveGenerations · ${developmentGovernance.label} · original TEST stayed locked",
                ),
            )
            return result(
                index = index,
                months = monthsLabel,
                p = meta,
                candidates = evaluations.size,
                best = best,
                opened = false,
                passed = false,
                candidateHoldout = null,
                productionHoldout = null,
                champion = null,
                note = "aiml-historical-option-row-v1 · time-causal TRAIN→calibration→embargo→scoring · TEST never opened · Development ${developmentGovernance.label}: ${developmentGovernance.reasons.joinToString("; ")}.",
            )
        }

        val champion = brainFromBaseline(best.hyperParameters)
        var fullTrain = 0L
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "PRELABELLED_REFIT",
                0,
                safeTrainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "Development-qualified after G$adaptiveGenerations · refitting on safe TRAIN only · policy frozen",
            ),
        )
        store.forEach(
            AimlHistoricalOptionCorpusV1Store.Split.TRAIN,
            shouldCancel = shouldCancel,
            accept = safeTrainAccept,
        ) { record ->
            val s = sample(record)
            champion.learn(s.features, s.success, s.weight)
            fullTrain++
            if (fullTrain % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "PRELABELLED_REFIT",
                        fullTrain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        safeTrainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "Safe TRAIN refit · $fullTrain/$safeTrainRows · policy fixed ${"%.1f".format(best.hyperParameters.takeThreshold * 100)}%/${"%.1f".format(best.hyperParameters.rejectThreshold * 100)}%",
                    ),
                )
            }
        }

        val candidateTest = Acc()
        val productionTest = Acc()
        val production = brainFromBaseline(productionBaseline.hyperParameters)
        var testUsed = 0L
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "LOCKED_HOLDOUT",
                0,
                selectedTestRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "Development-qualified model + frozen policy · opening original TEST ONCE",
            ),
        )
        store.forEach(
            AimlHistoricalOptionCorpusV1Store.Split.TEST,
            shouldCancel = shouldCancel,
            accept = marketAccept,
        ) { record ->
            val s = sample(record)
            candidateTest.add(champion.predict(s.features), s)
            productionTest.add(production.predict(s.features), s)
            testUsed++
            if (testUsed % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "LOCKED_HOLDOUT",
                        testUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        selectedTestRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "Locked TEST · $testUsed/$selectedTestRows · policy frozen",
                    ),
                )
            }
        }
        val candidateMetrics = candidateTest.metrics()
        val productionMetrics = productionTest.metrics()
        val governance = HistoricalCandidateGovernance.evaluate(
            candidate = candidateMetrics,
            production = productionMetrics,
            coverage = coverage,
            corpusSamples = corpusSamples,
            holdoutOpened = true,
        )
        val passed = governance.passed
        val state = if (passed) champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "COMPLETE",
                evaluations.size,
                evaluations.size,
                when (governance.status) {
                    HistoricalCandidateGovernance.Status.PASS -> "Original locked TEST + governance + time-leakage guard PASS · champion ready as Candidate only"
                    HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "Original locked TEST INSUFFICIENT DATA · cycle stops; TEST not reused"
                    HistoricalCandidateGovernance.Status.FAIL -> "Original locked TEST FAIL · cycle stops; TEST not reused"
                    HistoricalCandidateGovernance.Status.CLOSED -> "Original locked TEST stayed closed"
                },
            ),
        )
        return result(
            index = index,
            months = monthsLabel,
            p = meta,
            candidates = evaluations.size,
            best = best,
            opened = true,
            passed = passed,
            candidateHoldout = candidateMetrics,
            productionHoldout = productionMetrics,
            champion = state,
            note = "aiml-historical-option-row-v1 · safe TRAIN tail purge · timestamp-early VALIDATION calibration · ${EMBARGO_MINUTES}m embargo · timestamp-later scoring · original TEST opened once only · deferred causal feature materialization · 5m cost-aware labels · Governance ${governance.label}: ${governance.reasons.joinToString("; ")}.",
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
        val quality = (18.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 40.0)
        val confidence = (72.0 + 12.0 * closeness + 10.0 * body).coerceIn(0.0, 100.0)
        val direction = (34.0 + 20.0 * abs(orderFlow) + 6.0 * closeness).coerceIn(0.0, 60.0)
        val local = Instant.ofEpochMilli(r.timestampMs).atOffset(IST)
        val minutes = (local.hour * 60 + local.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble()
        val features = NumericalMetaBrain.Features(
            engine = engine,
            index = r.index,
            side = side,
            engineConfidence = confidence,
            directionScore = direction,
            entryQualityScore = quality,
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
            minutesFromOpen = minutes,
            recentEngineWinRate = 50.0,
            recentEngineProfitFactor = 1.0,
        )
        val success = AimlHistoricalOptionCorpusV1Store.success5(r)
        val stop = r.mae5 <= -0.075
        val target = r.mfe5 >= 0.10 && !stop
        return Sample(
            features = features,
            success = success,
            weight = if (stop || target) 1.25 else 0.75,
            mfe = r.mfe5,
            mae = r.mae5,
            net = AimlHistoricalOptionCorpusV1Store.netReturn5(r),
            side = side,
            engine = engine,
        )
    }

    private fun score(candidate: HistoricalCorpusTrainer.Metrics, production: HistoricalCorpusTrainer.Metrics): Double {
        val accuracyGain = candidate.accuracy - production.accuracy
        val brierGain = production.brier - candidate.brier
        val requiredTake = HistoricalCandidateGovernance.requiredActionSamples(candidate.labels).coerceAtLeast(1L)
        val requiredReject = HistoricalCandidateGovernance.requiredActionSamples(candidate.labels).coerceAtLeast(1L)
        val takeCoverage = (candidate.takeSamples.toDouble() / requiredTake).coerceIn(0.0, 1.0)
        val rejectCoverage = (candidate.rejectSamples.toDouble() / requiredReject).coerceIn(0.0, 1.0)
        val takeBonus = if (candidate.takeSamples > 0) candidate.takePrecision - 0.50 else -0.50
        val rejectBonus = if (candidate.rejectSamples > 0) candidate.rejectPrecision - 0.50 else -0.25
        val actionCoverage = 0.18 * takeCoverage + 0.05 * rejectCoverage
        val starvationPenalty = 0.45 * (1.0 - takeCoverage) + 0.10 * (1.0 - rejectCoverage)
        return accuracyGain + brierGain + 0.22 * takeBonus + 0.10 * rejectBonus + 0.35 * candidate.takeAverageNetReturn + actionCoverage - starvationPenalty
    }

    private fun brainFromBaseline(hyper: NumericalMetaBrain.HyperParameters): NumericalMetaBrain = NumericalMetaBrain().apply {
        restore(productionBaseline.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = HistoricalAdaptiveCandidateSearch.bounded(hyper)))
    }

    private fun candidateHyperParameters(): List<NumericalMetaBrain.HyperParameters> {
        val all = ArrayList<NumericalMetaBrain.HyperParameters>()
        MetaBrainRuntime.CandidateProfile.entries.map { HistoricalAdaptiveCandidateSearch.bounded(it.hyper) }.forEach { base ->
            all += base
            all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(
                learningRate = base.learningRate * 0.80,
                l2 = base.l2 * 0.70,
                takeThreshold = base.takeThreshold + 0.015,
                rejectThreshold = base.rejectThreshold - 0.015,
            ))
            all += HistoricalAdaptiveCandidateSearch.bounded(base.copy(
                learningRate = base.learningRate * 1.20,
                l2 = base.l2 * 1.35,
                takeThreshold = base.takeThreshold - 0.015,
                rejectThreshold = base.rejectThreshold + 0.015,
            ))
        }
        return all.distinctBy { HistoricalAdaptiveCandidateSearch.signature(it) }
    }

    private fun coverage(p: java.util.Properties) = HistoricalCorpusTrainer.Coverage(
        ceSamples = p.longToInt("ceRows"),
        peSamples = p.longToInt("peRows"),
        engine1Samples = p.longToInt("e1Rows"),
        engine2Samples = p.longToInt("e2Rows"),
        engine3Samples = p.longToInt("e3Rows"),
        nativeDepthSamples = 0,
    )

    private fun corpusSamples(p: java.util.Properties): Int =
        (p.getProperty("accepted")?.toLongOrNull() ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun java.util.Properties.longToInt(key: String): Int =
        (getProperty(key)?.toLongOrNull() ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun result(
        index: MarketIndex,
        months: Long,
        p: java.util.Properties,
        candidates: Int,
        best: HistoricalCorpusTrainer.CandidateEvaluation?,
        opened: Boolean,
        passed: Boolean,
        candidateHoldout: HistoricalCorpusTrainer.Metrics?,
        productionHoldout: HistoricalCorpusTrainer.Metrics?,
        champion: NumericalMetaBrain.ModelState?,
        note: String,
    ): HistoricalCorpusTrainer.Result {
        val from = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now().minusMonths(12)
        val to = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        return HistoricalCorpusTrainer.Result(
            index = index,
            months = months,
            fromDate = from,
            toDate = to,
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
    }

    private fun emptyResult(index: MarketIndex, months: Long, error: String): HistoricalCorpusTrainer.Result = HistoricalCorpusTrainer.Result(
        index = index,
        months = months,
        fromDate = LocalDate.now().minusMonths(months),
        toDate = LocalDate.now(),
        expiries = 0,
        contractsDownloaded = 0,
        corpusSamples = 0,
        coverage = HistoricalCorpusTrainer.Coverage(0, 0, 0, 0, 0, 0),
        averageMfeReturn = 0.0,
        averageMaeReturn = 0.0,
        averageNetReturn = 0.0,
        candidatesEvaluated = 0,
        bestWalkForward = null,
        lockedHoldoutOpened = false,
        lockedHoldoutPassed = false,
        holdoutCandidate = null,
        holdoutProduction = null,
        championState = null,
        errors = listOf(error),
        note = "Pre-labelled corpus not compatible with selected market.",
    )

    companion object {
        private const val MAX_SEARCH_TRAIN_ROWS = 300_000L
        private const val MAX_SEARCH_VALIDATION_ROWS = 200_000L
        private const val FOLDS = 4
        private const val CALIBRATION_FRACTION = 0.35
        private const val MIN_CALIBRATION_ROWS = 40L
        private const val MIN_SCORING_ROWS = 80L
        private const val EMBARGO_MINUTES = 6L
        private const val EMBARGO_MS = EMBARGO_MINUTES * 60_000L
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

        internal fun sampledCount(rows: Long, stride: Int): Long =
            if (rows <= 0L) 0L else (rows + stride - 1L) / stride

        internal fun timeFold(timestampMs: Long, startMs: Long, endMs: Long, folds: Int): Int {
            if (folds <= 1 || endMs <= startMs) return 0
            val span = (endMs - startMs + 1L).coerceAtLeast(1L)
            val offset = (timestampMs - startMs).coerceIn(0L, span - 1L)
            return ((offset * folds) / span).toInt().coerceIn(0, folds - 1)
        }
    }
}
