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

/**
 * On-device historical research trainer for the VARDHANI Numerical Meta Brain.
 *
 * Safety / validity rules:
 * - chronological only; never random-shuffles time-series samples,
 * - signal features use bars available at signal time only,
 * - option execution is next-bar open,
 * - labels use the actual future option-premium path including MFE/MAE,
 * - ambiguous same-candle target/stop is treated as stop-first,
 * - a locked chronological holdout is opened only after walk-forward robustness,
 * - the result is returned as a Candidate state; Production is never modified here,
 * - unavailable historical D30/depth fields stay zero and are explicitly reported as proxy/missing.
 */
class HistoricalCorpusTrainer(
    private val client: UpstoxPlusHistoricalClient,
    private val productionBaseline: NumericalMetaBrain.ModelState,
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
    data class Config(
        val months: Long = 1,
        val interval: String = "1minute",
        val strikesEachSide: Int = 2,
        val sampleStrideBars: Int = 3,
        val minimumSignalScore: Int = 72,
        val developmentFraction: Double = 0.85,
        val walkForwardFolds: Int = 4,
        val minimumCorpusSamples: Int = 120,
        val labelConfig: HistoricalPremiumLabeler.Config = HistoricalPremiumLabeler.Config(),
    )

    data class Progress(
        val stage: String,
        val completed: Int,
        val total: Int,
        val message: String,
    )

    data class Coverage(
        val ceSamples: Int,
        val peSamples: Int,
        val engine1Samples: Int,
        val engine2Samples: Int,
        val engine3Samples: Int,
        val nativeDepthSamples: Int,
    )

    data class Metrics(
        val labels: Long = 0,
        val accuracy: Double = 0.0,
        val brier: Double = 1.0,
        val takeSamples: Long = 0,
        val takePrecision: Double = 0.0,
        val rejectSamples: Long = 0,
        val rejectPrecision: Double = 0.0,
        val takeAverageNetReturn: Double = 0.0,
    )

    data class CandidateEvaluation(
        val hyperParameters: NumericalMetaBrain.HyperParameters,
        val foldsRun: Int,
        val foldsWon: Int,
        val candidate: Metrics,
        val production: Metrics,
        val score: Double,
        val robust: Boolean,
    )

    data class Result(
        val index: MarketIndex,
        val months: Long,
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val expiries: Int,
        val contractsDownloaded: Int,
        val corpusSamples: Int,
        val coverage: Coverage,
        val averageMfeReturn: Double,
        val averageMaeReturn: Double,
        val averageNetReturn: Double,
        val candidatesEvaluated: Int,
        val bestWalkForward: CandidateEvaluation?,
        val lockedHoldoutOpened: Boolean,
        val lockedHoldoutPassed: Boolean,
        val holdoutCandidate: Metrics?,
        val holdoutProduction: Metrics?,
        val championState: NumericalMetaBrain.ModelState?,
        val errors: List<String>,
        val nativeHistoricalDepthAvailable: Boolean = false,
        val note: String = "Historical D30/order-book depth is unavailable in expired OHLCV/OI candles; those feature slots remain zero rather than being fabricated.",
    )

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

        fun metrics(): Metrics = Metrics(
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
        today: LocalDate = LocalDate.now(),
        config: Config = Config(),
        onProgress: (Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(config.months in setOf(1L, 3L, 6L, 12L))
        require(config.strikesEachSide in 1..6)
        require(config.sampleStrideBars >= 1)
        require(config.walkForwardFolds in 2..6)
        require(config.developmentFraction in 0.70..0.92)

        val from = today.minusMonths(config.months)
        onProgress(Progress("DISCOVERY", 0, 1, "Discovering expired ${index.name} option expiries…"))
        val expiries = client.getExpiries(index).filter { !it.isBefore(from) && !it.isAfter(today) }
        val work = expiries.flatMap { expiry ->
            if (shouldCancel()) error("Training cancelled")
            selectResearchContracts(client.getExpiredOptionContracts(index, expiry), config.strikesEachSide)
        }

        val samples = ArrayList<Sample>()
        val errors = ArrayList<String>()
        var ceSamples = 0
        var peSamples = 0
        var e1Samples = 0
        var e2Samples = 0
        var e3Samples = 0

        work.forEachIndexed { contractIndex, contract ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(
                Progress(
                    "CORPUS",
                    contractIndex,
                    work.size,
                    "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType} · ${samples.size} causal samples",
                ),
            )
            runCatching {
                val start = contract.expiry.minusDays(7).coerceAtLeast(from)
                val candles = client.getExpiredCandles(contract.instrumentKey, config.interval, start, contract.expiry)
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
            }.onFailure { failure ->
                errors += "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType}: ${failure.message}"
            }
        }

        val corpus = samples.sortedBy { it.timestamp }
        onProgress(Progress("CORPUS", work.size, work.size, "Corpus ready · ${corpus.size} samples"))
        if (corpus.size < config.minimumCorpusSamples) {
            return Result(
                index = index,
                months = config.months,
                fromDate = from,
                toDate = today,
                expiries = expiries.size,
                contractsDownloaded = work.size,
                corpusSamples = corpus.size,
                coverage = Coverage(ceSamples, peSamples, e1Samples, e2Samples, e3Samples, 0),
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
            )
        }

        val developmentEnd = (corpus.size * config.developmentFraction).toInt()
            .coerceIn(config.walkForwardFolds + 2, corpus.size - 1)
        val development = corpus.subList(0, developmentEnd)
        val holdout = corpus.subList(developmentEnd, corpus.size)
        val hypers = candidateHyperParameters()
        val evaluations = ArrayList<CandidateEvaluation>()

        hypers.forEachIndexed { i, hyper ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(Progress("WALK_FORWARD", i, hypers.size, "Candidate ${i + 1}/${hypers.size} · chronological folds"))
            evaluations += evaluateWalkForward(development, hyper, config.walkForwardFolds)
        }
        val best = evaluations.maxByOrNull { it.score }
        val robust = best?.robust == true

        var holdoutOpened = false
        var holdoutPassed = false
        var holdoutCandidate: Metrics? = null
        var holdoutProduction: Metrics? = null
        var championState: NumericalMetaBrain.ModelState? = null

        if (robust && best != null) {
            holdoutOpened = true
            onProgress(Progress("LOCKED_HOLDOUT", 0, 1, "Walk-forward robust · opening final chronological holdout once"))
            val champion = brainFromBaseline(best.hyperParameters)
            learn(champion, development)
            val production = brainFromBaseline(productionBaseline.hyperParameters)
            holdoutCandidate = evaluate(champion, holdout).metrics()
            holdoutProduction = evaluate(production, holdout).metrics()
            holdoutPassed = holdoutPass(holdoutCandidate, holdoutProduction)
            if (holdoutPassed) championState = champion.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW)
        }

        onProgress(
            Progress(
                "COMPLETE",
                hypers.size,
                hypers.size,
                when {
                    championState != null -> "Historical champion passed locked holdout · ready for fresh live shadow validation"
                    holdoutOpened -> "Locked holdout failed · Production unchanged"
                    else -> "No walk-forward-robust candidate · locked holdout stayed closed"
                },
            ),
        )

        return Result(
            index = index,
            months = config.months,
            fromDate = from,
            toDate = today,
            expiries = expiries.size,
            contractsDownloaded = work.size,
            corpusSamples = corpus.size,
            coverage = Coverage(ceSamples, peSamples, e1Samples, e2Samples, e3Samples, 0),
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
        )
    }

    private fun buildSamples(
        index: MarketIndex,
        contract: UpstoxPlusHistoricalClient.ExpiredContract,
        candles: List<UpstoxPlusHistoricalClient.Candle>,
        config: Config,
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
            val entryQuality = (
                min(evaluation.adx, 40.0) / 40.0 * 16.0 +
                    min(evaluation.atrExpansion, 2.0) / 2.0 * 12.0 +
                    min(evaluation.volumeRatio, 2.0) / 2.0 * 12.0
                ).coerceIn(0.0, 40.0)
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

    private fun evaluateWalkForward(
        development: List<Sample>,
        hyper: NumericalMetaBrain.HyperParameters,
        requestedFolds: Int,
    ): CandidateEvaluation {
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
        return CandidateEvaluation(hyper, foldsRun, foldsWon, candidateMetrics, productionMetrics, score, robust)
    }

    private fun foldScore(candidate: Metrics, production: Metrics): Double {
        val accuracyGain = candidate.accuracy - production.accuracy
        val brierGain = production.brier - candidate.brier
        val takeBonus = if (candidate.takeSamples >= 10) candidate.takePrecision - 0.50 else -0.03
        val rejectBonus = if (candidate.rejectSamples >= 10) candidate.rejectPrecision - 0.50 else -0.02
        return accuracyGain + brierGain + 0.18 * takeBonus + 0.10 * rejectBonus + 0.20 * candidate.takeAverageNetReturn
    }

    private fun holdoutPass(candidate: Metrics, production: Metrics): Boolean {
        if (candidate.labels < 30) return false
        val qualityGain = candidate.accuracy - production.accuracy >= 0.005 || production.brier - candidate.brier >= 0.002
        if (!qualityGain) return false
        if (candidate.takeSamples >= 10 && candidate.takePrecision < 0.52) return false
        if (candidate.rejectSamples >= 10 && candidate.rejectPrecision < 0.52) return false
        return true
    }

    private fun learn(brain: NumericalMetaBrain, samples: List<Sample>) {
        samples.forEach { sample -> brain.learn(sample.features, sample.success, sample.weight) }
    }

    private fun evaluate(brain: NumericalMetaBrain, samples: List<Sample>): Accumulator {
        val stats = Accumulator()
        samples.forEach { sample -> stats.add(brain.predict(sample.features), sample) }
        return stats
    }

    private fun brainFromBaseline(hyper: NumericalMetaBrain.HyperParameters): NumericalMetaBrain =
        NumericalMetaBrain().apply {
            restore(
                productionBaseline.copy(
                    mode = NumericalMetaBrain.Mode.SHADOW,
                    hyperParameters = hyper.sanitized(),
                ),
            )
        }

    private fun candidateHyperParameters(): List<NumericalMetaBrain.HyperParameters> {
        val seeds = MetaBrainRuntime.CandidateProfile.entries.map { it.hyper.sanitized() }
        val all = ArrayList<NumericalMetaBrain.HyperParameters>()
        seeds.forEach { base ->
            all += base
            all += base.copy(
                learningRate = base.learningRate * 0.80,
                l2 = base.l2 * 0.70,
                takeThreshold = base.takeThreshold + 0.015,
                rejectThreshold = base.rejectThreshold - 0.015,
            ).sanitized()
            all += base.copy(
                learningRate = base.learningRate * 1.20,
                l2 = base.l2 * 1.35,
                takeThreshold = base.takeThreshold - 0.015,
                rejectThreshold = base.rejectThreshold + 0.015,
            ).sanitized()
        }
        return all.distinctBy { signature(it) }
    }

    private fun signature(h: NumericalMetaBrain.HyperParameters): String =
        "%.6f|%.7f|%.4f|%.4f".format(h.learningRate, h.l2, h.takeThreshold, h.rejectThreshold)

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
        val prior = (b - a) / a
        val current = (c - b) / b
        return ((current - prior) * 1_000.0).coerceIn(-3.0, 3.0)
    }

    private fun selectResearchContracts(
        contracts: List<UpstoxPlusHistoricalClient.ExpiredContract>,
        eachSide: Int,
    ): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        if (contracts.isEmpty()) return emptyList()
        val strikes = contracts.map { it.strike }.distinct().sorted()
        val centre = strikes[strikes.size / 2]
        val selected = strikes.sortedBy { abs(it - centre) }.take(eachSide * 2 + 1).toSet()
        return contracts.filter { it.strike in selected }.sortedWith(
            compareBy<UpstoxPlusHistoricalClient.ExpiredContract> { it.expiry }
                .thenBy { it.strike }
                .thenBy { it.optionType },
        )
    }

    private fun LocalDate.coerceAtLeast(minimum: LocalDate): LocalDate = if (isBefore(minimum)) minimum else this
    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
