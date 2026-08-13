package com.parmod.ema.engine

import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tick-native signal core. Every underlying tick is ingested; no completed candle is required.
 * Engine 1: tick EMA trend + directional efficiency + breakout/anti-chop.
 * Engine 2: tick AVWAP + tick-profile POC + liquidity sweep + uptick/downtick order-flow proxy.
 *
 * Fast warm-up is intentionally supported for slower index feeds such as SENSEX. The initial
 * evaluation thresholds are small, while all rolling lookbacks naturally deepen as more ticks
 * arrive. Entry quality gates and full-confluence requirements remain unchanged.
 */
class TickNativeDualEngine {
    data class Tick(val price: Double, val timestamp: Long)
    data class Result(val engine1: SignalSnapshot, val engine2: SignalSnapshot, val tickCount: Int)

    private val ticks = ArrayDeque<Tick>()
    private var cumulativePv = 0.0
    private var cumulativeWeight = 0.0

    fun reset() {
        ticks.clear()
        cumulativePv = 0.0
        cumulativeWeight = 0.0
    }

    fun ingest(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        ticks.addLast(Tick(price, timestamp))
        cumulativePv += price
        cumulativeWeight += 1.0
        while (ticks.size > MAX_TICKS) ticks.removeFirst()
    }

    fun evaluate(): Result {
        val list = ticks.toList()
        return Result(evaluateTrend(list), evaluateAvwap(list), list.size)
    }

    private fun evaluateTrend(t: List<Tick>): SignalSnapshot {
        if (t.size < TREND_MIN_TICKS) {
            return wait("Fast warm-up ${t.size}/$TREND_MIN_TICKS ticks", "TICK TREND WARMING")
        }
        val prices = t.map { it.price }
        val fast = ema(prices, 21)
        val slow = ema(prices, 55)
        val slopeGap = 12.coerceAtMost((prices.size / 4).coerceAtLeast(3))
        val prevFast = ema(prices.dropLast(slopeGap), 21)
        val prevSlow = ema(prices.dropLast(slopeGap), 55)
        val fastSlope = fast - prevFast
        val slowSlope = slow - prevSlow
        val microAtr = tickAtr(prices, 60)
        val separation = if (microAtr > 0.0) abs(fast - slow) / microAtr else 0.0
        val efficiency = efficiencyRatio(prices, 80.coerceAtMost(prices.size - 1))
        val crosses = emaCrossCount(prices, 21, 55, 100.coerceAtMost(prices.size))
        val current = prices.last()
        val prior = prices.dropLast(1).takeLast(100)
        val high = prior.maxOrNull() ?: current
        val low = prior.minOrNull() ?: current
        val buffer = max(microAtr * 0.30, current * 0.00004)
        val bullBreak = current > high + buffer
        val bearBreak = current < low - buffer
        val bullish = fast > slow && fastSlope > 0.0 && slowSlope >= 0.0
        val bearish = fast < slow && fastSlope < 0.0 && slowSlope <= 0.0
        val antiChop = efficiency >= 0.30 && crosses <= 3
        val breakout = (bullish && bullBreak) || (bearish && bearBreak)

        var score = 0
        val reasons = mutableListOf<String>()
        if (bullish || bearish) { score += 30; reasons += "Tick EMA21/55 aligned" }
        if (efficiency >= 0.30) { score += 22; reasons += "Tick efficiency ${fmt(efficiency)}" }
        if (crosses <= 3) { score += 14; reasons += "Low tick whipsaw count $crosses" }
        if (separation >= 0.70) { score += 14; reasons += "Tick EMA separation confirmed" }
        if (breakout) { score += 20; reasons += "Tick-range breakout confirmed" }
        if (prices.size < 80) reasons += "Fast-start mode · depth ${prices.size} ticks"
        score = score.coerceIn(0, 100)

        val risk = max(microAtr * 7.0, current * 0.00065)
        return when {
            bullish && antiChop && bullBreak && separation >= 0.55 && score >= 82 ->
                SignalSnapshot(SignalAction.BUY_CE, score, TrendDirection.BULLISH, current, current - risk, current + risk * 1.8, reasons, "TICK TREND + BREAKOUT + ANTI-CHOP")
            bearish && antiChop && bearBreak && separation >= 0.55 && score >= 82 ->
                SignalSnapshot(SignalAction.BUY_PE, score, TrendDirection.BEARISH, current, current + risk, current - risk * 1.8, reasons, "TICK TREND + BREAKOUT + ANTI-CHOP")
            else -> {
                if (!antiChop) reasons += "Blocked: tick chop/whipsaw"
                if (!breakout) reasons += "Blocked: no tick-range breakout"
                SignalSnapshot(SignalAction.WAIT, score, TrendDirection.NEUTRAL, null, null, null, reasons, "TICK ANTI-CHOP WAIT")
            }
        }
    }

