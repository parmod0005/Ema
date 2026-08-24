package com.parmod.ema.training

import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Side-effect-free E1/E2 tick signal core for parallel NIFTY/SENSEX AI research. */
class MarketTickResearchCore {
    data class Tick(val price: Double, val timestamp: Long)
    data class Result(val engine1: SignalSnapshot, val engine2: SignalSnapshot, val tickCount: Int)

    private val ticks = ArrayDeque<Tick>()
    private var cumulativePv = 0.0
    private var cumulativeWeight = 0.0

    @Synchronized fun reset() {
        ticks.clear()
        cumulativePv = 0.0
        cumulativeWeight = 0.0
    }

    @Synchronized fun ingest(price: Double, timestamp: Long) {
        if (price <= 0.0 || timestamp <= 0L) return
        val last = ticks.lastOrNull()
        if (last != null && timestamp < last.timestamp) return
        if (last != null && last.timestamp == timestamp && last.price == price) return
        ticks.addLast(Tick(price, timestamp))
        cumulativePv += price
        cumulativeWeight += 1.0
        while (ticks.size > MAX_TICKS) ticks.removeFirst()
    }

    @Synchronized fun evaluate(): Result {
        val list = ticks.toList()
        return Result(evaluateTrend(list), evaluateAvwap(list), list.size)
    }

    private fun evaluateTrend(t: List<Tick>): SignalSnapshot {
        if (t.size < TREND_MIN_TICKS) return wait("Fast warm-up ${t.size}/$TREND_MIN_TICKS ticks", "TICK TREND WARMING")
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
        val extension = if (microAtr > 0.0) abs(current - fast) / microAtr else 0.0
        val impulseLookback = 28.coerceAtMost(prices.size - 1)
        val impulseBase = prices[prices.size - 1 - impulseLookback]
        val impulseAtr = if (microAtr > 0.0) abs(current - impulseBase) / microAtr else 0.0
        val overshoot = when {
            bullBreak && microAtr > 0.0 -> (current - high) / microAtr
            bearBreak && microAtr > 0.0 -> (low - current) / microAtr
            else -> 0.0
        }
        val overextended = extension > MAX_FAST_EXTENSION_ATR || impulseAtr > MAX_IMPULSE_ATR || overshoot > MAX_BREAKOUT_OVERSHOOT_ATR
        var score = 0
        val reasons = mutableListOf<String>()
        if (bullish || bearish) { score += 30; reasons += "Tick EMA21/55 aligned" }
        if (efficiency >= 0.30) { score += 22; reasons += "Tick efficiency ${fmt(efficiency)}" }
        if (crosses <= 3) { score += 14; reasons += "Low tick whipsaw count $crosses" }
        if (separation >= 0.70) { score += 14; reasons += "Tick EMA separation confirmed" }
        if (breakout) { score += 20; reasons += "Tick-range breakout confirmed" }
        score = score.coerceIn(0, 100)
        val trend = if (bullish) TrendDirection.BULLISH else if (bearish) TrendDirection.BEARISH else TrendDirection.NEUTRAL
        if (overextended && trend != TrendDirection.NEUTRAL) {
            reasons += "OVEREXTENDED · WAIT FOR PULLBACK"
            return SignalSnapshot(SignalAction.WAIT, score, trend, null, null, null, reasons, "OVEREXTENDED · WAIT FOR PULLBACK")
        }
        val risk = max(microAtr * 7.0, current * 0.00065)
        return when {
            bullish && antiChop && bullBreak && separation >= 0.55 && score >= 82 -> SignalSnapshot(
                SignalAction.BUY_CE, score, TrendDirection.BULLISH, current, current - risk, current + risk * 1.8, reasons,
                "TICK TREND + BREAKOUT + ANTI-CHOP",
            )
            bearish && antiChop && bearBreak && separation >= 0.55 && score >= 82 -> SignalSnapshot(
                SignalAction.BUY_PE, score, TrendDirection.BEARISH, current, current + risk, current - risk * 1.8, reasons,
                "TICK TREND + BREAKOUT + ANTI-CHOP",
            )
            else -> SignalSnapshot(SignalAction.WAIT, score, trend, null, null, null, reasons, "TICK ANTI-CHOP WAIT")
        }
    }

