package com.parmod.ema.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Shared, deterministic signal-quality core for live paper trading and historical replay.
 * It intentionally produces a direction/score only; execution and position sizing remain
 * in their respective risk-controlled layers.
 */
class SignalEngineV2 {
    enum class Direction { BULLISH, BEARISH, NEUTRAL }

    data class Bar(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 0,
    )

    data class Config(
        val fastEma: Int = 20,
        val slowEma: Int = 50,
        val atrPeriod: Int = 14,
        val adxPeriod: Int = 14,
        val minimumAdx: Double = 24.0,
        val minimumAtrExpansion: Double = 1.08,
        val minimumVolumeRatio: Double = 1.05,
        val minimumScore: Int = 85,
        val efficiencyLookback: Int = 24,
        val minimumEfficiencyRatio: Double = 0.34,
        val maximumEmaCrosses: Int = 2,
        val breakoutLookback: Int = 18,
        val breakoutAtrBuffer: Double = 0.10,
    )

    data class Evaluation(
        val direction: Direction,
        val score: Int,
        val ema20: Double,
        val ema50: Double,
        val atr: Double,
        val adx: Double,
        val atrExpansion: Double,
        val volumeRatio: Double,
        val higherTimeframeAligned: Boolean,
        val reasons: List<String>,
    ) {
        val actionable: Boolean get() = direction != Direction.NEUTRAL && score >= 85
    }

    fun evaluate(bars: List<Bar>, config: Config = Config()): Evaluation {
        val minimumBars = max(
            max(config.slowEma + 5, config.adxPeriod * 2 + 2),
            max(config.efficiencyLookback + 2, config.breakoutLookback + 2),
        )
        if (bars.size < minimumBars) return neutral("Collecting bars ${bars.size}/$minimumBars")

        val closes = bars.map { it.close }
        val emaFast = ema(closes, config.fastEma)
        val emaSlow = ema(closes, config.slowEma)
        val previousFast = ema(closes.dropLast(3), config.fastEma)
        val previousSlow = ema(closes.dropLast(3), config.slowEma)
        val fastSlope = emaFast - previousFast
        val slowSlope = emaSlow - previousSlow
        val atrNow = atr(bars, config.atrPeriod)
        val priorBars = bars.dropLast(config.atrPeriod.coerceAtMost(bars.size / 3))
        val atrPrior = atr(priorBars, config.atrPeriod).takeIf { it > 0.0 } ?: atrNow
        val atrExpansion = if (atrPrior > 0.0) atrNow / atrPrior else 0.0
        val adx = adx(bars, config.adxPeriod)
        val volumeRatio = volumeRatio(bars, 20)

        val higherBars = aggregate(bars, 5)
        val higherFast = ema(higherBars.map { it.close }, 4)
        val higherSlow = ema(higherBars.map { it.close }, 10)

        val bullish = emaFast > emaSlow && fastSlope > 0.0 && slowSlope > 0.0
        val bearish = emaFast < emaSlow && fastSlope < 0.0 && slowSlope < 0.0
        val higherAligned = (bullish && higherFast > higherSlow) || (bearish && higherFast < higherSlow)
        val separationAtr = if (atrNow > 0.0) abs(emaFast - emaSlow) / atrNow else 0.0
        val efficiency = efficiencyRatio(closes, config.efficiencyLookback)
        val emaCrosses = emaCrossCount(closes, config.fastEma, config.slowEma, config.efficiencyLookback)
        val lastClose = closes.last()
        val priorRange = bars.dropLast(1).takeLast(config.breakoutLookback)
        val recentHigh = priorRange.maxOf { it.high }
        val recentLow = priorRange.minOf { it.low }
        val bullishBreakout = lastClose > recentHigh + atrNow * config.breakoutAtrBuffer
        val bearishBreakout = lastClose < recentLow - atrNow * config.breakoutAtrBuffer
        val breakoutConfirmed = (bullish && bullishBreakout) || (bearish && bearishBreakout)

        var score = 0
        val reasons = mutableListOf<String>()
        if (bullish || bearish) { score += 25; reasons += "EMA20/50 trend aligned" }
        if (higherAligned) { score += 20; reasons += "5-minute trend confirmed" }
        if (adx >= config.minimumAdx) { score += if (adx >= 28.0) 18 else 14; reasons += "Trend strength ADX ${adx.toInt()}" }
        if (atrExpansion >= config.minimumAtrExpansion) { score += 12; reasons += "ATR expansion ${format(atrExpansion)}x" }
        if (volumeRatio >= config.minimumVolumeRatio) { score += 8; reasons += "Volume ${format(volumeRatio)}x" }
        if (separationAtr >= 0.35) { score += 8; reasons += "EMA separation confirmed" }
        if (efficiency >= config.minimumEfficiencyRatio) { score += 10; reasons += "Directional efficiency ${format(efficiency)}" }
        if (emaCrosses <= config.maximumEmaCrosses) { score += 5; reasons += "Low whipsaw count $emaCrosses" }
        if (breakoutConfirmed) { score += 14; reasons += "Recent range breakout confirmed" }
        score = score.coerceIn(0, 100)

        val antiChopPass = efficiency >= config.minimumEfficiencyRatio &&
            emaCrosses <= config.maximumEmaCrosses && breakoutConfirmed
        val qualityPass = higherAligned && adx >= config.minimumAdx &&
            atrExpansion >= config.minimumAtrExpansion && separationAtr >= 0.25 && antiChopPass
        val direction = when {
            qualityPass && bullish && score >= config.minimumScore -> Direction.BULLISH
            qualityPass && bearish && score >= config.minimumScore -> Direction.BEARISH
            else -> Direction.NEUTRAL
        }
        if (direction == Direction.NEUTRAL) {
            if (efficiency < config.minimumEfficiencyRatio) reasons += "Blocked: choppy directional efficiency"
            if (emaCrosses > config.maximumEmaCrosses) reasons += "Blocked: repeated EMA whipsaws"
            if (!breakoutConfirmed) reasons += "Blocked: no genuine range breakout"
            reasons += "Quality filters not fully confirmed"
        }

        return Evaluation(direction, score, emaFast, emaSlow, atrNow, adx, atrExpansion, volumeRatio, higherAligned, reasons)
    }

