package com.parmod.ema.backtest

import com.parmod.ema.engine.SignalEngineV2
import java.time.LocalDate
import kotlin.math.abs

/**
 * Replays all candidate option contracts on one account timeline.
 * Enforces one open position, daily limits, capital exhaustion, slippage and costs.
 * Candidate generation uses the same Signal Engine v2 quality core intended for live signals.
 */
class AccountChronologicalReplayEngine(
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
    data class Config(
        val startingCapital: Double = 100_000.0,
        val minimumConfidence: Int = 80,
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
        val engineConfig = SignalEngineV2.Config(minimumScore = config.minimumConfidence)

        series.forEach { s ->
            val bars = ArrayList<SignalEngineV2.Bar>()
            s.candles.forEachIndexed { i, candle ->
                if (candle.close <= 0.0) return@forEachIndexed
                bars += SignalEngineV2.Bar(
                    open = candle.open,
                    high = candle.high,
                    low = candle.low,
                    close = candle.close,
                    volume = candle.volume,
                )
                val evaluation = signalEngine.evaluate(bars, engineConfig)
                // We buy options only when that option premium itself has a confirmed bullish expansion.
                if (evaluation.direction == SignalEngineV2.Direction.BULLISH &&
                    evaluation.score >= config.minimumConfidence
                ) {
                    byTime.getOrPut(candle.time.toInstant().toEpochMilli()) { mutableListOf() } +=
                        Candidate(s, i, evaluation.score)
                }
            }
        }

        val allTimes = byTime.keys.toList()
        val splitIndex = (allTimes.size * config.trainFraction).toInt()
            .coerceIn(0, (allTimes.size - 1).coerceAtLeast(0))
        val splitTime = allTimes.getOrNull(splitIndex) ?: Long.MAX_VALUE
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
                    val reverse = candidates.any {
                        it.series.optionType != active.series.optionType && it.score >= active.score
                    }
                    if (pct <= -config.stopLossPct || pct >= config.targetPct || reverse) {
                        val exit = candle.close * (1.0 - config.slippagePctEachSide)
                        val entry = entryPrice * (1.0 + config.slippagePctEachSide)
                        val gross = (exit - entry) * active.series.lotSize
                        val net = gross - config.flatChargesPerRoundTrip
                        val adjustedExit = entry + net / active.series.lotSize
                        val trade = BacktestEngine.Trade(
                            entryTime,
                            time,
                            active.series.optionType,
                            entry,
                            adjustedExit,
                            active.series.lotSize,
                            active.score,
                            active.series.expiry,
                        )
                        trades += trade
                        capital += trade.pnl
                        dailyPnl += trade.pnl
                        peak = maxOf(peak, capital)
                        maxDrawdown = maxOf(maxDrawdown, peak - capital)
                        open = null
                        cooldownUntil = time + config.cooldownMinutes * 60_000L
                        if (capital <= 0.0) {
                            exhausted = true
                            break
                        }
                    }
                }
            }

            if (open == null) {
                if (time < cooldownUntil ||
                    tradesToday >= config.maximumTradesPerDay ||
                    dailyPnl <= -config.maximumDailyLoss ||
                    capital <= 0.0
                ) {
                    rejected += candidates.size
                    continue
                }
                val best = candidates.maxWithOrNull(
                    compareBy<Candidate> { it.score }
                        .thenBy { -abs(it.series.strike) },
                ) ?: continue
                val candle = best.series.candles[best.index]
                val required = candle.close * best.series.lotSize
                if (required > capital) {
                    rejected++
                    continue
                }
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

    private fun emptyResult(capital: Double) = Result(
        emptyList(),
        BacktestEngine().evaluate(emptyList()),
        BacktestEngine().evaluate(emptyList()),
        capital,
        0.0,
        0,
        false,
    )
}
