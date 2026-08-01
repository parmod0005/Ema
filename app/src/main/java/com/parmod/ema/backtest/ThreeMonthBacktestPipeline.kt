package com.parmod.ema.backtest

import com.parmod.ema.model.MarketIndex
import java.time.LocalDate
import kotlin.math.abs

class ThreeMonthBacktestPipeline(
    private val client: UpstoxPlusHistoricalClient,
    private val accountReplay: AccountChronologicalReplayEngine = AccountChronologicalReplayEngine(),
) {
    data class Progress(val completed: Int, val total: Int, val message: String)
    data class Result(
        val index: MarketIndex,
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val expiries: Int,
        val contractsTested: Int,
        val candlesProcessed: Int,
        val trades: List<BacktestEngine.Trade>,
        val report: BacktestEngine.Report,
        val trainReport: BacktestEngine.Report,
        val testReport: BacktestEngine.Report,
        val endingCapital: Double,
        val maxAccountDrawdown: Double,
        val rejectedSignals: Int,
        val capitalExhausted: Boolean,
        val errors: List<String>,
    )

    fun run(
        index: MarketIndex,
        today: LocalDate = LocalDate.now(),
        interval: String = "1minute",
        strikesEachSide: Int = 5,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val from = today.minusMonths(3)
        val expiries = client.getExpiries(index).filter { !it.isBefore(from) && !it.isAfter(today) }
        val work = expiries.flatMap { expiry ->
            val contracts = client.getExpiredOptionContracts(index, expiry)
            selectResearchContracts(contracts, strikesEachSide)
        }
        val series = ArrayList<AccountChronologicalReplayEngine.Series>()
        val errors = ArrayList<String>()
        var bars = 0

        work.forEachIndexed { i, contract ->
            onProgress(Progress(i, work.size, "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType}"))
            runCatching {
                val start = contract.expiry.minusDays(7).coerceAtLeast(from)
                val candles = client.getExpiredCandles(contract.instrumentKey, interval, start, contract.expiry)
                bars += candles.size
                if (candles.isNotEmpty()) {
                    series += AccountChronologicalReplayEngine.Series(
                        optionType = contract.optionType,
                        strike = contract.strike,
                        expiry = contract.expiry.toString(),
                        lotSize = contract.lotSize,
                        candles = candles,
                    )
                }
            }.onFailure { error ->
                errors += "${contract.expiry} ${contract.strike.toInt()} ${contract.optionType}: ${error.message}"
            }
        }

        onProgress(Progress(work.size, work.size, "Running chronological account replay…"))
        val replay = accountReplay.replay(series)
        val allReport = BacktestEngine().evaluate(replay.allTrades)
        onProgress(Progress(work.size, work.size, "Backtest complete"))
        return Result(
            index = index,
            fromDate = from,
            toDate = today,
            expiries = expiries.size,
            contractsTested = work.size,
            candlesProcessed = bars,
            trades = replay.allTrades,
            report = allReport,
            trainReport = replay.trainReport,
            testReport = replay.testReport,
            endingCapital = replay.endingCapital,
            maxAccountDrawdown = replay.maxAccountDrawdown,
            rejectedSignals = replay.rejectedSignals,
            capitalExhausted = replay.capitalExhausted,
            errors = errors,
        )
    }

    private fun selectResearchContracts(
        contracts: List<UpstoxPlusHistoricalClient.ExpiredContract>,
        eachSide: Int,
    ): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        if (contracts.isEmpty()) return emptyList()
        val strikes = contracts.map { it.strike }.distinct().sorted()
        val centre = strikes[strikes.size / 2]
        val selectedStrikes = strikes.sortedBy { abs(it - centre) }.take(eachSide * 2 + 1).toSet()
        return contracts.filter { it.strike in selectedStrikes }.sortedWith(
            compareBy<UpstoxPlusHistoricalClient.ExpiredContract> { it.expiry }
                .thenBy { it.strike }
                .thenBy { it.optionType },
        )
    }

    private fun LocalDate.coerceAtLeast(minimum: LocalDate): LocalDate = if (isBefore(minimum)) minimum else this
}
