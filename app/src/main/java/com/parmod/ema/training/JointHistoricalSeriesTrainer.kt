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

private typealias JointRawHistoricalSample = HistoricalSeriesSampleBuilder.Sample

/**
 * Joint historical research for the shared VARDHANI brain.
 * NIFTY and SENSEX are merged chronologically; each walk-forward block fits weights,
 * calibrates one shared cost-aware policy on an early forward slice, and scores it on
 * a later forward slice. Locked holdout is opened once only after dual-market governance.
 */
class JointHistoricalSeriesTrainer(
    private val productionBaseline: NumericalMetaBrain.ModelState,
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
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
        fun add(prediction: NumericalMetaBrain.Prediction, sample: JointRawHistoricalSample) {
            val y = if (sample.success) 1.0 else 0.0
            labels++
            if ((prediction.probabilitySuccess >= 0.50) == sample.success) correct++
            brierSum += (prediction.probabilitySuccess - y).pow(2)
            when (prediction.decision) {
                NumericalMetaBrain.Decision.TAKE -> {
                    take++
                    if (sample.success) takeWins++
                    takeNet += sample.netReturn
                }
                NumericalMetaBrain.Decision.REJECT -> {
                    reject++
                    if (!sample.success) rejectLosses++
                }
                NumericalMetaBrain.Decision.CAUTION -> Unit
            }
        }

        fun merge(other: Acc) {
            labels += other.labels
            correct += other.correct
            brierSum += other.brierSum
            take += other.take
            takeWins += other.takeWins
            reject += other.reject
            rejectLosses += other.rejectLosses
            takeNet += other.takeNet
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
        series: List<HistoricalOptionSeries>,
        config: HistoricalCorpusTrainer.Config,
        sourceLabel: String,
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): HistoricalCorpusTrainer.Result {
        require(config.sampleStrideBars >= 1)
        require(config.walkForwardFolds in 2..6)
        require(config.developmentFraction in 0.70..0.92)
        val selected = series.filter { it.index in MarketIndex.entries && it.optionType in setOf("CE", "PE") && it.candles.isNotEmpty() }
        val errors = mutableListOf<String>()
        val samples = ArrayList<JointRawHistoricalSample>()
        var ce = 0
        var pe = 0
        var e1 = 0
        var e2 = 0
        var e3 = 0
        var nativeUnderlyingContracts = 0
        var proxyContracts = 0

        selected.forEachIndexed { i, contract ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "JOINT_CORPUS",
                    i,
                    selected.size,
                    "$sourceLabel · ${contract.index.name} ${contract.expiry} ${contract.strike.toInt()} ${contract.optionType} · ${samples.size} joint causal samples",
                ),
            )
            runCatching {
                val candles = contract.candles.sortedBy { it.time.toInstant().toEpochMilli() }
                    .distinctBy { it.time.toInstant().toEpochMilli() }
                val built = HistoricalSeriesSampleBuilder.build(contract, candles, config, signalEngine)
                if (built.nativeUnderlying) nativeUnderlyingContracts++ else proxyContracts++
                samples += built.samples
                built.samples.forEach { s ->
                    if (s.side == PositionSide.CE) ce++ else pe++
                    when (s.engine) {
                        EngineId.ENGINE_1_TREND -> e1++
                        EngineId.ENGINE_2_AVWAP_LIQUIDITY -> e2++
                        EngineId.ENGINE_3_V76_SCALPER -> e3++
                    }
                }
            }.onFailure { errors += "${contract.index.name} ${contract.symbol.ifBlank { contract.key }}: ${it.message}" }
        }

        val corpus = samples.sortedWith(compareBy<JointRawHistoricalSample> { it.timestamp }.thenBy { it.index.ordinal })
        val coverage = HistoricalCorpusTrainer.Coverage(ce, pe, e1, e2, e3, 0)
        val marketCounts = MarketIndex.entries.associateWith { m -> corpus.count { it.index == m } }
        val from = selected.flatMap { it.candles }.minOfOrNull { it.time.toLocalDate() } ?: LocalDate.now().minusMonths(config.months)
        val to = selected.flatMap { it.candles }.maxOfOrNull { it.time.toLocalDate() } ?: LocalDate.now()
        val expiryCount = selected.map { it.index to it.expiry }.distinct().size
        val contextNote = "signal context: native underlying $nativeUnderlyingContracts contract(s) · legacy option-premium proxy $proxyContracts contract(s)"
        onProgress(
            HistoricalCorpusTrainer.Progress(
                "JOINT_CORPUS",
                selected.size,
                selected.size,
                "Joint corpus ready · NIFTY ${marketCounts[MarketIndex.NIFTY]} · SENSEX ${marketCounts[MarketIndex.SENSEX]} · $contextNote",
            ),
        )

        val marketMin = max(config.minimumCorpusSamples / 2, 60)
        val missingMarket = MarketIndex.entries.firstOrNull { (marketCounts[it] ?: 0) < marketMin }
        if (corpus.size < config.minimumCorpusSamples || missingMarket != null) {
            return result(
                config, from, to, expiryCount, selected.size, corpus, coverage, 0, null,
                false, false, null, null, null,
                errors + if (missingMarket != null) "${missingMarket.name} needs at least $marketMin causal samples for BOTH-market training" else "Need at least ${config.minimumCorpusSamples} joint causal samples",
                "$sourceLabel · $contextNote · BOTH-market training blocked until both NIFTY and SENSEX have adequate causal evidence.",
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
        val devMarketMissing = MarketIndex.entries.firstOrNull { m -> development.none { it.index == m } }
        val holdoutMarketMissing = MarketIndex.entries.firstOrNull { m -> holdout.none { it.index == m } }
        if (development.size < config.walkForwardFolds + 2 || devMarketMissing != null || holdoutMarketMissing != null) {
            val reason = when {
                devMarketMissing != null -> "${devMarketMissing.name} missing from embargoed development"
                holdoutMarketMissing != null -> "${holdoutMarketMissing.name} missing from locked holdout"
                else -> "Leakage embargo left insufficient development evidence"
            }
            return result(
                config, from, to, expiryCount, selected.size, corpus, coverage, 0, null,
                false, false, null, null, null, errors + reason,
                "$sourceLabel · $contextNote · BOTH fail-closed leakage validation blocked training; Production unchanged.",
            )
        }
        val seedHypers = candidateHyperParameters()
        val evaluations = ArrayList<JointEvaluation>()
        val seen = seedHypers.mapTo(linkedSetOf()) { HistoricalAdaptiveCandidateSearch.signature(it) }

        fun evalBatch(hypers: List<NumericalMetaBrain.HyperParameters>, generation: Int, guidance: HistoricalAdaptiveCandidateSearch.Guidance?) {
            hypers.forEachIndexed { i, hyper ->
                if (shouldCancel()) error("Training cancelled")
                onProgress(
                    HistoricalCorpusTrainer.Progress(
                        if (generation == 0) "JOINT_WALK_FORWARD" else "JOINT_ADAPT_G$generation",
                        i,
                        hypers.size,
                        if (generation == 0) "$sourceLabel BOTH · seed ${i + 1}/${hypers.size} · purged dual-market policy calibration" else "$sourceLabel BOTH · G$generation ${guidance?.name ?: "BALANCED"} · ${i + 1}/${hypers.size} · holdout untouched",
                    ),
                )
                evaluations += evaluateWalkForward(development, hyper, config.walkForwardFolds, coverage, corpus.size, embargoMs)
            }
        }

        evalBatch(seedHypers, 0, null)
        var best = selectBest(evaluations)
        var adaptiveGenerations = 0
        while (
            (best?.summary?.robust != true || best.governance.passed.not()) &&
            adaptiveGenerations < HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS
        ) {
            val parent = best ?: break
            adaptiveGenerations++
            val guidance = HistoricalAdaptiveCandidateSearch.guidance(parent.summary)
            val batch = HistoricalAdaptiveCandidateSearch.nextBatch(
                parent = parent.summary.hyperParameters,
                generation = adaptiveGenerations,
                seenSignatures = seen,
                guidance = guidance,
            )
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "JOINT_EVOLVE",
                    adaptiveGenerations,
                    HistoricalAdaptiveCandidateSearch.MAX_ADAPTIVE_GENERATIONS,
                    "BOTH G$adaptiveGenerations ${guidance.name} · model evolves; shared policy recalibrates on both markets inside purged development · holdout untouched",
                ),
            )
            evalBatch(batch.candidates, adaptiveGenerations, guidance)
            best = selectBest(evaluations)
        }

        if (best == null || !best.summary.robust || !best.governance.passed) {
            return result(
                config, from, to, expiryCount, selected.size, corpus, coverage, evaluations.size, best?.summary,
                false, false, null, null, null, errors,
                "$sourceLabel · $contextNote · BOTH joint model + policy search exhausted G$adaptiveGenerations · ${best?.governance?.label ?: "CLOSED"}: ${best?.governance?.reasons?.joinToString("; ") ?: "no candidate"} · leakage guard active · locked holdout never opened.",
            )
        }

        onProgress(HistoricalCorpusTrainer.Progress("JOINT_LOCKED_HOLDOUT", 0, 1, "BOTH model + shared policy development-qualified after G$adaptiveGenerations · opening embargoed locked holdout ONCE"))
        val champion = brainFromBaseline(best.summary.hyperParameters)
        learn(champion, development)
        val production = brainFromBaseline(productionBaseline.hyperParameters)
        val candidateHoldoutAcc = evaluate(champion, holdout)
        val productionHoldoutAcc = evaluate(production, holdout)
        val candidateHoldout = candidateHoldoutAcc.metrics()
        val productionHoldout = productionHoldoutAcc.metrics()
        val candidateBy = evaluateByMarket(champion, holdout)
        val productionBy = evaluateByMarket(production, holdout)
        val governance = DualMarketHistoricalGovernance.evaluateHoldout(
            candidate = candidateHoldout,
            production = productionHoldout,
            candidateByMarket = candidateBy,
            productionByMarket = productionBy,
            coverage = coverage,
            corpusSamples = corpus.size,
        )
        val passed = governance.passed
        val state = if (passed) champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW) else null
        val note = buildString {
            append("$sourceLabel · $contextNote · BOTH NIFTY+SENSEX joint chronological corpus · ")
            append("${seedHypers.size} seeds + ${evaluations.size - seedHypers.size} evolved across G$adaptiveGenerations · ")
            append("every TRAIN/calibration/scoring/holdout boundary purged by ${embargoMs / 60_000L}m · locked holdout opened once · ")
            append("dual-market governance ${governance.label}: ${governance.reasons.joinToString("; ")} · ")
            append("NIFTY holdout labels ${candidateBy[MarketIndex.NIFTY]?.labels ?: 0} · SENSEX ${candidateBy[MarketIndex.SENSEX]?.labels ?: 0} · ")
            append("actual option-premium MFE/MAE · next-bar entry · historical D30 unavailable stays zero.")
        }
        return result(
            config, from, to, expiryCount, selected.size, corpus, coverage, evaluations.size, best.summary,
            true, passed, candidateHoldout, productionHoldout, state, errors, note,
        )
    }

    private fun evaluateWalkForward(
        development: List<JointRawHistoricalSample>,
        hyper: NumericalMetaBrain.HyperParameters,
        requestedFolds: Int,
        coverage: HistoricalCorpusTrainer.Coverage,
        corpusSamples: Int,
        embargoMs: Long,
    ): JointEvaluation {
        val blocks = requestedFolds + 1
        val candidateTotal = Acc()
        val productionTotal = Acc()
        val candidateMarketAcc = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        val productionMarketAcc = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
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
            if (MarketIndex.entries.any { market -> calibrationSlice.count { it.index == market } < MIN_MARKET_CALIBRATION }) continue
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
            val overall = BinaryTrainingPolicy.StreamingCalibration()
            val byMarket = MarketIndex.entries.associateWith { BinaryTrainingPolicy.StreamingCalibration() }
            calibrationSlice.forEach { s ->
                val p = candidate.predict(s.features)
                overall.add(p.probabilitySuccess, s.success, s.netReturn)
                byMarket.getValue(s.index).add(p.probabilitySuccess, s.success, s.netReturn)
            }
            val current = candidate.currentHyperParameters()
            val policy = BinaryTrainingPolicy.calibrateJoint(overall, byMarket.values, current)
            candidate.configure(current.copy(takeThreshold = policy.takeThreshold, rejectThreshold = policy.rejectThreshold), bumpVersion = false)
            takePolicies += policy.takeThreshold
            rejectPolicies += policy.rejectThreshold
            calibrationScore += policy.score

            val production = brainFromBaseline(productionBaseline.hyperParameters)
            val cm = evaluate(candidate, scoringSlice)
            val pm = evaluate(production, scoringSlice)
            candidateTotal.merge(cm)
            productionTotal.merge(pm)
            val cBy = evaluateAccByMarket(candidate, scoringSlice)
            val pBy = evaluateAccByMarket(production, scoringSlice)
            MarketIndex.entries.forEach { m ->
                candidateMarketAcc.getValue(m).merge(cBy.getValue(m))
                productionMarketAcc.getValue(m).merge(pBy.getValue(m))
            }
            foldsRun++
            val overallWin = foldScore(cm.metrics(), pm.metrics()) > 0.0
            val marketSafe = MarketIndex.entries.all { m ->
                val c = cBy.getValue(m).metrics()
                val p = pBy.getValue(m).metrics()
                c.labels < 10 || (c.accuracy - p.accuracy >= -0.03 || p.brier - c.brier >= -0.01)
            }
            if (overallWin && marketSafe) foldsWon++
        }

        val cMetrics = candidateTotal.metrics()
        val pMetrics = productionTotal.metrics()
        val winRatio = if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun
        val medianTake = median(takePolicies, hyper.takeThreshold)
        val medianReject = median(rejectPolicies, hyper.rejectThreshold).coerceAtMost(medianTake - BinaryTrainingPolicy.MIN_THRESHOLD_GAP)
        val calibratedHyper = HistoricalAdaptiveCandidateSearch.bounded(hyper.copy(takeThreshold = medianTake, rejectThreshold = medianReject))
        val score = foldScore(cMetrics, pMetrics) + 0.08 * (winRatio - 0.50) +
            if (foldsRun == 0) 0.0 else 0.10 * calibrationScore / foldsRun
        val robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt()
        val summary = HistoricalCorpusTrainer.CandidateEvaluation(calibratedHyper, foldsRun, foldsWon, cMetrics, pMetrics, score, robust)
        val cByMetrics = candidateMarketAcc.mapValues { it.value.metrics() }
        val pByMetrics = productionMarketAcc.mapValues { it.value.metrics() }
        val governance = DualMarketHistoricalGovernance.evaluateDevelopment(cMetrics, pMetrics, cByMetrics, pByMetrics, coverage, corpusSamples)
        return JointEvaluation(summary, cByMetrics, pByMetrics, governance)
    }

    private fun selectBest(evaluations: List<JointEvaluation>): JointEvaluation? {
        if (evaluations.isEmpty()) return null
        val passed = evaluations.filter { it.summary.robust && it.governance.passed }
        return (passed.ifEmpty { evaluations }).maxByOrNull { e ->
            var score = HistoricalAdaptiveCandidateSearch.developmentSelectionScore(e.summary)
            for (market in MarketIndex.entries) {
                val c = e.candidateByMarket[market] ?: HistoricalCorpusTrainer.Metrics()
                val p = e.productionByMarket[market] ?: HistoricalCorpusTrainer.Metrics()
                val required = DualMarketHistoricalGovernance.requiredActions(c.labels).coerceAtLeast(1)
                val actionCoverage = min(c.takeSamples.toDouble() / required, 1.0)
                score += 0.12 * actionCoverage
                score += 0.08 * (c.accuracy - p.accuracy)
                score += 0.08 * (p.brier - c.brier)
                if (c.labels < DualMarketHistoricalGovernance.MIN_MARKET_LABELS) score -= 0.20
            }
            if (e.governance.passed) score += 0.30
            score
        }
    }

    private fun evaluate(brain: NumericalMetaBrain, samples: List<JointRawHistoricalSample>): Acc =
        Acc().also { a -> samples.forEach { a.add(brain.predict(it.features), it) } }

    private fun evaluateAccByMarket(brain: NumericalMetaBrain, samples: List<JointRawHistoricalSample>): Map<MarketIndex, Acc> {
        val out = MarketIndex.entries.associateWith { Acc() }.toMutableMap()
        samples.forEach { out.getValue(it.index).add(brain.predict(it.features), it) }
        return out
    }

    private fun evaluateByMarket(brain: NumericalMetaBrain, samples: List<JointRawHistoricalSample>): Map<MarketIndex, HistoricalCorpusTrainer.Metrics> =
        evaluateAccByMarket(brain, samples).mapValues { it.value.metrics() }

    private fun learn(brain: NumericalMetaBrain, samples: List<JointRawHistoricalSample>) =
        samples.forEach { brain.learn(it.features, it.success, it.weight) }

    private fun foldScore(c: HistoricalCorpusTrainer.Metrics, p: HistoricalCorpusTrainer.Metrics): Double {
        val accuracyGain = c.accuracy - p.accuracy
        val brierGain = p.brier - c.brier
        val requiredTake = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val requiredReject = HistoricalCandidateGovernance.requiredActionSamples(c.labels).coerceAtLeast(1L)
        val takeCoverage = (c.takeSamples.toDouble() / requiredTake).coerceIn(0.0, 1.0)
        val rejectCoverage = (c.rejectSamples.toDouble() / requiredReject).coerceIn(0.0, 1.0)
        val takeBonus = if (c.takeSamples > 0) c.takePrecision - 0.50 else -0.50
        val rejectBonus = if (c.rejectSamples > 0) c.rejectPrecision - 0.50 else -0.25
        return accuracyGain + brierGain + 0.22 * takeBonus + 0.10 * rejectBonus +
            0.35 * c.takeAverageNetReturn + 0.18 * takeCoverage + 0.05 * rejectCoverage -
            0.45 * (1.0 - takeCoverage) - 0.10 * (1.0 - rejectCoverage)
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

    private fun median(values: List<Double>, fallback: Double): Double {
        if (values.isEmpty()) return fallback
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun result(
        config: HistoricalCorpusTrainer.Config,
        from: LocalDate,
        to: LocalDate,
        expiries: Int,
        contracts: Int,
        corpus: List<JointRawHistoricalSample>,
        coverage: HistoricalCorpusTrainer.Coverage,
        candidates: Int,
        best: HistoricalCorpusTrainer.CandidateEvaluation?,
        opened: Boolean,
        passed: Boolean,
        holdoutCandidate: HistoricalCorpusTrainer.Metrics?,
        holdoutProduction: HistoricalCorpusTrainer.Metrics?,
        champion: NumericalMetaBrain.ModelState?,
        errors: List<String>,
        note: String,
    ) = HistoricalCorpusTrainer.Result(
        index = MarketIndex.NIFTY,
        months = config.months,
        fromDate = from,
        toDate = to,
        expiries = expiries,
        contractsDownloaded = contracts,
        corpusSamples = corpus.size,
        coverage = coverage,
        averageMfeReturn = corpus.map { it.mfeReturn }.averageOrZero(),
        averageMaeReturn = corpus.map { it.maeReturn }.averageOrZero(),
        averageNetReturn = corpus.map { it.netReturn }.averageOrZero(),
        candidatesEvaluated = candidates,
        bestWalkForward = best,
        lockedHoldoutOpened = opened,
        lockedHoldoutPassed = passed,
        holdoutCandidate = holdoutCandidate,
        holdoutProduction = holdoutProduction,
        championState = champion,
        errors = errors,
        nativeHistoricalDepthAvailable = false,
        note = note,
    )

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    companion object {
        private const val CALIBRATION_FRACTION = 0.35
        private const val MIN_FOLD_CALIBRATION = 20
        private const val MIN_FOLD_SCORING = 30
        private const val MIN_FOLD_VALIDATION = MIN_FOLD_CALIBRATION + MIN_FOLD_SCORING
        private const val MIN_MARKET_CALIBRATION = 5
    }
}
