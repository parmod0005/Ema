package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.time.LocalDate
import kotlin.math.abs

/** Downloads the same expired-option series used by historical AI training, using the resumable Upstox cache. */
class UpstoxCorpusSeriesLoader(private val client: UpstoxPlusHistoricalClient) {
    data class Result(
        val series: List<HistoricalOptionSeries>,
        val expiries: Int,
        val contractsAttempted: Int,
        val errors: List<String>,
    )

    fun load(
        index: MarketIndex,
        months: Long,
        interval: String = "1minute",
        strikesEachSide: Int = 2,
        today: LocalDate = LocalDate.now(),
        onProgress: (HistoricalCorpusTrainer.Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(months in setOf(1L, 3L, 6L, 12L))
        val from = today.minusMonths(months)
        onProgress(HistoricalCorpusTrainer.Progress("DISCOVERY", 0, 1, "Discovering expired ${index.name} option expiries…"))
        val expiries = client.getExpiries(index).filter { !it.isBefore(from) && !it.isAfter(today) }
        val work = expiries.flatMap { expiry ->
            if (shouldCancel()) error("Training cancelled")
            selectResearchContracts(client.getExpiredOptionContracts(index, expiry), strikesEachSide)
        }
        val result = mutableListOf<HistoricalOptionSeries>()
        val errors = mutableListOf<String>()
        work.forEachIndexed { i, contract ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(HistoricalCorpusTrainer.Progress("DOWNLOAD", i, work.size, "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType}"))
            runCatching {
                val start = contract.expiry.minusDays(7).coerceAtLeast(from)
                val candles = client.getExpiredCandles(contract.instrumentKey, interval, start, contract.expiry)
                if (candles.isNotEmpty()) {
                    result += HistoricalOptionSeries(
                        index = index,
                        optionType = contract.optionType,
                        strike = contract.strike,
                        expiry = contract.expiry,
                        lotSize = contract.lotSize,
                        symbol = contract.tradingSymbol,
                        source = "UPSTOX_PLUS",
                        candles = candles,
                    )
                }
            }.onFailure { errors += "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType}: ${it.message}" }
        }
        return Result(result, expiries.size, work.size, errors)
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
}

object HistoricalSeriesMerger {
    fun merge(series: List<HistoricalOptionSeries>): List<HistoricalOptionSeries> =
        series.groupBy { it.key }.map { (_, group) ->
            val preferred = group.maxByOrNull { if (it.source == "UPSTOX_PLUS") 2 else 1 } ?: group.first()
            val candles = group.flatMap { it.candles }
                .sortedBy { it.time.toInstant().toEpochMilli() }
                .distinctBy { it.time.toInstant().toEpochMilli() }
            preferred.copy(
                source = group.map { it.source }.distinct().joinToString("+"),
                candles = candles,
            )
        }.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
}
