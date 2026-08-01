package com.parmod.ema.backtest

import java.time.LocalDate
import kotlin.math.abs

/**
 * Replays all candidate option contracts on one account timeline.
 * Enforces one open position, daily limits, capital exhaustion, slippage and costs.
 */
class AccountChronologicalReplayEngine {
    data class Config(
        val startingCapital: Double = 100_000.0,
        val fastPeriod: Int = 8,
        val slowPeriod: Int = 20,
        val minimumConfidence: Int = 85,
        val stopLossPct: Double = 0.15,
        val targetPct: Double = 0.30,
        val cooldownMinutes: Long = 5,
        val maximumTradesPerDay: Int = 4,
        val maximumDailyLoss: Double = 1_000.0,
        val slippagePctEachSide: Double = 0.0015,
        val flatChargesPerRoundTrip: Double = 50.0,
        val trainFraction: Double = 0.67,
    )

    data class Series(
        val optionType: String,
        val strike: Double,
        val expiry: String,
        val lotSize: Int,
        val candles: List<UpstoxPlusHistoricalClient.Candle>,
    )

    data class Result(
        val allTrades: List<BacktestEngine.Trade>,
        val trainReport: BacktestEngine.Report,
        val testReport: BacktestEngine.Report,
        val endingCapital: Double,
        val maxAccountDrawdown: Double,
        val rejectedSignals: Int,
        val capitalExhausted: Boolean,
    )

    private data class Candidate(
        val series: Series,
        val index: Int,
        val score: Int,
    )

    fun replay(series: List<Series>, config: Config = Config()): Result {
        if (series.isEmpty()) return emptyResult(config.startingCapital)
        val byTime = sortedMapOf<Long, MutableList<Candidate>>()
        series.forEach { s ->
            val closes = ArrayList<Double>()
            s.candles.forEachIndexed { i, c ->
                closes += c.close
                if (closes.size < config.slowPeriod || c.close <= 0.0) return@forEachIndexed
                val fast = ema(closes, config.fastPeriod)
                val slow = ema(closes, config.slowPeriod)
                val previousFast = ema(closes.dropLast(1), config.fastPeriod)
                val slope = fast - previousFast
                val separation = abs(fast - slow) / c.close
                val match = if (s.optionType == "CE") fast > slow && slope > 0 else fast < slow && slope < 0
                if (!match) return@forEachIndexed
                val score = (65 + minOf(20, (abs(slope) / c.close * 120_000).toInt()) + if (separation > 0.00005) 10 else 0).coerceAtMost(95)
                if (score >= config.minimumConfidence) {
                    byTime.getOrPut(c.time.toInstant().toEpochMilli()) { mutableListOf() } += Candidate(s, i, score)
                }
            }
        }

        val allTimes = byTime.keys.toList()
        val splitTime = allTimes.getOrNull((allTimes.size * config.trainFraction).toInt().coerceIn(0, (allTimes.size - 1).coerceAtLeast(0))) ?: Long.MAX_VALUE
        val trades = ArrayList<BacktestEngine.Trade>()
        var capital = config.startingCapital
        var peak = capital
        var maxDrawdown = 0.0
        var open: Candidate? = null
        var entryPrice = 0.0
        var entryTime = 0L
        var cooldownUntil = 0L
        var currentDay: LocalDate? = null
        var tradesToday = 0
        var dailyPnl = 0.0
        var rejected = 0
        var exhausted = false

        for ((time, candidates) in byTime) {
            val day = candidates.first().series.candles[candidates.first().index].time.toLocalDate()
            if (day != currentDay) {
                currentDay = day
                tradesToday = 0
                dailyPnl = 0.0
            }

            val active = open
            if (active != null) {
                val candle = active.series.candles.firstOrNull { it.time.toInstant().toEpochMilli() == time }
                if (candle != null) {
                    val pct = (candle.close - entryPrice) / entryPrice
                    val reverse = candidates.any { it.series.optionType != active.series.optionType }
                    if (pct <= -config.stopLossPct || pct >= config.targetPct || reverse) {
                        val exit = candle.close * (1.0 - config.slippagePctEachSide)
                        val entry = entryPrice * (1.0 + config.slippagePctEachSide)
                        val gross = (exit - entry) * active.series.lotSize
                        val net = gross - config.flatChargesPerRoundTrip
                        val adjustedExit = entry + net / active.series.lotSize
                        val trade = BacktestEngine.Trade(entryTime, time, active.series.optionType, entry, adjustedExit, active.series.lotSize, active.score, active.series.expiry)
                        trades += trade
                        capital += trade.pnl
                        dailyPnl += trade.pnl
                        peak = maxOf(peak, capital)
                        maxDrawdown = maxOf(maxDrawdown, peak - capital)
                        open = null
                        cooldownUntil = time + config.cooldownMinutes * 60_000L
                        if (capital <= 0.0) { exhausted = true; break }
                    }
                }
            }

            if (open == null) {
                if (time < cooldownUntil || tradesToday >= config.maximumTradesPerDay || dailyPnl <= -config.maximumDailyLoss || capital <= 0.0) {
                    rejected += candidates.size
                    continue
                }
                val best = candidates.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { -abs(it.series.strike) }) ?: continue
                val candle = best.series.candles[best.index]
                val required = candle.close * best.series.lotSize
                if (required > capital) { rejected++; continue }
                open = best
                entryPrice = candle.close
                entryTime = time
                tradesToday++
            } else {
                rejected += candidates.size
            }
        }

        val train = trades.filter { it.entryEpochMs <= splitTime }
        val test = trades.filter { it.entryEpochMs > splitTime }
        return Result(
            allTrades = trades,
            trainReport = BacktestEngine().evaluate(train),
            testReport = BacktestEngine().evaluate(test),
            endingCapital = capital,
            maxAccountDrawdown = maxDrawdown,
            rejectedSignals = rejected,
            capitalExhausted = exhausted,
        )
    }

    private fun emptyResult(capital: Double) = Result(emptyList(), BacktestEngine().evaluate(emptyList()), BacktestEngine().evaluate(emptyList()), capital, 0.0, 0, false)

    private fun ema(values: List<Double>, period: Int): Double {
        val subset = values.takeLast(period)
        if (subset.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var result = subset.first()
        subset.drop(1).forEach { result = it * k + result * (1.0 - k) }
        return result
    }
}