    private fun evaluateAvwap(t: List<Tick>): SignalSnapshot {
        if (t.size < AVWAP_MIN_TICKS) {
            return wait("Fast warm-up ${t.size}/$AVWAP_MIN_TICKS ticks", "TICK AVWAP WARMING")
        }
        val prices = t.map { it.price }
        val current = prices.last()
        val avwap = if (cumulativeWeight > 0.0) cumulativePv / cumulativeWeight else current
        val atr = tickAtr(prices, 80)
        val poc = tickProfilePoc(prices.takeLast(1200), atr)
        val flow = orderFlowProxy(prices.takeLast(100))

        val sweepLookback = 140.coerceAtMost((prices.size - 1).coerceAtLeast(20))
        val sweepSample = prices.dropLast(1).takeLast(sweepLookback)
        val priorLow = sweepSample.minOrNull() ?: current
        val priorHigh = sweepSample.maxOrNull() ?: current
        val recent = prices.takeLast(12.coerceAtMost(prices.size))
        val recentLow = recent.minOrNull() ?: current
        val recentHigh = recent.maxOrNull() ?: current
        val reclaimBuffer = max(atr * 0.15, current * 0.000025)
        val bullishSweep = recentLow < priorLow - reclaimBuffer && current > priorLow
        val bearishSweep = recentHigh > priorHigh + reclaimBuffer && current < priorHigh

        val aboveAvwap = current > avwap + atr * 0.30
        val belowAvwap = current < avwap - atr * 0.30
        val abovePoc = current > poc
        val belowPoc = current < poc
        val bullishFlow = flow >= 0.16
        val bearishFlow = flow <= -0.16
        val bullParts = listOf(aboveAvwap, abovePoc, bullishSweep, bullishFlow).count { it }
        val bearParts = listOf(belowAvwap, belowPoc, bearishSweep, bearishFlow).count { it }
        val fullBull = bullParts == 4
        val fullBear = bearParts == 4

        val setup = when {
            fullBull || fullBear -> "TICK AVWAP + PROFILE + SWEEP + ORDER FLOW · FULL CONFLUENCE"
            max(bullParts, bearParts) == 3 -> "TICK THREE-FACTOR CONFLUENCE"
            max(bullParts, bearParts) == 2 -> "TICK TWO-FACTOR CONFLUENCE"
            else -> "TICK NO CONFLUENCE"
        }
        val confidence = when (max(bullParts, bearParts)) { 4 -> 94; 3 -> 78; 2 -> 62; else -> 40 }
        val reasons = mutableListOf("Tick AVWAP ${fmt(avwap)} · POC ${fmt(poc)}", "Tick order-flow proxy ${fmt(flow)}")
        if (prices.size < 120) reasons += "Fast-start mode · depth ${prices.size} ticks"
        if (bullishSweep) reasons += "Sell-side liquidity swept/reclaimed on ticks"
        if (bearishSweep) reasons += "Buy-side liquidity swept/rejected on ticks"
        if (!(fullBull || fullBear)) reasons += "AUTO waits for full four-factor tick confluence"
        val risk = max(atr * 8.0, current * 0.00070)

        return when {
            fullBull -> SignalSnapshot(SignalAction.BUY_CE, confidence, TrendDirection.BULLISH, current, current - risk, current + risk * 2.0, reasons, setup)
            fullBear -> SignalSnapshot(SignalAction.BUY_PE, confidence, TrendDirection.BEARISH, current, current + risk, current - risk * 2.0, reasons, setup)
            else -> SignalSnapshot(SignalAction.WAIT, confidence, TrendDirection.NEUTRAL, null, null, null, reasons, setup)
        }
    }

    private fun tickAtr(values: List<Double>, lookback: Int): Double {
        val s = values.takeLast(lookback + 1)
        if (s.size < 2) return 0.0
        val meanMove = s.zipWithNext().map { (a, b) -> abs(b - a) }.average()
        return max(meanMove * sqrt(8.0), values.last() * 0.00002)
    }

    private fun efficiencyRatio(values: List<Double>, lookback: Int): Double {
        val s = values.takeLast(lookback + 1)
        if (s.size < 2) return 0.0
        val net = abs(s.last() - s.first())
        val path = s.zipWithNext().sumOf { (a, b) -> abs(b - a) }
        return if (path > 0.0) (net / path).coerceIn(0.0, 1.0) else 0.0
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val s = values.takeLast(period.coerceAtMost(values.size))
        if (s.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var v = s.first()
        for (x in s.drop(1)) v = x * k + v * (1.0 - k)
        return v
    }

    private fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1.0)
        var v = values.first()
        return values.mapIndexed { i, x -> if (i == 0) v else { v = x * k + v * (1.0 - k); v } }
    }

    private fun emaCrossCount(values: List<Double>, fast: Int, slow: Int, lookback: Int): Int {
        val f = emaSeries(values, fast)
        val s = emaSeries(values, slow)
        val start = (values.size - lookback).coerceAtLeast(1)
        var crosses = 0
        for (i in start until values.size) {
            val a = f[i - 1] - s[i - 1]
            val b = f[i] - s[i]
            if ((a > 0 && b <= 0) || (a < 0 && b >= 0)) crosses++
        }
        return crosses
    }

    private fun orderFlowProxy(values: List<Double>): Double {
        var buy = 0
        var sell = 0
        values.zipWithNext().forEach { (a, b) -> if (b > a) buy++ else if (b < a) sell++ }
        val n = buy + sell
        return if (n == 0) 0.0 else ((buy - sell).toDouble() / n).coerceIn(-1.0, 1.0)
    }

    private fun tickProfilePoc(values: List<Double>, atr: Double): Double {
        if (values.isEmpty()) return 0.0
        val step = max(atr * 0.75, values.last() * 0.00003)
        val buckets = linkedMapOf<Long, Int>()
        values.forEach { p -> val key = (p / step).toLong(); buckets[key] = (buckets[key] ?: 0) + 1 }
        val key = buckets.maxByOrNull { it.value }?.key ?: return values.last()
        return (key + 0.5) * step
    }

    private fun wait(reason: String, setup: String) = SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf(reason), setup)
    private fun fmt(v: Double) = "%.2f".format(v)

    companion object {
        private const val TREND_MIN_TICKS = 40
        private const val AVWAP_MIN_TICKS = 60
        private const val MAX_TICKS = 5000
    }
}
