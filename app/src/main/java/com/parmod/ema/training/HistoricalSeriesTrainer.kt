package com.parmod.ema.training

import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.engine.SignalEngineV2
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private typealias RawHistoricalSample = HistoricalSeriesSampleBuilder.Sample

/** Runs causal AI research over preloaded local/downloaded/combined option series. */
class HistoricalSeriesTrainer(
    private val productionBaseline: NumericalMetaBrain.ModelState,
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
    private data class Accumulator(
        var labels: Long = 0,
        var correct: Long = 0,
        var brierSum: Double = 0.0,
        var take: Long = 0,
        var takeWins: Long = 0,
        var reject: Long = 0,
        var rejectLosses: Long = 0,
        var takeNetReturnSum: Double = 0.0,
    ) {
        fun add(prediction: NumericalMetaBrain.Prediction, sample: RawHistoricalSample) {
            val y = if (sample.success) 1.0 else 0.0
            labels++
            if ((prediction.probabilitySuccess >= 0.50) == sample.success) correct++
            brierSum += (prediction.probabilitySuccess - y).pow(2)
            when (prediction.decision) {
                NumericalMetaBrain.Decision.TAKE -> {
                    take++
                    if (sample.success) takeWins++
                    takeNetReturnSum += sample.netReturn
                }
                NumericalMetaBrain.Decision.REJECT -> {
                    reject++
                    if (!sample.success) rejectLosses++
                }
                NumericalMetaBrain.Decision.CAUTION -> Unit
            }
        }

        fun merge(other: Accumulator) {
            labels += other.labels
            correct += other.correct
            brierSum += other.brierSum
            take += other.take
            takeWins += other.takeWins
            reject += other.reject
            rejectLosses += other.rejectLosses
            takeNetReturnSum += other.takeNetReturnSum
        }

        fun metrics() = HistoricalCorpusTrainer.Metrics(
            labels = labels,
            accuracy = if (labels == 0L) 0.0 else correct.toDouble() / labels,
            brier = if (labels == 0L) 1.0 else brierSum / labels,
            takeSamples = take,
            takePrecision = if (take == 0L) 0.0 else takeWins.toDouble() / take,
            rejectSamples = reject,
            rejectPrecision = if (reject == 0L) 0.0 else rejectLosses.toDouble() / reject,
            takeAverageNetReturn = if (take == 0L) 0.0 else takeNetReturnSum / take,
        )
    }

    fun run(
        index: MarketIndex,
        series: List<HistoricalOptionSeries>,
        config: HistoricalCorpusTrainer.Config,
        sourceLabel: String,
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): HistoricalCorpusTrainer.Result {
        require(config.sampleStrideBars >= 1)
        require(config.walkForwardFolds in 2..6)
        require(config.developmentFraction in 0.70..0.92)
        val selected = series.filter { it.index == index && it.optionType in setOf("CE", "PE") && it.candles.isNotEmpty() }
        val samples = ArrayList<RawHistoricalSample>()
        val errors = mutableListOf<String>()
        var ceSamples = 0
        var peSamples = 0
        var e1Samples = 0
        var e2Samples = 0
        var e3Samples = 0
        var nativeUnderlyingContracts = 0
        var proxyContracts = 0

        selected.forEachIndexed { i, contract ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(HistoricalCorpusTrainer.Progress("CORPUS", i, selected.size, "$sourceLabel · ${contract.expiry} ${contract.strike.toInt()} ${contract.optionType} · ${samples.size} causal samples"))
            runCatching {
                val candles = contract.candles.sortedBy { it.time.toInstant().toEpochMilli() }
                    .distinctBy { it.time.toInstant().toEpochMilli() }
                val built = HistoricalSeriesSampleBuilder.build(contract, candles, config, signalEngine)
                if (built.nativeUnderlying) nativeUnderlyingContracts++ else proxyContracts++
                samples += built.samples
                built.samples.forEach { sample ->
                    if (sample.side == PositionSide.CE) ceSamples++ else peSamples++
                    when (sample.engine) {
                        EngineId.ENGINE_1_TREND -> e1Samples++
                        EngineId.ENGINE_2_AVWAP_LIQUIDITY -> e2Samples++
                        EngineId.ENGINE_3_V76_SCALPER -> e3Samples++
                    }
                }
            }.onFailure { errors += "${contract.symbol.ifBlank { contract.key }}: ${it.message}" }
        }

        val corpus = samples.sortedBy { it.timestamp }
        val from = selected.flatMap { it.candles }.minOfOrNull { it.time.toLocalDate() } ?: LocalDate.now().minusMonths(config.months)
        val to = selected.flatMap { it.candles }.maxOfOrNull { it.time.toLocalDate() } ?: LocalDate.now()
        val expiryCount = selected.map { it.expiry }.distinct().size
        val coverage = HistoricalCorpusTrainer.Coverage(ceSamples, peSamples, e1Samples, e2Samples, e3Samples, 0)
        val contextNote = "signal context: native underlying $nativeUnderlyingContracts contract(s) · legacy option-premium proxy $proxyContracts contract(s)"
        onProgress(HistoricalCorpusTrainer.Progress("CORPUS", selected.size, selected.size, "$sourceLabel corpus ready · ${corpus.size} samples · $contextNote"))

        if (corpus.size < config.minimumCorpusSamples) {
            return HistoricalCorpusTrainer.Result(
                index = index,
                months = config.months,
                fromDate = from,
                toDate = to,
                expiries = expiryCount,
                contractsDownloaded = selected.size,
                corpusSamples = corpus.size,
                coverage = coverage,
                averageMfeReturn = corpus.map { it.mfeReturn }.averageOrZero(),
                averageMaeReturn = corpus.map { it.maeReturn }.averageOrZero(),
                averageNetReturn = corpus.map { it.netReturn }.averageOrZero(),
                candidatesEvaluated = 0,
                bestWalkForward = null,
                lockedHoldoutOpened = false,
                lockedHoldoutPassed = false,
                holdoutCandidate = null,
                holdoutProduction = null,
                championState = null,
                errors = errors + "Need at least ${config.minimumCorpusSamples} causal samples; found ${corpus.size}",
                note = "$sourceLabel · $contextNote · historical D30/depth is never fabricated; unavailable feature slots are zero.",
            )
        }

        val embargoMs = TrainingLeakageGuard.embargoMillis(config.interval, config.labelConfig.horizonBars)
        val developmentEnd = (corpus.size * config.developmentFraction).toInt().coerceIn(config.walkForwardFolds + 2, corpus.size - 1)
        val rawDevelopment = corpus.subList(0, developmentEnd)
        val holdout = corpus.subList(developmentEnd, corpus.size)
        val development = TrainingLeakageGuard.purgeBeforeBoundary(
            rows = rawDevelopment,
            boundaryTimestamp = holdout.first().timestamp,
            embargoMillis = embargoMs,
            timestamp = { it.timestamp },
        )
        if (development.size < config.walkForwardFolds + 2) {
            return HistoricalCorpusTrainer.Result(
                index = index,
                months = config.months,
                fromDate = from,
                toDate = to,
                expiries = expiryCount,
                contractsDownloaded = selected.size,
                corpusSamples = corpus.size,
                coverage = coverage,
                averageMfeReturn = corpus.map { it.mfeReturn }.averageOrZero(),
                averageMaeReturn = corpus.map { it.maeReturn }.averageOrZero(),
                averageNetReturn = corpus.map { it.netReturn }.averageOrZero(),
                candidatesEvaluated = 0,
                bestWalkForward = null,
                lockedHoldoutOpened = false,
                lockedHoldoutPassed = false,
                holdoutCandidate = null,
                holdoutProduction = null,
                championState = null,
                errors = errors + "Leakage embargo left insufficient development evidence",
                note = "$sourceLabel · $contextNote · fail-closed leakage validation blocked training; Production unchanged.",
            )
        }
        val seedHypers = candidateHyperParameters()
        val evaluations = ArrayList<HistoricalCorpusTrainer.CandidateEvaluation>()
        val seen = seedHypers.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }

        fun evaluateBatch(hypers: List<NumericalMetaBrain.HyperParameters>, generation: Int, guidance: HistoricalAdaptiveCandidateSearch.Guidance?) {
            hypers.forEachIndexed { i, hyper ->
                if (shouldCancel()) error("Training cancelled")
                val stage = if (generation == 0) "WALK_FORWARD" else "HISTORICAL_ADAPT_G$generation"
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        stage,
                        i,
                        hypers.size,
                        if (generation == 0) {
                            "$sourceLabel · seed Candidate ${i + 1}/${hypers.size} · purged chronological calibration + scoring"
                        } else {
                            "$sourceLabel · Adaptive G$generation ${guidance?.name ?: "BALANCED"} · Candidate ${i + 1}/${hypers.size} · development only · holdout untouched"
                        },
                    ),
                )
                evaluations += evaluateWalkForward(development, hyper, config.walkForwardFolds, embargoMs)
            }
        }

        evaluateBatch(seedHypers, 0, null)
        var best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
        var developmentGovernance = best?.let {
            HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, coverage, corpus.size)
        } ?: HistoricalCandidateGovernance.Decision(HistoricalCandidateGovernance.Status.CLOSED, listOf("No development Candidate"))
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
                    "HISTORICAL_EVOLVE",
                    adaptiveGenerations,
                    HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS,
                    "$sourceLabel · G$adaptiveGenerations ${guidance.name} · model evolves LR/L2; TAKE/REJECT recalibrates inside each purged development fold · Production frozen",
                ),
            )
            evaluateBatch(batch.candidates, adaptiveGenerations, guidance)
            best = HistoricalAdaptiveCandidateSearch.selectBest(evaluations)
            developmentGovernance = best?.let {
                HistoricalCandidateGovernance.evaluateDevelopment(it.candidate, it.production, coverage, corpus.size)
            } ?: developmentGovernance
        }

        val developmentQualified = best?.robust == true && developmentGovernance.passed
        var holdoutOpened = false
        var holdoutPassed = false
        var holdoutCandidate: HistoricalCorpusTrainer.Metrics? = null
        var holdoutProduction: HistoricalCorpusTrainer.Metrics? = null
        var championState: NumericalMetaBrain.ModelState? = null
        var governance = HistoricalCandidateGovernance.Decision(
            HistoricalCandidateGovernance.Status.CLOSED,
            listOf("Locked holdout was not opened"),
        )

        if (developmentQualified && best != null) {
            holdoutOpened = true
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "LOCKED_HOLDOUT",
                    0,
                    1,
                    "$sourceLabel · model + calibrated policy development-qualified after G$adaptiveGenerations · opening embargoed locked holdout ONCE",
                ),
            )
            val champion = brainFromBaseline(best.hyperParameters)
            learn(champion, development)
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            holdoutCandidate = evaluate(champion, holdout).metrics()
            holdoutProduction = evaluate(production, holdout).metrics()
            governance = HistoricalCandidateGovernance.evaluate(
                candidate = holdoutCandidate,
                production = holdoutProduction,
                coverage = coverage,
                corpusSamples = corpus.size,
                holdoutOpened = true,
            )
            holdoutPassed = governance.passed
            if (holdoutPassed) championState = champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW)
        }

        onProgress(
            HistoricalCorpusTrainer.Progress(
                "COMPLETE",
                evaluations.size,
                evaluations.size,
                when {
                    championState != null -> "$sourceLabel historical model + policy PASS after G$adaptiveGenerations · leakage guard PASS · ready for fresh live validation"
                    governance.status == HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "$sourceLabel locked holdout INSUFFICIENT DATA · search stopped; holdout is not reused"
                    holdoutOpened -> "$sourceLabel locked holdout FAIL · search stopped; holdout is not reused for tuning"
                    developmentGovernance.status == HistoricalCandidateGovernance.Status.INSUFFICIENT_DATA -> "$sourceLabel adaptive search exhausted G$adaptiveGenerations · calibrated action/evidence target not reached · holdout stayed closed"
                    else -> "$sourceLabel adaptive historical search exhausted G$adaptiveGenerations · no development-qualified model + policy · holdout stayed closed"
                },
            ),
        )

        val devNote = "Historical adaptive search: ${seedHypers.size} seeds + ${evaluations.size - seedHypers.size} evolved Candidates across G$adaptiveGenerations; every TRAIN/calibration/scoring/holdout boundary purged by ${embargoMs / 60_000L}m; locked holdout never tuned"
        val governanceNote = when {
            holdoutOpened -> "Locked governance ${governance.label}: ${governance.reasons.joinToString("; ")}"
            else -> "Development gate ${developmentGovernance.label}: ${developmentGovernance.reasons.joinToString("; ")}"
        }
        return HistoricalCorpusTrainer.Result(
            index = index,
            months = config.months,
            fromDate = from,
            toDate = to,
            expiries = expiryCount,
            contractsDownloaded = selected.size,
            corpusSamples = corpus.size,
            coverage = coverage,
            averageMfeReturn = corpus.map { it.mfeReturn }.averageOrZero(),
            averageMaeReturn = corpus.map { it.maeReturn }.averageOrZero(),
            averageNetReturn = corpus.map { it.netReturn }.averageOrZero(),
            candidatesEvaluated = evaluations.size,
            bestWalkForward = best,
            lockedHoldoutOpened = holdoutOpened,
            lockedHoldoutPassed = holdoutPassed,
            holdoutCandidate = holdoutCandidate,
            holdoutProduction = holdoutProduction,
            championState = championState,
            errors = errors,
            note = "$sourceLabel · $contextNote · $devNote · $governanceNote · actual option-premium MFE/MAE · next-bar entry · cost-aware TAKE/REJECT policy calibration · locked holdout opened at most once · unavailable historical D30/depth stays zero.",
        )
    }

    private fun evaluateWalkForward(
        development: List<RawHistoricalSample>,
        hyper: NumericalMetaBrain.HyperParameters,
        requestedFolds: Int,
        embargoMs: Long,
    ): HistoricalCorpusTrainer.CandidateEvaluation {
        val blocks = requestedFolds + 1
        val candidateTotal = Accumulator()
        val productionTotal = Accumulator()
        val takePolicies = ArrayList<Double>()
        val rejectPolicies = ArrayList<Double>()
        var calibrationScore = 0.0
        var foldsRun = 0
        var foldsWon = 0

        for (fold in 1..requestedFolds) {
            val trainEnd = development.size * fold / blocks
            val validateEnd = if (fold == requestedFolds) development.size else development.size * (fold + 1) / blocks
            if (trainEnd < 20 || validateEnd <= trainEnd) continue
            val rawTrain = development.subList(0, trainEnd)
            val validation = development.subList(trainEnd, validateEnd)
            if (validation.size < MIN_FOLD_VALIDATION) continue
            val train = TrainingLeakageGuard.purgeBeforeBoundary(rawTrain, validation.first().timestamp, embargoMs) { it.timestamp }
            if (train.size < 20) continue
            val calibrationSize = max(MIN_FOLD_CALIBRATION, (validation.size * CALIBRATION_FRACTION).toInt())
                .coerceAtMost(validation.size - MIN_FOLD_SCORING)
            if (calibrationSize <= 0 || validation.size - calibrationSize < MIN_FOLD_SCORING) continue
            val rawCalibration = validation.subList(0, calibrationSize)
            val scoringSlice = validation.subList(calibrationSize, validation.size)
            val calibrationSlice = TrainingLeakageGuard.purgeBeforeBoundary(rawCalibration, scoringSlice.first().timestamp, embargoMs) { it.timestamp }
            if (calibrationSlice.size < MIN_FOLD_CALIBRATION) continue
            val leakage = TrainingLeakageGuard.validateOrderedSlices(
                train = train,
                calibration = calibrationSlice,
                scoring = scoringSlice,
                embargoMillis = embargoMs,
                timestamp = { it.timestamp },
            )
            if (!leakage.passed) continue

            val candidate = brainFromBaseline(hyper)
            learn(candidate, train)
            val stream = BinaryTrainingPolicy.StreamingCalibration()
            calibrationSlice.forEach { s ->
                val p = candidate.predict(s.features)
                stream.add(p.probabilitySuccess, s.success, s.netReturn)
            }
            val policy = BinaryTrainingPolicy.applyCalibration(candidate, stream)
            takePolicies += policy.takeThreshold
            rejectPolicies += policy.rejectThreshold
            calibrationScore += policy.score

            val production = brainFromBaseline(productionBaseline.hyperParameters)
            val cm = evaluate(candidate, scoringSlice)
            val pm = evaluate(production, scoringSlice)
            candidateTotal.merge(cm)
            productionTotal.merge(pm)
            foldsRun++
            if (foldScore(cm.metrics(), pm.metrics()) > 0.0) foldsWon++
        }

        val candidateMetrics = candidateTotal.metrics()
        val productionMetrics = productionTotal.metrics()
        val winRatio = if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun
        val medianTake = median(takePolicies, hyper.takeThreshold)
        val medianReject = median(rejectPolicies, hyper.rejectThreshold).coerceAtMost(medianTake - BinaryTrainingPolicy.MIN_THRESHOLD_GAP)
        val calibratedHyper = HistoricalAdaptiveCandidateSearch.bounded(hyper.copy(takeThreshold = medianTake, rejectThreshold = medianReject))
        val score = foldScore(candidateMetrics, productionMetrics) +
            0.08 * (winRatio - 0.50) +
            if (foldsRun == 0) 0.0 else 0.10 * calibrationScore / foldsRun
        val robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt()
        return HistoricalCorpusTrainer.CandidateEvaluation(calibratedHyper, foldsRun, foldsWon, candidateMetrics, productionMetrics, score, robust)
    }

    private fun foldScore(candidate: HistoricalCorpusTrainer.Metrics, production: HistoricalCorpusTrainer.Metrics): Double {
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

    private fun learn(brain: NumericalMetaBrain, samples: List<RawHistoricalSample>) =
        samples.forEach { brain.learn(it.features, it.success, it.weight) }

    private fun evaluate(brain: NumericalMetaBrain, samples: List<RawHistoricalSample>): Accumulator =
        Accumulator().also { stats -> samples.forEach { stats.add(brain.predict(it.features), it) } }

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

    private fun median(values: List<Double>, fallback: Double): Double {
        if (values.isEmpty()) return fallback
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val CALIBRATION_FRACTION = 0.35
        private const val MIN_FOLD_CALIBRATION = 12
        private const val MIN_FOLD_SCORING = 20
        private const val MIN_FOLD_VALIDATION = MIN_FOLD_CALIBRATION + MIN_FOLD_SCORING
    }
}
