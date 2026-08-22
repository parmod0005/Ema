package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.engine.SignalEngineV2
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.PositionSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared causal sample builder for raw/downloaded/direct-Upstox historical option series.
 *
 * Preferred path:
 *  - NIFTY/SENSEX underlying 1m bars determine direction and trend-quality features.
 *  - CE requires bullish underlying confirmation; PE requires bearish confirmation.
 *  - option premium/OI determines the trade outcome and option-side pressure.
 *
 * Legacy imported raw corpora may not contain underlying bars. Those retain the historical
 * option-premium proxy path for backwards compatibility, and callers report that fallback.
 */
internal object HistoricalSeriesSampleBuilder {
    data class Sample(
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

    data class Result(
        val samples: List<Sample>,
        val nativeUnderlying: Boolean,
        val alignedUnderlyingBars: Int,
    )

    fun build(
        contract: HistoricalOptionSeries,
        optionCandlesInput: List<UpstoxPlusHistoricalClient.Candle>,
        config: HistoricalCorpusTrainer.Config,
        signalEngine: SignalEngineV2,
    ): Result {
        val optionCandles = optionCandlesInput
            .filter { it.close > 0.0 }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
        if (optionCandles.size < MIN_OPTION_BARS) return Result(emptyList(), contract.hasNativeUnderlyingContext, 0)

        val underlying = contract.underlyingCandles
            .filter { it.close > 0.0 }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
        return if (underlying.size >= MIN_SIGNAL_BARS) {
            buildNative(contract, optionCandles, underlying, config, signalEngine)
        } else {
            buildLegacyProxy(contract, optionCandles, config, signalEngine)
        }
    }

    private fun buildNative(
        contract: HistoricalOptionSeries,
        options: List<UpstoxPlusHistoricalClient.Candle>,
        underlying: List<UpstoxPlusHistoricalClient.Candle>,
        config: HistoricalCorpusTrainer.Config,
        signalEngine: SignalEngineV2,
    ): Result {
        val out = ArrayList<Sample>()
        val underlyingBars = ArrayList<SignalEngineV2.Bar>(underlying.size)
        val underlyingSeen = ArrayList<UpstoxPlusHistoricalClient.Candle>(underlying.size)
        val engineConfig = SignalEngineV2.Config(minimumScore = config.minimumSignalScore)
        val side = side(contract)
        val sideSign = if (side == PositionSide.CE) 1.0 else -1.0
        var u = 0

        options.forEachIndexed { i, option ->
            val signalEpoch = option.time.toInstant().toEpochMilli()
            while (u < underlying.size && underlying[u].time.toInstant().toEpochMilli() <= signalEpoch) {
                val bar = underlying[u++]
                underlyingSeen += bar
                underlyingBars += SignalEngineV2.Bar(bar.open, bar.high, bar.low, bar.close, bar.volume)
            }
            if (i < MIN_OPTION_SIGNAL_INDEX || i % config.sampleStrideBars != 0 || i + 1 >= options.size) return@forEachIndexed
            if (underlyingBars.size < MIN_SIGNAL_BARS) return@forEachIndexed

            val evaluation = signalEngine.evaluate(underlyingBars, engineConfig)
            val directionMatches = when (side) {
                PositionSide.CE -> evaluation.direction == SignalEngineV2.Direction.BULLISH
                PositionSide.PE -> evaluation.direction == SignalEngineV2.Direction.BEARISH
            }
            if (!directionMatches || evaluation.score < config.minimumSignalScore) return@forEachIndexed

            val outcome = HistoricalPremiumLabeler.label(options, i, contract.lotSize, config.labelConfig) ?: return@forEachIndexed
            val indexBar = underlyingSeen.last()
            val indexRange = max(indexBar.high - indexBar.low, 0.01)
            val directionalOrderFlow = (((indexBar.close - indexBar.open) / indexRange) * sideSign).coerceIn(-1.0, 1.0)
            val directionalAcceleration = (acceleration(underlyingSeen, underlyingSeen.lastIndex) * sideSign).coerceIn(-3.0, 3.0)
            val oiImpulse = openInterestImpulse(options, i)
            val premiumRange = max(option.high - option.low, 0.01)
            val premiumOrderFlow = ((option.close - option.open) / premiumRange).coerceIn(-1.0, 1.0)
            val optionActivity = activityRatio(options, i)
            val optionFlow = (premiumOrderFlow * min(optionActivity, 3.0) / 3.0).coerceIn(-1.0, 1.0)
            val extensionAtr = if (evaluation.atr > 0.0) abs(indexBar.close - evaluation.ema50) / evaluation.atr else 0.0
            val entryQuality = entryQuality(evaluation)
            val engine = historicalEngineProxy(evaluation, oiImpulse)
            val features = NumericalMetaBrain.Features(
                engine = engine,
                index = contract.index,
                side = side,
                engineConfidence = evaluation.score.toDouble(),
                directionScore = (evaluation.score * 0.60).coerceIn(0.0, 60.0),
                entryQualityScore = entryQuality,
                orderFlow = directionalOrderFlow,
                relativeActivity = evaluation.volumeRatio,
                oiImpulse = oiImpulse,
                optionFlow = optionFlow,
                acceleration = directionalAcceleration,
                extensionAtr = extensionAtr,
                depthImbalance = 0.0,
                micropricePressure = 0.0,
                totalBookPressure = 0.0,
                wallPressure = 0.0,
                depthLevels = 0.0,
                minutesFromOpen = minutesFromOpen(option),
                recentEngineWinRate = 50.0,
                recentEngineProfitFactor = 1.0,
            )
            out += sample(options, i, features, outcome, side, engine)
        }
        return Result(out, nativeUnderlying = true, alignedUnderlyingBars = underlyingSeen.size)
    }

    private fun buildLegacyProxy(
        contract: HistoricalOptionSeries,
        options: List<UpstoxPlusHistoricalClient.Candle>,
        config: HistoricalCorpusTrainer.Config,
        signalEngine: SignalEngineV2,
    ): Result {
        val out = ArrayList<Sample>()
        val bars = ArrayList<SignalEngineV2.Bar>(options.size)
        val engineConfig = SignalEngineV2.Config(minimumScore = config.minimumSignalScore)
        val side = side(contract)
        options.forEachIndexed { i, candle ->
            bars += SignalEngineV2.Bar(candle.open, candle.high, candle.low, candle.close, candle.volume)
            if (i < MIN_OPTION_SIGNAL_INDEX || i % config.sampleStrideBars != 0 || i + 1 >= options.size) return@forEachIndexed
            val evaluation = signalEngine.evaluate(bars, engineConfig)
            // Legacy raw files contain option premiums but no underlying. A bought option
            // still needs bullish premium structure, regardless of CE/PE identity.
            if (evaluation.direction != SignalEngineV2.Direction.BULLISH || evaluation.score < config.minimumSignalScore) return@forEachIndexed
            val outcome = HistoricalPremiumLabeler.label(options, i, contract.lotSize, config.labelConfig) ?: return@forEachIndexed
            val range = max(candle.high - candle.low, 0.01)
            val premiumOrderFlow = ((candle.close - candle.open) / range).coerceIn(-1.0, 1.0)
            val oiImpulse = openInterestImpulse(options, i)
            val optionFlow = (premiumOrderFlow * min(evaluation.volumeRatio, 3.0) / 3.0).coerceIn(-1.0, 1.0)
            val premiumAcceleration = acceleration(options, i)
            val extensionAtr = if (evaluation.atr > 0.0) abs(candle.close - evaluation.ema50) / evaluation.atr else 0.0
            val engine = historicalEngineProxy(evaluation, oiImpulse)
            val features = NumericalMetaBrain.Features(
                engine = engine,
                index = contract.index,
                side = side,
                engineConfidence = evaluation.score.toDouble(),
                directionScore = (evaluation.score * 0.60).coerceIn(0.0, 60.0),
                entryQualityScore = entryQuality(evaluation),
                orderFlow = premiumOrderFlow,
                relativeActivity = evaluation.volumeRatio,
                oiImpulse = oiImpulse,
                optionFlow = optionFlow,
                acceleration = premiumAcceleration,
                extensionAtr = extensionAtr,
                depthImbalance = 0.0,
                micropricePressure = 0.0,
                totalBookPressure = 0.0,
                wallPressure = 0.0,
                depthLevels = 0.0,
                minutesFromOpen = minutesFromOpen(candle),
                recentEngineWinRate = 50.0,
                recentEngineProfitFactor = 1.0,
            )
            out += sample(options, i, features, outcome, side, engine)
        }
        return Result(out, nativeUnderlying = false, alignedUnderlyingBars = 0)
    }

    private fun sample(
        options: List<UpstoxPlusHistoricalClient.Candle>,
        i: Int,
        features: NumericalMetaBrain.Features,
        outcome: HistoricalPremiumLabeler.Outcome,
        side: PositionSide,
        engine: EngineId,
    ) = Sample(
        timestamp = options[i + 1].time.toInstant().toEpochMilli(),
        features = features,
        success = outcome.success,
        weight = if (outcome.exitReason == HistoricalPremiumLabeler.ExitReason.TIMEOUT) 0.75 else 1.25,
        mfeReturn = outcome.mfeReturn,
        maeReturn = outcome.maeReturn,
        netReturn = outcome.netReturn,
        side = side,
        engine = engine,
    )

    private fun side(contract: HistoricalOptionSeries): PositionSide =
        if (contract.optionType == "CE") PositionSide.CE else PositionSide.PE

    private fun entryQuality(e: SignalEngineV2.Evaluation): Double = (
        min(e.adx, 40.0) / 40.0 * 16.0 +
            min(e.atrExpansion, 2.0) / 2.0 * 12.0 +
            min(e.volumeRatio, 2.0) / 2.0 * 12.0
        ).coerceIn(0.0, 40.0)

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

    private fun activityRatio(candles: List<UpstoxPlusHistoricalClient.Candle>, index: Int): Double {
        if (index <= 0) return 1.0
        val current = candles[index].volume.toDouble()
        val prior = candles.subList(max(0, index - 20), index).map { it.volume.toDouble() }.filter { it > 0.0 }
        if (current <= 0.0 || prior.isEmpty()) return 1.0
        return (current / max(prior.average(), 1.0)).coerceIn(0.0, 5.0)
    }

    private fun acceleration(candles: List<UpstoxPlusHistoricalClient.Candle>, index: Int): Double {
        if (index < 2) return 0.0
        val a = candles[index - 2].close
        val b = candles[index - 1].close
        val c = candles[index].close
        if (a <= 0.0 || b <= 0.0) return 0.0
        return ((((c - b) / b) - ((b - a) / a)) * 1_000.0).coerceIn(-3.0, 3.0)
    }

    private fun minutesFromOpen(c: UpstoxPlusHistoricalClient.Candle): Double =
        (c.time.hour * 60 + c.time.minute - (9 * 60 + 15)).coerceAtLeast(0).toDouble()

    private const val MIN_OPTION_BARS = 70
    private const val MIN_OPTION_SIGNAL_INDEX = 60
    private const val MIN_SIGNAL_BARS = 60
}