    private fun evaluateAvwap(t: List<Tick>): SignalSnapshot {
        if (t.size < AVWAP_MIN_TICKS) return wait("Fast warm-up ${t.size}/$AVWAP_MIN_TICKS ticks", "TICK AVWAP WARMING")
        val prices = t.map { it.price }
        val current = prices.last()
        val avwap = if (cumulativeWeight > 0.0) cumulativePv / cumulativeWeight else current
        val atr = tickAtr(prices, 80)
        val poc = tickProfilePoc(prices.takeLast(1200), atr)
        val flow = orderFlowProxy(prices.takeLast(100))
        val lookback = 140.coerceAtMost((prices.size - 1).coerceAtLeast(20))
        val sweepSample = prices.dropLast(1).takeLast(lookback)
        val priorLow = sweepSample.minOrNull() ?: current
        val priorHigh = sweepSample.maxOrNull() ?: current
        val recent = prices.takeLast(12.coerceAtMost(prices.size))
        val reclaimBuffer = max(atr * 0.15, current * 0.000025)
        val bullishSweep = (recent.minOrNull() ?: current) < priorLow - reclaimBuffer && current > priorLow
        val bearishSweep = (recent.maxOrNull() ?: current) > priorHigh + reclaimBuffer && current < priorHigh
        val bullParts = listOf(current > avwap + atr * 0.30, current > poc, bullishSweep, flow >= 0.16).count { it }
        val bearParts = listOf(current < avwap - atr * 0.30, current < poc, bearishSweep, flow <= -0.16).count { it }
        val fullBull = bullParts == 4
        val fullBear = bearParts == 4
        val candidateTrend = when {
            bullParts >= 3 && bullParts > bearParts -> TrendDirection.BULLISH
            bearParts >= 3 && bearParts > bullParts -> TrendDirection.BEARISH
            else -> TrendDirection.NEUTRAL
        }
        val confidence = when (max(bullParts, bearParts)) { 4 -> 94; 3 -> 78; 2 -> 62; else -> 40 }
        val reasons = mutableListOf("Tick AVWAP ${fmt(avwap)} · POC ${fmt(poc)}", "Tick order-flow proxy ${fmt(flow)}")
        val risk = max(atr * 8.0, current * 0.00070)
        return when {
            fullBull -> SignalSnapshot(SignalAction.BUY_CE, confidence, TrendDirection.BULLISH, current, current - risk, current + risk * 2.0, reasons, "TICK AVWAP + PROFILE + SWEEP + ORDER FLOW")
            fullBear -> SignalSnapshot(SignalAction.BUY_PE, confidence, TrendDirection.BEARISH, current, current + risk, current - risk * 2.0, reasons, "TICK AVWAP + PROFILE + SWEEP + ORDER FLOW")
            else -> SignalSnapshot(SignalAction.WAIT, confidence, candidateTrend, null, null, null, reasons, "TICK D30 CANDIDATE")
        }
    }

    fun diagnosticFeatures(signal: SignalSnapshot): Pair<Double, Double> {
        val trendSign = if (signal.trend == TrendDirection.BEARISH) -1.0 else 1.0
        val prices = synchronized(this) { ticks.map { it.price } }
        if (prices.size < 2) return 0.0 to 0.0
        val atr = tickAtr(prices, 80)
        val flow = orderFlowProxy(prices.takeLast(100)) * trendSign
        val current = prices.last()
        val ema21 = ema(prices, 21)
        val extension = if (atr > 0.0) abs(current - ema21) / atr else 0.0
        return flow to extension
    }

    private fun tickAtr(values: List<Double>, lookback: Int): Double {
        val s = values.takeLast(lookback + 1)
        if (s.size < 2) return 0.0
        return max(s.zipWithNext().map { (a, b) -> abs(b - a) }.average() * sqrt(8.0), values.last() * 0.00002)
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
        s.drop(1).forEach { v = it * k + v * (1.0 - k) }
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
        values.forEach { p ->
            val key = (p / step).toLong()
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val key = buckets.maxByOrNull { it.value }?.key ?: return values.last()
        return (key + 0.5) * step
    }

    private fun wait(reason: String, setup: String) = SignalSnapshot(
        SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf(reason), setup,
    )

    private fun fmt(v: Double) = "%.2f".format(v)

    companion object {
        private const val TREND_MIN_TICKS = 40
        private const val AVWAP_MIN_TICKS = 60
        private const val MAX_TICKS = 5000
        private const val MAX_FAST_EXTENSION_ATR = 4.8
        private const val MAX_IMPULSE_ATR = 8.0
        private const val MAX_BREAKOUT_OVERSHOOT_ATR = 3.0
    }
}
