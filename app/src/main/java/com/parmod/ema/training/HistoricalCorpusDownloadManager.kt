package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs

/**
 * Resumable read-only Upstox historical downloader for NIFTY/SENSEX expired CE/PE data.
 *
 * Each candle request is marked complete only after the deterministic local store has
 * atomically verified the contract file. A cancelled/restarted download therefore
 * resumes without re-fetching already completed request windows.
 */
class HistoricalCorpusDownloadManager(
    context: Context,
    private val store: DownloadedHistoricalCorpusStore = DownloadedHistoricalCorpusStore(context),
    private val cacheDirectory: File = File(context.filesDir, "upstox_backtest_cache/v1"),
) {
    data class Progress(
        val stage: String,
        val completed: Int,
        val total: Int,
        val message: String,
        val cacheHits: Long = 0L,
        val networkRequests: Long = 0L,
    )

    data class Result(
        val summary: LocalCorpusSummary,
        val expiries: Int,
        val contractsPlanned: Int,
        val contractsDownloaded: Int,
        val contractsSkipped: Int,
        val rowsAdded: Long,
        val duplicatesRemoved: Long,
        val errors: List<String>,
        val stats: UpstoxPlusHistoricalClient.RequestStats,
    )

    private data class Work(
        val index: MarketIndex,
        val contract: UpstoxPlusHistoricalClient.ExpiredContract,
        val fromDate: LocalDate,
        val toDate: LocalDate,
    )

    private val appContext = context.applicationContext
    private val markerRoot = File(appContext.filesDir, "vardhani_historical_download_markers/v$SCHEMA").apply { mkdirs() }

    fun download(
        accessToken: String,
        scope: HistoricalMarketScope,
        months: Int,
        strikesEachSide: Int = DEFAULT_STRIKES_EACH_SIDE,
        today: LocalDate = LocalDate.now(),
        onProgress: (Progress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): Result {
        require(accessToken.isNotBlank()) { "Upstox access token is required for historical download" }
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        require(strikesEachSide in ALLOWED_STRIKE_RADII)
        val client = UpstoxPlusHistoricalClient(accessToken = accessToken, cacheDirectory = cacheDirectory)
        val markets = scope.singleIndexOrNull()?.let(::listOf) ?: listOf(MarketIndex.NIFTY, MarketIndex.SENSEX)
        val work = mutableListOf<Work>()
        var expiryCount = 0
        val errors = mutableListOf<String>()

        markets.forEachIndexed { marketIndex, market ->
            if (shouldCancel()) error("Historical download cancelled")
            onProgress(progress("DISCOVERY", marketIndex, markets.size, "Discovering ${market.name} expired option history…", client))
            val available = runCatching { client.getExpiries(market) }
                .getOrElse { error("Could not discover ${market.name} expiries: ${it.message}") }
            val expiries = HistoricalDownloadPlanner.expiries(available, months, today)
            expiryCount += expiries.size
            expiries.forEachIndexed { expiryIndex, expiry ->
                if (shouldCancel()) error("Historical download cancelled")
                onProgress(progress("DISCOVERY", expiryIndex + 1, expiries.size.coerceAtLeast(1), "${market.name} · scanning expiry $expiry", client))
                runCatching {
                    val contracts = client.getExpiredOptionContracts(market, expiry)
                    val selected = HistoricalDownloadPlanner.selectContracts(contracts, strikesEachSide)
                    val windowStart = HistoricalDownloadPlanner.windowStart(months, today)
                    selected.forEach { contract ->
                        val naturalStart = contract.expiry.minusDays(CONTRACT_LOOKBACK_DAYS)
                        val from = if (windowStart == null || naturalStart.isAfter(windowStart)) naturalStart else windowStart
                        if (!from.isAfter(contract.expiry)) work += Work(market, contract, from, contract.expiry)
                    }
                }.onFailure { errors += "${market.name} $expiry discovery: ${it.message ?: it::class.java.simpleName}" }
            }
        }

        val distinctWork = work.distinctBy { requestIdentity(it) }
        var downloaded = 0
        var skipped = 0
        var rowsAdded = 0L
        var duplicates = 0L
        distinctWork.forEachIndexed { i, item ->
            if (shouldCancel()) error("Historical download cancelled")
            val label = "${item.index.name} ${item.contract.expiry} ${item.contract.strike.toInt()} ${item.contract.optionType}"
            val marker = markerFile(item)
            if (marker.isFile && store.hasContract(item.index, item.contract.expiry, item.contract.strike, item.contract.optionType)) {
                skipped++
                onProgress(progress("DOWNLOAD", i + 1, distinctWork.size, "$label · already saved · resume skip", client))
                return@forEachIndexed
            }
            onProgress(progress("DOWNLOAD", i, distinctWork.size, "$label · downloading 1-minute candles…", client))
            runCatching {
                val candles = client.getExpiredCandles(item.contract.instrumentKey, INTERVAL, item.fromDate, item.toDate)
                require(candles.isNotEmpty()) { "No candles returned" }
                val saved = store.saveSeries(
                    HistoricalOptionSeries(
                        index = item.index,
                        optionType = item.contract.optionType,
                        strike = item.contract.strike,
                        expiry = item.contract.expiry,
                        lotSize = item.contract.lotSize.coerceAtLeast(1),
                        symbol = item.contract.tradingSymbol,
                        source = "UPSTOX_DOWNLOADED",
                        candles = candles,
                    ),
                )
                rowsAdded += saved.addedRows
                duplicates += saved.duplicateRows
                writeMarker(marker, item, saved.totalRows)
                downloaded++
            }.onFailure { e ->
                errors += "$label: ${(e.message ?: e::class.java.simpleName).take(220)}"
            }
            onProgress(progress("DOWNLOAD", i + 1, distinctWork.size, "$label · saved · phone corpus ${store.summary().optionContracts} contracts", client))
        }

        val summary = store.summary()
        val stats = client.requestStats()
        onProgress(
            Progress(
                stage = "DOWNLOAD_COMPLETE",
                completed = distinctWork.size,
                total = distinctWork.size,
                message = "Historical download complete · NIFTY ${summary.niftyContracts} · SENSEX ${summary.sensexContracts} · ${summary.rowsAccepted} unique rows",
                cacheHits = stats.cacheHits,
                networkRequests = stats.requests,
            ),
        )
        return Result(summary, expiryCount, distinctWork.size, downloaded, skipped, rowsAdded, duplicates, errors.distinct().takeLast(50), stats)
    }

    fun summary(): LocalCorpusSummary = store.summary()

    @Synchronized
    fun clearDownloadedCorpus() {
        store.clear()
        markerRoot.deleteRecursively()
        markerRoot.mkdirs()
    }

    private fun progress(stage: String, completed: Int, total: Int, message: String, client: UpstoxPlusHistoricalClient): Progress {
        val stats = client.requestStats()
        return Progress(stage, completed, total, message, stats.cacheHits, stats.requests)
    }

    private fun markerFile(item: Work): File = File(markerRoot, sha256(requestIdentity(item)) + ".ok")

    private fun writeMarker(file: File, item: Work, rows: Int) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(
            buildString {
                append("market=").append(item.index.name).append('\n')
                append("instrument=").append(item.contract.instrumentKey).append('\n')
                append("expiry=").append(item.contract.expiry).append('\n')
                append("strike=").append(item.contract.strike).append('\n')
                append("type=").append(item.contract.optionType).append('\n')
                append("from=").append(item.fromDate).append('\n')
                append("to=").append(item.toDate).append('\n')
                append("rows=").append(rows).append('\n')
                append("completed_at=").append(System.currentTimeMillis()).append('\n')
            },
            Charsets.UTF_8,
        )
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Could not finalize historical download marker" }
    }

    private fun requestIdentity(item: Work): String =
        "${item.index.name}|${item.contract.instrumentKey}|$INTERVAL|${item.fromDate}|${item.toDate}"

    companion object {
        const val DEFAULT_STRIKES_EACH_SIDE = 5
        val ALLOWED_STRIKE_RADII: Set<Int> = setOf(2, 5, 10)
        private const val SCHEMA = 1
        private const val INTERVAL = "1minute"
        private const val CONTRACT_LOOKBACK_DAYS = 7L

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Pure deterministic planning helpers kept testable without Android/network dependencies. */
object HistoricalDownloadPlanner {
    fun expiries(available: List<LocalDate>, months: Int, today: LocalDate): List<LocalDate> {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val closed = available.filter { !it.isAfter(today) }.distinct().sorted()
        if (months == PrelabelledTrainingWindowPlan.FULL) return closed
        val from = today.minusMonths(months.toLong())
        return closed.filter { !it.isBefore(from) }
    }

    fun windowStart(months: Int, today: LocalDate): LocalDate? =
        if (months == PrelabelledTrainingWindowPlan.FULL) null else today.minusMonths(months.toLong())

    fun selectContracts(
        contracts: List<UpstoxPlusHistoricalClient.ExpiredContract>,
        strikesEachSide: Int,
    ): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        require(strikesEachSide in HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII)
        if (contracts.isEmpty()) return emptyList()
        val strikes = contracts.map { it.strike }.filter { it > 0.0 }.distinct().sorted()
        if (strikes.isEmpty()) return emptyList()
        val centre = strikes[strikes.size / 2]
        val selected = strikes.sortedBy { abs(it - centre) }.take(strikesEachSide * 2 + 1).toSet()
        return contracts.asSequence()
            .filter { it.optionType == "CE" || it.optionType == "PE" }
            .filter { it.strike in selected }
            .distinctBy { "${it.instrumentKey}|${it.optionType}|${it.strike}" }
            .sortedWith(compareBy<UpstoxPlusHistoricalClient.ExpiredContract> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
            .toList()
    }
}
