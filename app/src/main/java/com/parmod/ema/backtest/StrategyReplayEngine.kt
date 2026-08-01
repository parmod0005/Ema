package com.parmod.ema.backtest

import kotlin.math.abs

class StrategyReplayEngine {
    data class Config(
        val fastPeriod: Int = 8,
        val slowPeriod: Int = 20,
        val minimumConfidence: Int = 80,
        val stopLossPct: Double = 0.15,
        val targetPct: Double = 0.30,
        val cooldownBars: Int = 3,
        val maximumTradesPerDay: Int = 4,
    )

    data class ContractSeries(
        val optionType: String,
        val strike: Double,
        val expiry: String,
        val lotSize: Int,
        val candles: List<UpstoxPlusHistoricalClient.Candle>,
    )

    data class ReplayResult(
        val trades: List<BacktestEngine.Trade>,
        val report: BacktestEngine.Report,
        val processedBars: Int,
        val rejectedSignals: Int,
    )

    fun replay(series: ContractSeries, config: Config = Config()): ReplayResult {
        require(series.optionType == "CE" || series.optionType == "PE")
        if (series.candles.size < config.slowPeriod + 2) {
            return ReplayResult(emptyList(), BacktestEngine().evaluate(emptyList()), series.candles.size, 0)
        }

        val closes = ArrayList<Double>()
        val trades = ArrayList<BacktestEngine.Trade>()
        var openIndex: Int? = null
        var entryPrice = 0.0
        var entryScore = 0
        var cooldown = 0
        var rejected = 0
        var currentDay = series.candles.first().time.toLocalDate()
        var tradesToday = 0

        series.candles.forEachIndexed { index, candle ->
            if (candle.time.toLocalDate() != currentDay) {
                currentDay = candle.time.toLocalDate()
                tradesToday = 0
                cooldown = 0
            }
            closes += candle.close
            if (closes.size < config.slowPeriod) return@forEachIndexed
            if (cooldown > 0) cooldown--

            val fast = ema(closes, config.fastPeriod)
            val slow = ema(closes, config.slowPeriod)
            val previous = ema(closes.dropLast(1), config.fastPeriod)
            val slope = fast - previous
            val separation = if (candle.close == 0.0) 0.0 else abs(fast - slow) / candle.close
            val trendMatches = if (series.optionType == "CE") fast > slow && slope > 0 else fast < slow && slope < 0
            val score = (65 + minOf(20, (abs(slope) / candle.close * 120_000).toInt()) + if (separation > 0.00005) 10 else 0).coerceAtMost(95)

            val openedAt = openIndex
            if (openedAt != null) {
                val pct = if (entryPrice == 0.0) 0.0 else (candle.close - entryPrice) / entryPrice
                val target = pct >= config.targetPct
                val stop = pct <= -config.stopLossPct
                val reversed = !trendMatches && index - openedAt > 0
                val endOfSeries = index == series.candles.lastIndex
                if (target || stop || reversed || endOfSeries) {
                    trades += BacktestEngine.Trade(
                        entryEpochMs = series.candles[openedAt].time.toInstant().toEpochMilli(),
                        exitEpochMs = candle.time.toInstant().toEpochMilli(),
                        side = series.optionType,
                        entryPrice = entryPrice,
                        exitPrice = candle.close,
                        quantity = series.lotSize,
                        signalScore = entryScore,
                        expiry = series.expiry,
                    )
                    openIndex = null
                    cooldown = config.cooldownBars
                }
            } else if (trendMatches && score >= config.minimumConfidence) {
                if (cooldown == 0 && tradesToday < config.maximumTradesPerDay && candle.close > 0.0) {
                    openIndex = index
                    entryPrice = candle.close
                    entryScore = score
                    tradesToday++
                } else {
                    rejected++
                }
            }
        }

        return ReplayResult(
            trades = trades,
            report = BacktestEngine().evaluate(trades),
            processedBars = series.candles.size,
            rejectedSignals = rejected,
        )
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val start = (values.size - period).coerceAtLeast(0)
        val subset = values.subList(start, values.size)
        val k = 2.0 / (period + 1.0)
        var result = subset.first()
        subset.drop(1).forEach { result = it * k + result * (1.0 - k) }
        return result
    }
}
