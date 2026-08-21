package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.engine.NumericalMetaBrain
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Stateful, past-only feature engineering shared by historical research paths.
 * Every value uses the current completed bar plus bars at or before it. No future
 * return/MFE/MAE field is ever consulted when features are constructed.
 */
object CausalFeatureEngineering {
    private data class Point(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val oi: Double,
        val spot: Double,
        val date: LocalDate,
        val moneynessSteps: Double,
        val daysToExpiry: Double,
    )

    class PrelabelledState {
        private val histories = HashMap<String, ArrayDeque<Point>>()

        fun observe(r: AimlHistoricalOptionCorpusV1Store.Record): NumericalMetaBrain.CausalExtras {
            val date = java.time.Instant.ofEpochMilli(r.timestampMs)
                .atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30)).toLocalDate()
            val expiry = LocalDate.ofEpochDay(r.expiryEpochDay.toLong())
            val key = "${r.index.name}|${r.expiryEpochDay}|${r.strike}|${r.optionType}"
            val q = histories.getOrPut(key) { ArrayDeque() }
            q.addLast(
                Point(
                    r.open, r.high, r.low, r.close, r.volume, r.oi, r.spot, date,
                    r.signedMoneynessSteps,
                    java.time.temporal.ChronoUnit.DAYS.between(date, expiry).coerceAtLeast(0L).toDouble(),
                ),
            )
            while (q.size > MAX_HISTORY) q.removeFirst()
            return fromPoints(q.toList())
        }
    }

    fun fromCandles(
        candles: List<UpstoxPlusHistoricalClient.Candle>,
        index: Int,
        expiry: LocalDate,
        moneynessSteps: Double = 0.0,
        spot: Double = 0.0,
    ): NumericalMetaBrain.CausalExtras {
        if (index !in candles.indices) return NumericalMetaBrain.CausalExtras()
        val start = max(0, index - MAX_HISTORY + 1)
        val points = ArrayList<Point>(index - start + 1)
        for (i in start..index) {
            val c = candles[i]
            val d = c.time.toLocalDate()
            points += Point(
                c.open, c.high, c.low, c.close, c.volume.toDouble(), c.openInterest.toDouble(),
                spot, d, moneynessSteps,
                java.time.temporal.ChronoUnit.DAYS.between(d, expiry).coerceAtLeast(0L).toDouble(),
            )
        }
        return fromPoints(points)
    }

    /** Live helper from completed/observed premium closes and underlying closes. */
    fun fromLiveCloses(
        premiumCloses: List<Double>,
        spotCloses: List<Double>,
        currentOpen: Double,
        currentHigh: Double,
        currentLow: Double,
        currentClose: Double,
        currentVolume: Double = 0.0,
        currentOi: Double = 0.0,
        previousVolume: Double = 0.0,
        previousOi: Double = 0.0,
        moneynessSteps: Double = 0.0,
        daysToExpiry: Double = 0.0,
    ): NumericalMetaBrain.CausalExtras {
        val closes = premiumCloses.takeLast(MAX_HISTORY).toMutableList()
        if (closes.isEmpty() || closes.last() != currentClose) closes += currentClose
        val spots = spotCloses.takeLast(MAX_HISTORY)
        val synthetic = closes.mapIndexed { i, c ->
            val isLast = i == closes.lastIndex
            Point(
                open = if (isLast) currentOpen else c,
                high = if (isLast) max(currentHigh, c) else c,
                low = if (isLast) min(currentLow, c) else c,
                close = c,
                volume = if (isLast) currentVolume else previousVolume,
                oi = if (isLast) currentOi else previousOi,
                spot = spots.getOrNull(spots.size - closes.size + i) ?: spots.lastOrNull() ?: 0.0,
                date = LocalDate.now(),
                moneynessSteps = moneynessSteps,
                daysToExpiry = daysToExpiry,
            )
        }
        return fromPoints(synthetic)
    }

    private fun fromPoints(points: List<Point>): NumericalMetaBrain.CausalExtras {
        if (points.isEmpty()) return NumericalMetaBrain.CausalExtras()
        val p = points.last()
        val closes = points.map { it.close }
        val spots = points.map { it.spot }
        val ranges = points.map { max(it.high - it.low, 0.0) }
        val returns = closes.zipWithNext { a, b -> if (a > 0.0) (b - a) / a else 0.0 }
        val ema9 = ema(closes, 9)
        val ema21 = ema(closes, 21)
        val ema9Prev = if (closes.size > 1) ema(closes.dropLast(1), 9) else ema9
        val z9 = zlema(closes, 9)
        val z21 = zlema(closes, 21)
        val rsi = rsi(closes, 14)
        val macdHist = macdHistogram(closes)
        val tr = trueRanges(points)
        val atr14 = tr.takeLast(14).averageOrZero()
        val baselineAtr = tr.takeLast(50).averageOrZero().takeIf { it > 1e-9 } ?: atr14.takeIf { it > 1e-9 } ?: 1.0
        val bb = bollinger(closes, 20)
        val bodyRange = max(p.high - p.low, 1e-9)
        val upperWick = max(0.0, p.high - max(p.open, p.close)) / bodyRange
        val lowerWick = max(0.0, min(p.open, p.close) - p.low) / bodyRange
        val avgVol = points.dropLast(1).takeLast(10).map { it.volume }.filter { it > 0.0 }.averageOrZero()
        val avgOi = points.dropLast(1).takeLast(10).map { it.oi }.filter { it > 0.0 }.averageOrZero()
        val rv = stddev(returns.takeLast(20))
        val persistence = if (returns.isEmpty()) 0.0 else returns.takeLast(10).map { when { it > 0 -> 1.0; it < 0 -> -1.0; else -> 0.0 } }.average()
        val spot3 = returnN(spots, 3)
        val option3 = returnN(closes, 3)
        val closeSafe = max(abs(p.close), 1e-9)

        return NumericalMetaBrain.CausalExtras(
            premiumReturn1 = returnN(closes, 1),
            premiumReturn3 = option3,
            premiumReturn5 = returnN(closes, 5),
            premiumReturn15 = returnN(closes, 15),
            emaSpread = (ema9 - ema21) / closeSafe,
            emaSlope = (ema9 - ema9Prev) / closeSafe,
            zlemaSpread = (z9 - z21) / closeSafe,
            rsi = rsi,
            macdHistogram = macdHist / closeSafe,
            atrRatio = if (baselineAtr <= 1e-9) 1.0 else atr14 / baselineAtr,
            bbPosition = bb.first,
            bbWidth = bb.second,
            bodyRatio = abs(p.close - p.open) / bodyRange,
            wickSkew = (lowerWick - upperWick).coerceIn(-1.0, 1.0),
            volumeAcceleration = ratioAcceleration(p.volume, avgVol),
            oiAcceleration = ratioAcceleration(p.oi, avgOi),
            spotReturn3 = spot3,
            optionSpotRelative = option3 - spot3,
            moneynessSteps = p.moneynessSteps,
            daysToExpiry = p.daysToExpiry,
            realizedVolatility = rv,
            momentumPersistence = persistence,
        )
    }

    private fun returnN(values: List<Double>, n: Int): Double {
        if (values.size <= n) return 0.0
        val base = values[values.lastIndex - n]
        val end = values.last()
        return if (base > 0.0) (end - base) / base else 0.0
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val alpha = 2.0 / (period + 1.0)
        var e = values.first()
        for (i in 1 until values.size) e += alpha * (values[i] - e)
        return e
    }

    private fun zlema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val lag = max(1, (period - 1) / 2)
        val adjusted = values.indices.map { i ->
            val prior = values[max(0, i - lag)]
            values[i] + (values[i] - prior)
        }
        return ema(adjusted, period)
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size < 2) return 50.0
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gain = changes.filter { it > 0.0 }.sum() / max(changes.size, 1)
        val loss = -changes.filter { it < 0.0 }.sum() / max(changes.size, 1)
        if (loss <= 1e-12) return if (gain > 0.0) 100.0 else 50.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun macdHistogram(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val macdSeries = ArrayList<Double>(values.size)
        for (i in values.indices) {
            val prefix = values.subList(0, i + 1)
            macdSeries += ema(prefix, 12) - ema(prefix, 26)
        }
        val signal = ema(macdSeries, 9)
        return macdSeries.last() - signal
    }

    private fun trueRanges(points: List<Point>): List<Double> {
        if (points.isEmpty()) return emptyList()
        return points.indices.map { i ->
            val p = points[i]
            if (i == 0) p.high - p.low
            else max(p.high - p.low, max(abs(p.high - points[i - 1].close), abs(p.low - points[i - 1].close)))
        }
    }

    /** returns z-position and relative full 4-sigma width. */
    private fun bollinger(values: List<Double>, period: Int): Pair<Double, Double> {
        val w = values.takeLast(period)
        if (w.isEmpty()) return 0.0 to 0.0
        val mean = w.average()
        val sd = stddev(w)
        val pos = if (sd <= 1e-12) 0.0 else (values.last() - mean) / (2.0 * sd)
        val width = if (abs(mean) <= 1e-12) 0.0 else 4.0 * sd / abs(mean)
        return pos to width
    }

    private fun stddev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
    }

    private fun ratioAcceleration(current: Double, baseline: Double): Double =
        if (current <= 0.0 || baseline <= 1e-9) 0.0 else ((current / baseline) - 1.0).coerceIn(-3.0, 3.0)

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
    private const val MAX_HISTORY = 80
}
