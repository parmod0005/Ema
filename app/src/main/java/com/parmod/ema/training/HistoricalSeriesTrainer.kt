package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.engine.SignalEngineV2
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Runs the same causal AI research pipeline over preloaded local/downloaded/combined option series. */
class HistoricalSeriesTrainer(
    private val productionBaseline: NumericalMetaBrain.ModelState,
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
    private data class Sample(
        val timestamp: Long,
        val features: NumericalMetaBrain.Features,
        val success: Boolean,
        val weight: Double,
        val mfeReturn: Double,
        val maeReturn: Double,
        val netReturn: Double,
        val side: PositionSide,
        val engine: EngineId,
    )

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
        fun add(prediction: NumericalMetaBrain.Prediction, sample: Sample) {
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
        val samples = ArrayList<Sample>()
        val errors = mutableListOf<String>()
        var ceSamples = 0
        var peSamples = 0
        var e1Samples = 0
        var e2Samples = 0
        var e3Samples = 0

        selected.forEachIndexed { i, contract ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(HistoricalCorpusTrainer.Progress("CORPUS", i, selected.size, "$sourceLabel · ${contract.expiry} ${contract.strike.toInt()} ${contract.optionType} · ${samples.size} causal samples"))
            runCatching {
                val candles = contract.candles.sortedBy { it.time.toInstant().toEpochMilli() }
                    .distinctBy { it.time.toInstant().toEpochMilli() }
                val built = buildSamples(index, contract, candles, config)
                samples += built
                built.forEach { sample ->
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
        onProgress(HistoricalCorpusTrainer.Progress("CORPUS", selected.size, selected.size, "$sourceLabel corpus ready · ${corpus.size} samples"))

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
                note = "$sourceLabel · historical D30/depth is never fabricated; unavailable feature slots are zero.",
            )
        }

        val developmentEnd = (corpus.size * config.developmentFraction).toInt().coerceIn(config.walkForwardFolds + 2, corpus.size - 1)
        val development = corpus.subList(0, developmentEnd)
        val holdout = corpus.subList(developmentEnd, corpus.size)
        val hypers = candidateHyperParameters()
        val evaluations = ArrayList<HistoricalCorpusTrainer.CandidateEvaluation>()
        hypers.forEachIndexed { i, hyper ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(HistoricalCorpusTrainer.Progress("WALK_FORWARD", i, hypers.size, "$sourceLabel · Candidate ${i + 1}/${hypers.size} · chronological folds"))
            evaluations += evaluateWalkForward(development, hyper, config.walkForwardFolds)
        }
        val best = evaluations.maxByOrNull { it.score }
        val robust = best?.robust == true
        var holdoutOpened = false
        var holdoutPassed = false
        var holdoutCandidate: HistoricalCorpusTrainer.Metrics? = null
        var holdoutProduction: HistoricalCorpusTrainer.Metrics? = null
        var championState: NumericalMetaBrain.ModelState? = null

        if (robust && best != null) {
            holdoutOpened = true
            onProgress(HistoricalCorpusTrainer.Progress("LOCKED_HOLDOUT", 0, 1, "$sourceLabel · walk-forward robust · opening final holdout once"))
            val champion = brainFromBaseline(best.hyperParameters)
            learn(champion, development)
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            holdoutCandidate = evaluate(champion, holdout).metrics()
            holdoutProduction = evaluate(production, holdout).metrics()
            holdoutPassed = holdoutPass(holdoutCandidate, holdoutProduction)
            if (holdoutPassed) championState = champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW)
        }

        onProgress(HistoricalCorpusTrainer.Progress("COMPLETE", hypers.size, hypers.size, when {
            championState != null -> "$sourceLabel champion passed locked holdout · ready for fresh live validation"
            holdoutOpened -> "$sourceLabel locked holdout failed · Production unchanged"
            else -> "$sourceLabel produced no robust candidate · locked holdout stayed closed"
        }))

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
            candidatesEvaluated = hypers.size,
            bestWalkForward = best,
            lockedHoldoutOpened = holdoutOpened,
            lockedHoldoutPassed = holdoutPassed,
            holdoutCandidate = holdoutCandidate,
            holdoutProduction = holdoutProduction,
            championState = championState,
            errors = errors,
            note = "$sourceLabel · actual option-premium MFE/MAE · next-bar entry · chronological walk-forward · locked holdout · unavailable historical D30/depth stays zero.",
        )
    }

    private fun buildSamples(
        index: MarketIndex,
        contract: HistoricalOptionSeries,
        candles: List<UpstoxPlusHistoricalClient.Candle>,
        config: HistoricalCorpusTrainer.Config,
    ): List<Sample> {
        if (candles.size < 70) return emptyList()
        val result = ArrayList<Sample>()
        val bars = ArrayList<SignalEngineV2.Bar>(candles.size)
        val engineConfig = SignalEngineV2.Config(minimumScore = config.minimumSignalScore)
        candles.forEachIndexed { i, candle ->
            if (candle.close <= 0.0) return@forEachIndexed
            bars += SignalEngineV2.Bar(candle.open, candle.high, candle.low, candle.close, candle.volume)
            if (i < 60 || i % config.sampleStrideBars != 0 || i + 1 >= candles.size) return@forEachIndexed
            val evaluation = signalEngine.evaluate(bars, engineConfig)
            if (evaluation.direction != SignalEngineV2.Direction.BULLISH || evaluation.score < config.minimumSignalScore) return@forEachIndexed
            val outcome = HistoricalPremiumLabeler.label(candles, i, contract.lotSize, config.labelConfig) ?: return@forEachIndexed
            val range = max(candle.high - candle.low, 0.01)
            val orderFlow = ((candle.close - candle.open) / range).coerceIn(-1.0, 1.0)
            val oiImpulse = openInterestImpulse(candles, i)
            val optionFlow = (orderFlow * min(evaluation.volumeRatio, 3.0) / 3.0).coerceIn(-1.0, 1.0)
            val acceleration = acceleration(candles, i)
            val extensionAtr = if (evaluation.atr > 0.0) abs(candle.close - evaluation.ema50) / evaluation.atr else 0.0
            val entryQuality = (min(evaluation.adx, 40.0) / 40.0 * 16.0 + min(evaluation.atrExpansion, 2.0) / 2.0 * 12.0 + min(evaluation.volumeRatio, 2.0) / 2.0 * 12.0).coerceIn(0.0, 40.0)
            val engine = historicalEngineProxy(evaluation, oiImpulse)
            val side = if (contract.optionType == "CE") PositionSide.CE else PositionSide.PE
            val local = candle.time
            val minutesFromOpen = (local.hour * 60 + local.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble()
            val features = NumericalMetaBrain.Features(
                engine = engine,
                index = index,
                side = side,
                engineConfidence = evaluation.score.toDouble(),
                directionScore = (evaluation.score * 0.60).coerceIn(0.0, 60.0),
                entryQualityScore = entryQuality,
                orderFlow = orderFlow,
                relativeActivity = evaluation.volumeRatio,
                oiImpulse = oiImpulse,
                optionFlow = optionFlow,
                acceleration = acceleration,
                extensionAtr = extensionAtr,
                depthImbalance = 0.0,
                micropricePressure = 0.0,
                totalBookPressure = 0.0,
                wallPressure = 0.0,
                depthLevels = 0.0,
                minutesFromOpen = minutesFromOpen,
                recentEngineWinRate = 50.0,
                recentEngineProfitFactor = 1.0,
            )
            result += Sample(
                timestamp = candles[i + 1].time.toInstant().toEpochMilli(),
                features = features,
                success = outcome.success,
                weight = if (outcome.exitReason == HistoricalPremiumLabeler.ExitReason.TIMEOUT) 0.75 else 1.25,
                mfeReturn = outcome.mfeReturn,
                maeReturn = outcome.maeReturn,
                netReturn = outcome.netReturn,
                side = side,
                engine = engine,
            )
        }
        return result
    }

    private fun evaluateWalkForward(development: List<Sample>, hyper: NumericalMetaBrain.HyperParameters, requestedFolds: Int): HistoricalCorpusTrainer.CandidateEvaluation {
        val blocks = requestedFolds + 1
        val candidateTotal = Accumulator()
        val productionTotal = Accumulator()
        var foldsRun = 0
        var foldsWon = 0
        for (fold in 1..requestedFolds) {
            val trainEnd = development.size * fold / blocks
            val validateEnd = if (fold == requestedFolds) development.size else development.size * (fold + 1) / blocks
            if (trainEnd < 20 || validateEnd <= trainEnd) continue
            val train = development.subList(0, trainEnd)
            val validation = development.subList(trainEnd, validateEnd)
            val candidate = brainFromBaseline(hyper)
            learn(candidate, train)
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            val cm = evaluate(candidate, validation)
            val pm = evaluate(production, validation)
            candidateTotal.merge(cm)
            productionTotal.merge(pm)
            foldsRun++
            if (foldScore(cm.metrics(), pm.metrics()) > 0.0) foldsWon++
        }
        val candidateMetrics = candidateTotal.metrics()
        val productionMetrics = productionTotal.metrics()
        val winRatio = if (foldsRun == 0) 0.0 else foldsWon.toDouble() / foldsRun
        val score = foldScore(candidateMetrics, productionMetrics) + 0.08 * (winRatio - 0.50)
        val robust = foldsRun >= 3 && foldsWon >= ceil(foldsRun * 0.75).toInt()
        return HistoricalCorpusTrainer.CandidateEvaluation(hyper, foldsRun, foldsWon, candidateMetrics, productionMetrics, score, robust)
    }

    private fun foldScore(candidate: HistoricalCorpusTrainer.Metrics, production: HistoricalCorpusTrainer.Metrics): Double {
        val accuracyGain = candidate.accuracy - production.accuracy
        val brierGain = production.brier - candidate.brier
        val takeBonus = if (candidate.takeSamples >= 10) candidate.takePrecision - 0.50 else -0.03
        val rejectBonus = if (candidate.rejectSamples >= 10) candidate.rejectPrecision - 0.50 else -0.02
        return accuracyGain + brierGain + 0.18 * takeBonus + 0.10 * rejectBonus + 0.20 * candidate.takeAverageNetReturn
    }

    private fun holdoutPass(candidate: HistoricalCorpusTrainer.Metrics, production: HistoricalCorpusTrainer.Metrics): Boolean {
        if (candidate.labels < 30) return false
        val qualityGain = candidate.accuracy - production.accuracy >= 0.005 || production.brier - candidate.brier >= 0.002
        if (!qualityGain) return false
        if (candidate.takeSamples >= 10 && candidate.takePrecision < 0.52) return false
        if (candidate.rejectSamples >= 10 && candidate.rejectPrecision < 0.52) return false
        return true
    }

    private fun learn(brain: NumericalMetaBrain, samples: List<Sample>) = samples.forEach { brain.learn(it.features, it.success, it.weight) }
    private fun evaluate(brain: NumericalMetaBrain, samples: List<Sample>): Accumulator = Accumulator().also { stats -> samples.forEach { stats.add(brain.predict(it.features), it) } }

    private fun brainFromBaseline(hyper: NumericalMetaBrain.HyperParameters): NumericalMetaBrain = NumericalMetaBrain().apply {
        restore(productionBaseline.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = hyper.sanitized()))
    }

    private fun candidateHyperParameters(): List<NumericalMetaBrain.HyperParameters> {
        val all = ArrayList<NumericalMetaBrain.HyperParameters>()
        MetaBrainRuntime.CandidateProfile.entries.map { it.hyper.sanitized() }.forEach { base ->
            all += base
            all += base.copy(learningRate = base.learningRate * 0.80, l2 = base.l2 * 0.70, takeThreshold = base.takeThreshold + 0.015, rejectThreshold = base.rejectThreshold - 0.015).sanitized()
            all += base.copy(learningRate = base.learningRate * 1.20, l2 = base.l2 * 1.35, takeThreshold = base.takeThreshold - 0.015, rejectThreshold = base.rejectThreshold + 0.015).sanitized()
        }
        return all.distinctBy { "%.6f|%.7f|%.4f|%.4f".format(it.learningRate, it.l2, it.takeThreshold, it.rejectThreshold) }
    }

    private fun historicalEngineProxy(e: SignalEngineV2.Evaluation, oiImpulse: Double): EngineId = when {
        e.volumeRatio >= 1.40 || abs(oiImpulse) >= 0.04 -> EngineId.ENGINE_2_AVWAP_LIQUIDITY
        e.atrExpansion >= 1.25 -> EngineId.ENGINE_3_V76_SCALPER
        else -> EngineId.ENGINE_1_TREND
    }

    private fun openInterestImpulse(candles: List<UpstoxPlusHistoricalClient.Candle>, index: Int): Double {
        val current = candles[index].openInterest.toDouble()
        if (current <= 0.0 || index <= 0) return 0.0
        val prior = candles.subList(max(0, index - 20), index).map { it.openInterest.toDouble() }.filter { it > 0.0 }
        if (prior.isEmpty()) return 0.0
        val average = prior.average()
        return ((current - average) / max(abs(average), 1.0)).coerceIn(-1.0, 1.0)
    }

    private fun acceleration(candles: List<UpstoxPlusHistoricalClient.Candle>, index: Int): Double {
        if (index < 2) return 0.0
        val a = candles[index - 2].close
        val b = candles[index - 1].close
        val c = candles[index].close
        if (a <= 0.0 || b <= 0.0) return 0.0
        return ((((c - b) / b) - ((b - a) / a)) * 1_000.0).coerceIn(-3.0, 3.0)
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
