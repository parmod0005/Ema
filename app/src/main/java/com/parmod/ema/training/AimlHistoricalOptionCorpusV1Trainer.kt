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
 * Seed + adaptive generations are trained only on the original train split and are
 * selected only on the original validation split. The original test split remains
 * locked until one Candidate clears walk-forward + strict development governance.
 * Historical evolution is action-aware: reject-only models cannot remain parents
 * simply because their accuracy/Brier score is high. Test results are never fed
 * back into subsequent mutations.
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
        val trainRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.TRAIN)
        val validationRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.VALIDATION)
        val testRows = store.rows(AimlHistoricalOptionCorpusV1Store.Split.TEST)
        val corpusMarket = meta.getProperty("market", "NIFTY")
        if ((index == MarketIndex.NIFTY && corpusMarket != "NIFTY") || (index == MarketIndex.SENSEX && corpusMarket != "SENSEX")) {
            return emptyResult(index, monthsLabel, "Imported pre-labelled corpus contains $corpusMarket, not ${index.name}")
        }

        val coverage = coverage(meta)
        val corpusSamples = corpusSamples(meta)
        val trainStride = ceil(trainRows.toDouble() / MAX_SEARCH_TRAIN_ROWS).toInt().coerceAtLeast(1)
        val validationStride = ceil(validationRows.toDouble() / MAX_SEARCH_VALIDATION_ROWS).toInt().coerceAtLeast(1)
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
                        "Training ${hypers.size} seed Candidates on original train split · stride $trainStride"
                    } else {
                        "Training Adaptive G$generation ${guidance?.name ?: "BALANCED"} · ${hypers.size} Candidates · original train only · locked test untouched"
                    },
                ),
            )
            store.forEach(
                split = AimlHistoricalOptionCorpusV1Store.Split.TRAIN,
                stride = trainStride,
                shouldCancel = shouldCancel,
            ) { record ->
                if (record.index != index) return@forEach
                val s = sample(record)
                brains.forEach { it.learn(s.features, s.success, s.weight) }
                searchTrainUsed++
                if (searchTrainUsed % 25_000L == 0L) {
                    onProgress(
                        HistoricalCorpusTrainer.Progress(
                            "${stagePrefix}_TRAIN",
                            (searchTrainUsed / 25_000L).toInt(),
                            max(1, (MAX_SEARCH_TRAIN_ROWS / 25_000L).toInt()),
                            "G$generation · $searchTrainUsed sampled train rows · ${hypers.size} Candidates",
                        ),
                    )
                }
            }

            val candidateAcc = Array(hypers.size) { Acc() }
            val productionAcc = Acc()
            val foldCandidate = Array(hypers.size) { Array(FOLDS) { Acc() } }
            val foldProduction = Array(FOLDS) { Acc() }
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            var validationUsed = 0L
            val validationTarget = max(1L, min(validationRows, MAX_SEARCH_VALIDATION_ROWS))
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "${stagePrefix}_VALIDATION",
                    0,
                    FOLDS,
                    "Scoring G$generation on original validation split · test remains locked",
                ),
            )
            store.forEach(
                split = AimlHistoricalOptionCorpusV1Store.Split.VALIDATION,
                stride = validationStride,
                shouldCancel = shouldCancel,
            ) { record ->
                if (record.index != index) return@forEach
                val s = sample(record)
                val p = production.predict(s.features)
                productionAcc.add(p, s)
                val fold = ((validationUsed * FOLDS) / validationTarget).toInt().coerceIn(0, FOLDS - 1)
                foldProduction[fold].add(p, s)
                brains.forEachIndexed { i, brain ->
                    val prediction = brain.predict(s.features)
                    candidateAcc[i].add(prediction, s)
                    foldCandidate[i][fold].add(prediction, s)
                }
                validationUsed++
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
                val s = score(cm, prodValidation) + 0.08 * ((if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun) - 0.50)
                HistoricalCorpusTrainer.CandidateEvaluation(
                    hyperParameters = hypers[i],
                    foldsRun = foldsRun,
                    foldsWon = foldsWon,
                    candidate = cm,
                    production = prodValidation,
                    score = s,
                    robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt() && s > 0.0,
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
                    "G$adaptiveGenerations guidance ${guidance.name} · parent TAKE ${parent.candidate.takeSamples}/${HistoricalCandidateGovernance.requiredActionSamples(parent.candidate.labels)} · TAKE net ${"%+.2f%%".format(parent.candidate.takeAverageNetReturn * 100.0)} · Production/test unchanged",
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
                    "Action-aware adaptive search exhausted G$adaptiveGenerations · ${developmentGovernance.label} · original test stayed locked",
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
                note = "aiml-historical-option-row-v1 · ${seedHypers.size} seeds + ${evaluations.size - seedHypers.size} evolved Candidates across G$adaptiveGenerations · action-aware parent ranking · TAKE-starvation recovery can lower historical TAKE threshold to 51% · development-only evolution · test never opened · Development ${developmentGovernance.label}: ${developmentGovernance.reasons.joinToString("; ")}.",
            )
        }

        val champion = brainFromBaseline(best.hyperParameters)
        var fullTrain = 0L
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "PRELABELLED_REFIT",
                0,
                1,
                "Development-qualified after G$adaptiveGenerations · refitting winner on all $trainRows train rows",
            ),
        )
        store.forEach(AimlHistoricalOptionCorpusV1Store.Split.TRAIN, shouldCancel = shouldCancel) { record ->
            if (record.index != index) return@forEach
            val s = sample(record)
            champion.learn(s.features, s.success, s.weight)
            fullTrain++
            if (fullTrain % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "PRELABELLED_REFIT",
                        fullTrain.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        trainRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "Full-train refit · $fullTrain/$trainRows",
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
                1,
                "Development-qualified · opening original test split ONCE; failure stops this research cycle",
            ),
        )
        store.forEach(AimlHistoricalOptionCorpusV1Store.Split.TEST, shouldCancel = shouldCancel) { record ->
            if (record.index != index) return@forEach
            val s = sample(record)
            candidateTest.add(champion.predict(s.features), s)
            productionTest.add(production.predict(s.features), s)
            testUsed++
            if (testUsed % 100_000L == 0L) {
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        "LOCKED_HOLDOUT",
                        testUsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        testRows.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        "Locked test · $testUsed/$testRows",
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
                    HistoricalCandidateGovernance.Status.PASS -> "Original locked test + governance PASS · champion ready as Candidate only"
                    HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "Original locked test INSUFFICIENT DATA · research stops; test will not be used for tuning"
                    HistoricalCandidateGovernance.Status.FAIL -> "Original locked test FAIL · research stops; test will not be used for tuning"
                    HistoricalCandidateGovernance.Status.CLOSED -> "Original locked test stayed closed"
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
            note = "aiml-historical-option-row-v1 · ${seedHypers.size} seeds + ${evaluations.size - seedHypers.size} evolved Candidates across G$adaptiveGenerations · action-aware historical evolution · original expiry-ordered train/validation/test preserved · locked test opened once only · 5m option future-return/MFE/MAE labels · conservative stop-first · costs included · historical native D30 unavailable · Governance ${governance.label}: ${governance.reasons.joinToString("; ")}.",
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
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
    }
}
