package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.time.LocalDate

/** Downloads expired-option series using the resumable Upstox cache and causal strike selection. */
class UpstoxCorpusSeriesLoader(private val client: UpstoxPlusHistoricalClient) {
    data class Result(
        val series: List<HistoricalOptionSeries>,
        val expiries: Int,
        val contractsAttempted: Int,
        val errors: List<String>,
    )

    private data class Work(
        val contract: UpstoxPlusHistoricalClient.ExpiredContract,
        val start: LocalDate,
        val referenceSpot: Double?,
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
        require(months.toInt() in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        require(strikesEachSide in HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII)
        onProgress(HistoricalCorpusTrainer.Progress("DISCOVERY", 0, 1, "Discovering expired ${index.name} option expiries…"))
        val available = client.getExpiries(index).filter { it.isBefore(today) }.distinct().sorted()
        if (available.isEmpty()) return Result(emptyList(), 0, 0, listOf("${index.name}: Upstox returned no completed expired-option expiries"))

        val requestedFrom = if (months == PrelabelledTrainingWindowPlan.FULL.toLong()) null else today.minusMonths(months)
        val expiries = if (requestedFrom == null) available else available.filter { !it.isBefore(requestedFrom) }
        val errors = mutableListOf<String>()
        if (requestedFrom != null && available.first().isAfter(requestedFrom.plusDays(7))) {
            errors += "${index.name}: requested ${months}M but Upstox expiry discovery currently starts at ${available.first()}"
        } else if (requestedFrom == null) {
            errors += "${index.name}: FULL means every expired expiry currently returned by Upstox; older locally accumulated data is not invented"
        }

        val work = mutableListOf<Work>()
        expiries.forEachIndexed { i, expiry ->
            if (shouldCancel()) error("Training cancelled")
            onProgress(HistoricalCorpusTrainer.Progress("DISCOVERY", i + 1, expiries.size.coerceAtLeast(1), "${index.name} · planning expiry $expiry"))
            val naturalStart = expiry.minusDays(CONTRACT_LOOKBACK_DAYS)
            val start = if (requestedFrom == null || naturalStart.isAfter(requestedFrom)) naturalStart else requestedFrom
            if (start.isAfter(expiry)) return@forEachIndexed

            val referenceSpot = runCatching {
                client.getHistoricalUnderlyingDailyCandles(
                    index = index,
                    fromDate = start.minusDays(SPOT_REFERENCE_LOOKBACK_DAYS),
                    toDate = start,
                ).asSequence()
                    .filter { !it.time.toLocalDate().isAfter(start) && it.close > 0.0 }
                    .maxByOrNull { it.time.toInstant().toEpochMilli() }
                    ?.close
            }.onFailure {
                errors += "$index $expiry: causal underlying reference unavailable (${it.message}); deterministic centre-strike fallback used"
            }.getOrNull()

            runCatching {
                val selected = HistoricalDownloadPlanner.selectContracts(
                    contracts = client.getExpiredOptionContracts(index, expiry),
                    strikesEachSide = strikesEachSide,
                    referenceSpot = referenceSpot,
                )
                if (selected.isEmpty()) errors += "$index $expiry: no usable CE/PE contracts"
                selected.forEach { work += Work(it, start, referenceSpot) }
            }.onFailure { errors += "$index $expiry contract discovery: ${it.message}" }
        }

        val result = mutableListOf<HistoricalOptionSeries>()
        work.forEachIndexed { i, item ->
            if (shouldCancel()) error("Training cancelled")
            val spot = item.referenceSpot?.let { " · spot %.2f".format(it) } ?: " · centre fallback"
            onProgress(
                HistoricalCorpusTrainer.Progress(
                    "DOWNLOAD",
                    i,
                    work.size,
                    "${item.contract.expiry} ${item.contract.strike.toInt()} ${item.contract.optionType}$spot",
                ),
            )
            runCatching {
                val candles = client.getExpiredCandles(item.contract.instrumentKey, interval, item.start, item.contract.expiry)
                if (candles.isNotEmpty()) {
                    result += HistoricalOptionSeries(
                        index = index,
                        optionType = item.contract.optionType,
                        strike = item.contract.strike,
                        expiry = item.contract.expiry,
                        lotSize = item.contract.lotSize.coerceAtLeast(1),
                        symbol = item.contract.tradingSymbol,
                        source = "UPSTOX_PLUS",
                        candles = candles,
                    )
                } else {
                    errors += "${item.contract.expiry} ${item.contract.strike.toInt()} ${item.contract.optionType}: no candles returned"
                }
            }.onFailure { errors += "${item.contract.expiry} ${item.contract.strike.toInt()} ${item.contract.optionType}: ${it.message}" }
        }
        return Result(result, expiries.size, work.size, errors.distinct().takeLast(80))
    }

    private companion object {
        const val CONTRACT_LOOKBACK_DAYS = 7L
        const val SPOT_REFERENCE_LOOKBACK_DAYS = 10L
    }
}

object HistoricalSeriesMerger {
    fun merge(series: List<HistoricalOptionSeries>): List<HistoricalOptionSeries> =
        series.groupBy { it.key }.map { (_, group) ->
            val ordered = group.sortedByDescending { sourcePriority(it.source) }
            val preferred = ordered.first()
            val candles = ordered.flatMap { it.candles }
                .sortedBy { it.time.toInstant().toEpochMilli() }
                .distinctBy { it.time.toInstant().toEpochMilli() }
            preferred.copy(
                source = ordered.map { it.source }.distinct().joinToString("+"),
                candles = candles,
            )
        }.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })

    private fun sourcePriority(source: String): Int = when {
        source.contains("UPSTOX_PLUS") -> 4
        source.contains("UPSTOX_DOWNLOADED") -> 3
        source.contains("LOCAL_IMPORT") -> 2
        else -> 1
    }
}