    private fun aggregate(bars: List<Bar>, size: Int): List<Bar> = bars.chunked(size).mapNotNull { chunk ->
        if (chunk.size < size) return@mapNotNull null
        Bar(chunk.first().open, chunk.maxOf { it.high }, chunk.minOf { it.low }, chunk.last().close, chunk.sumOf { it.volume })
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val subset = values.takeLast(period.coerceAtMost(values.size))
        val k = 2.0 / (period + 1.0)
        var result = subset.first()
        subset.drop(1).forEach { result = it * k + result * (1.0 - k) }
        return result
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1.0)
        val result = ArrayList<Double>(values.size)
        var current = values.first()
        result += current
        for (value in values.drop(1)) {
            current = value * k + current * (1.0 - k)
            result += current
        }
        return result
    }

    private fun emaCrossCount(values: List<Double>, fastPeriod: Int, slowPeriod: Int, lookback: Int): Int {
        val fast = emaSeries(values, fastPeriod)
        val slow = emaSeries(values, slowPeriod)
        val start = (values.size - lookback).coerceAtLeast(1)
        var crosses = 0
        for (i in start until values.size) {
            val previous = fast[i - 1] - slow[i - 1]
            val current = fast[i] - slow[i]
            if ((previous > 0 && current <= 0) || (previous < 0 && current >= 0)) crosses++
        }
        return crosses
    }

    private fun efficiencyRatio(values: List<Double>, lookback: Int): Double {
        val sample = values.takeLast(lookback + 1)
        if (sample.size < 2) return 0.0
        val net = abs(sample.last() - sample.first())
        val path = sample.zipWithNext().sumOf { (a, b) -> abs(b - a) }
        return if (path > 0.0) (net / path).coerceIn(0.0, 1.0) else 0.0
    }

    private fun atr(bars: List<Bar>, period: Int): Double {
        if (bars.size < 2) return 0.0
        return bars.zipWithNext().takeLast(period).map { (previous, current) ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }.average()
    }

    private fun adx(bars: List<Bar>, period: Int): Double {
        if (bars.size < period + 2) return 0.0
        val sample = bars.takeLast(period + 1)
        var plusDm = 0.0
        var minusDm = 0.0
        var trueRange = 0.0
        sample.zipWithNext().forEach { (previous, current) ->
            val up = current.high - previous.high
            val down = previous.low - current.low
            if (up > down && up > 0) plusDm += up
            if (down > up && down > 0) minusDm += down
            trueRange += max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }
        if (trueRange <= 0.0) return 0.0
        val plusDi = 100.0 * plusDm / trueRange
        val minusDi = 100.0 * minusDm / trueRange
        val denominator = plusDi + minusDi
        return if (denominator <= 0.0) 0.0 else 100.0 * abs(plusDi - minusDi) / denominator
    }

    private fun volumeRatio(bars: List<Bar>, period: Int): Double {
        val sample = bars.takeLast(period + 1)
        if (sample.size < 2 || sample.dropLast(1).all { it.volume <= 0 }) return 1.0
        val average = sample.dropLast(1).map { it.volume.toDouble() }.average()
        return if (average > 0.0) sample.last().volume / average else 1.0
    }

    private fun neutral(reason: String) = Evaluation(Direction.NEUTRAL, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, false, listOf(reason))
    private fun format(value: Double) = "%.2f".format(value)
}
