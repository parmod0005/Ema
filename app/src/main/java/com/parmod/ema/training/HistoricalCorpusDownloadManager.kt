package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Properties
import kotlin.math.abs

/**
 * Resumable read-only Upstox historical downloader for NIFTY/SENSEX expired CE/PE data.
 *
 * Planning uses the union of the persistent verified contract catalogue and fresh Upstox
 * Plus expiry discovery. This preserves older known expired instrument keys even when the
 * current discovery endpoint exposes a shorter history window.
 */
class HistoricalCorpusDownloadManager(
    context: Context,
    private val store: DownloadedHistoricalCorpusStore = DownloadedHistoricalCorpusStore(context),
    private val cacheDirectory: File = File(context.filesDir, "upstox_backtest_cache/v1"),
    private val underlyingStore: DownloadedUnderlyingCorpusStore = DownloadedUnderlyingCorpusStore(context),
    private val catalogStore: HistoricalContractCatalogStore = HistoricalContractCatalogStore(context),
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
        val availableFrom: LocalDate?,
        val availableTo: LocalDate?,
        val sourceCoverageLimited: Boolean,
        val strikeReferenceFallbacks: Int,
        val allAvailableWorkComplete: Boolean,
        val storage: DownloadedHistoricalCorpusStore.StorageStatus,
        val underlyingRows: Long = 0L,
        val underlyingRowsAdded: Long = 0L,
        val catalogue: HistoricalContractCatalogStore.Summary = HistoricalContractCatalogStore.Summary(),
    )

    private data class Work(
        val index: MarketIndex,
        val contract: UpstoxPlusHistoricalClient.ExpiredContract,
        val fromDate: LocalDate,
        val toDate: LocalDate,
        val referenceSpot: Double?,
    )

    private data class Marker(val identity: String, val rows: Int, val fingerprint: String)

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
        ensureStorage()

        val client = UpstoxPlusHistoricalClient(accessToken = accessToken, cacheDirectory = cacheDirectory)
        val markets = scope.singleIndexOrNull()?.let(::listOf) ?: listOf(MarketIndex.NIFTY, MarketIndex.SENSEX)
        val work = mutableListOf<Work>()
        val errors = mutableListOf<String>()
        var expiryCount = 0
        var earliestAvailable: LocalDate? = null
        var latestAvailable: LocalDate? = null
        var referenceFallbacks = 0
        var underlyingRowsAdded = 0L
        val requestedStart = HistoricalDownloadPlanner.windowStart(months, today)

        markets.forEachIndexed { marketIndex, market ->
            if (shouldCancel()) error("Historical download cancelled")
            onProgress(progress("DISCOVERY", marketIndex, markets.size, "Discovering ${market.name} · persistent catalogue + Upstox Plus…", client))

            val freshExpiries = runCatching { client.getExpiries(market) }
                .onFailure { errors += "${market.name}: fresh expiry discovery failed (${it.message}); retained catalogue will still be used" }
                .getOrDefault(emptyList())
                .filter { it.isBefore(today) }
                .distinct()
                .sorted()
            val knownExpiries = catalogStore.expiries(market).filter { it.isBefore(today) }
            val available = (knownExpiries + freshExpiries).distinct().sorted()
            if (available.isEmpty()) {
                errors += "${market.name}: no completed expired-option expiries in Upstox discovery or local catalogue"
                return@forEachIndexed
            }

            earliestAvailable = listOfNotNull(earliestAvailable, available.first()).minOrNull()
            latestAvailable = listOfNotNull(latestAvailable, available.last()).maxOrNull()
            val expiries = HistoricalDownloadPlanner.expiries(available, months, today)
            expiryCount += expiries.size

            expiries.forEachIndexed { expiryIndex, expiry ->
                if (shouldCancel()) error("Historical download cancelled")
                ensureStorage()
                onProgress(progress("DISCOVERY", expiryIndex + 1, expiries.size.coerceAtLeast(1), "${market.name} · planning expiry $expiry", client))
                runCatching {
                    val overallStart = HistoricalDownloadPlanner.windowStart(months, today)
                    val naturalStart = expiry.minusDays(CONTRACT_LOOKBACK_DAYS)
                    val from = if (overallStart == null || naturalStart.isAfter(overallStart)) naturalStart else overallStart
                    if (from.isAfter(expiry)) return@runCatching

                    val spotHistory = client.getHistoricalUnderlyingDailyCandles(
                        market,
                        from.minusDays(SPOT_REFERENCE_LOOKBACK_DAYS),
                        from,
                    )
                    val referenceSpot = spotHistory.asSequence()
                        .filter { !it.time.toLocalDate().isAfter(from) && it.close > 0.0 }
                        .maxByOrNull { it.time.toInstant().toEpochMilli() }
                        ?.close
                    if (referenceSpot == null) referenceFallbacks++

                    val retained = catalogStore.contracts(market, expiry)
                    val fresh = if (expiry in freshExpiries || retained.isEmpty()) {
                        runCatching { client.getExpiredOptionContracts(market, expiry) }
                            .onFailure { errors += "${market.name} $expiry contract refresh: ${it.message}" }
                            .getOrDefault(emptyList())
                    } else emptyList()
                    if (fresh.isNotEmpty()) catalogStore.merge(market, expiry, fresh)
                    val contracts = (retained + fresh)
                        .distinctBy { "${it.instrumentKey}|${it.optionType}|${it.strike}" }
                    val selected = HistoricalDownloadPlanner.selectContracts(contracts, strikesEachSide, referenceSpot)
                    if (selected.isEmpty()) errors += "${market.name} $expiry: no usable verified CE/PE contracts"
                    selected.forEach { contract -> work += Work(market, contract, from, contract.expiry, referenceSpot) }
                }.onFailure {
                    errors += "${market.name} $expiry discovery: ${(it.message ?: it::class.java.simpleName).take(220)}"
                }
            }
        }

        val distinctWork = work.distinctBy(::requestIdentity)
        if (distinctWork.isEmpty()) return emptyResult(client, expiryCount, earliestAvailable, latestAvailable, requestedStart, errors, referenceFallbacks)

        markets.forEach { market ->
            val marketWork = distinctWork.filter { it.index == market }
            if (marketWork.isEmpty()) return@forEach
            val from = marketWork.minOf { it.fromDate }.minusDays(UNDERLYING_WARMUP_DAYS)
            val to = marketWork.maxOf { it.toDate }
            val chunks = HistoricalDownloadPlanner.monthChunks(from, to)
            chunks.forEachIndexed { chunkIndex, (chunkFrom, chunkTo) ->
                if (shouldCancel()) error("Historical download cancelled")
                ensureStorage()
                if (underlyingStore.hasUsableRange(market, chunkFrom, chunkTo)) {
                    onProgress(progress("INDEX_CONTEXT", chunkIndex + 1, chunks.size, "${market.name} 1m $chunkFrom → $chunkTo · verified local skip", client))
                    return@forEachIndexed
                }
                onProgress(progress("INDEX_CONTEXT", chunkIndex, chunks.size, "${market.name} 1m $chunkFrom → $chunkTo · downloading causal index context…", client))
                runCatching {
                    val candles = client.getHistoricalUnderlyingMinuteCandles(market, chunkFrom, chunkTo)
                    require(candles.isNotEmpty()) { "No underlying index candles returned" }
                    underlyingRowsAdded += underlyingStore.save(market, candles).addedRows
                }.onFailure { e ->
                    errors += "${market.name} index context $chunkFrom → $chunkTo: ${(e.message ?: e::class.java.simpleName).take(220)}"
                }
            }
        }

        var downloaded = 0
        var skipped = 0
        var rowsAdded = 0L
        var duplicates = 0L
        distinctWork.forEachIndexed { i, item ->
            if (shouldCancel()) error("Historical download cancelled")
            ensureStorage()
            val spotLabel = item.referenceSpot?.let { " · spot %.2f".format(it) } ?: " · centre-strike fallback"
            val label = "${item.index.name} ${item.contract.expiry} ${item.contract.strike.toInt()} ${item.contract.optionType}$spotLabel"
            val markerFile = markerFile(item)
            val marker = readMarker(markerFile)
            if (marker != null && marker.identity == requestIdentity(item)) {
                val verified = store.verifyContract(
                    item.index,
                    item.contract.expiry,
                    item.contract.strike,
                    item.contract.optionType,
                    marker.fingerprint.takeIf(String::isNotBlank),
                    marker.rows.takeIf { it > 0 },
                )
                if (verified.verified) {
                    if (marker.fingerprint.isBlank() || marker.rows <= 0) writeMarker(markerFile, item, verified.rows, verified.fingerprint)
                    skipped++
                    onProgress(progress("DOWNLOAD", i + 1, distinctWork.size, "$label · verified resume skip", client))
                    return@forEachIndexed
                }
                markerFile.delete()
            }

            onProgress(progress("DOWNLOAD", i, distinctWork.size, "$label · downloading 1-minute candles…", client))
            runCatching {
                val candles = client.getExpiredCandles(item.contract.instrumentKey, INTERVAL, item.fromDate, item.toDate)
                require(candles.isNotEmpty()) { "No candles returned" }
                ensureStorage()
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
                writeMarker(markerFile, item, saved.totalRows, saved.fingerprint)
                downloaded++
            }.onFailure { e -> errors += "$label: ${(e.message ?: e::class.java.simpleName).take(220)}" }
            onProgress(progress("DOWNLOAD", i + 1, distinctWork.size, "$label · processed · phone corpus ${store.summary().optionContracts} contracts", client))
        }

        val summary = store.summary()
        val stats = client.requestStats()
        val underlyingRows = underlyingStore.rows()
        val complete = errors.isEmpty() && downloaded + skipped == distinctWork.size && markets.all { market ->
            distinctWork.none { it.index == market } || underlyingStore.rows(market) >= MIN_UNDERLYING_ROWS
        }
        val coverageLimited = requestedStart == null || earliestAvailable == null || earliestAvailable!!.isAfter(requestedStart.plusDays(COVERAGE_TOLERANCE_DAYS))
        val coverageText = when {
            earliestAvailable == null || latestAvailable == null -> "catalogue coverage unknown"
            coverageLimited -> "verified catalogue/discovery $earliestAvailable → $latestAvailable (shorter than requested)"
            else -> "verified catalogue/discovery $earliestAvailable → $latestAvailable"
        }
        onProgress(
            Progress(
                if (complete) "DOWNLOAD_COMPLETE" else "DOWNLOAD_PARTIAL",
                downloaded + skipped,
                distinctWork.size,
                "Historical download ${if (complete) "complete" else "partial"} · NIFTY ${summary.niftyContracts} · SENSEX ${summary.sensexContracts} · option rows ${summary.rowsAccepted} · index rows $underlyingRows · $coverageText",
                stats.cacheHits,
                stats.requests,
            ),
        )
        return Result(
            summary,
            expiryCount,
            distinctWork.size,
            downloaded,
            skipped,
            rowsAdded,
            duplicates,
            errors.distinct().takeLast(80),
            stats,
            earliestAvailable,
            latestAvailable,
            coverageLimited,
            referenceFallbacks,
            complete,
            store.storageStatus(),
            underlyingRows,
            underlyingRowsAdded,
            catalogStore.summary(),
        )
    }

    fun summary(): LocalCorpusSummary = store.summary()
    fun storageStatus(): DownloadedHistoricalCorpusStore.StorageStatus = store.storageStatus()
    fun underlyingRows(index: MarketIndex? = null): Long = underlyingStore.rows(index)
    fun catalogueSummary(): HistoricalContractCatalogStore.Summary = catalogStore.summary()
    fun importCatalogue(input: InputStream, nameHint: String = ""): HistoricalContractCatalogImporter.Result =
        HistoricalContractCatalogImporter(catalogStore).import(input, nameHint)

    @Synchronized
    fun clearDownloadedCorpus() {
        store.clear()
        underlyingStore.clear()
        markerRoot.deleteRecursively()
        markerRoot.mkdirs()
    }

    @Synchronized
    fun clearCatalogue() = catalogStore.clear()

    private fun emptyResult(
        client: UpstoxPlusHistoricalClient,
        expiryCount: Int,
        earliest: LocalDate?,
        latest: LocalDate?,
        requestedStart: LocalDate?,
        errors: List<String>,
        referenceFallbacks: Int,
    ): Result {
        val summary = store.summary()
        val stats = client.requestStats()
        val limited = requestedStart == null || earliest == null || earliest.isAfter(requestedStart.plusDays(COVERAGE_TOLERANCE_DAYS))
        return Result(
            summary = summary,
            expiries = expiryCount,
            contractsPlanned = 0,
            contractsDownloaded = 0,
            contractsSkipped = 0,
            rowsAdded = 0,
            duplicatesRemoved = 0,
            errors = (errors + "No historical option contracts were planned").distinct(),
            stats = stats,
            availableFrom = earliest,
            availableTo = latest,
            sourceCoverageLimited = limited,
            strikeReferenceFallbacks = referenceFallbacks,
            allAvailableWorkComplete = false,
            storage = store.storageStatus(),
            underlyingRows = underlyingStore.rows(),
            catalogue = catalogStore.summary(),
        )
    }

    private fun ensureStorage() {
        val storage = store.storageStatus()
        if (!storage.canDownload) error(
            "Historical download paused by storage protection · free ${formatGiB(storage.freeBytes)} GB is below the ${formatGiB(storage.minimumFreeBytes)} GB safety floor · completed contracts retained",
        )
    }

    private fun progress(stage: String, completed: Int, total: Int, message: String, client: UpstoxPlusHistoricalClient): Progress {
        val stats = client.requestStats()
        return Progress(stage, completed, total, message, stats.cacheHits, stats.requests)
    }

    private fun markerFile(item: Work): File = File(markerRoot, sha256(requestIdentity(item)) + ".ok")

    private fun readMarker(file: File): Marker? {
        if (!file.isFile) return null
        return runCatching {
            val p = Properties()
            FileInputStream(file).use(p::load)
            val schema = p.getProperty("schema")?.toIntOrNull() ?: 1
            require(schema in 1..SCHEMA)
            Marker(
                identity = p.getProperty("identity").orEmpty().ifBlank {
                    buildString {
                        append(p.getProperty("market").orEmpty()).append('|')
                        append(p.getProperty("instrument").orEmpty()).append('|').append(INTERVAL).append('|')
                        append(p.getProperty("from").orEmpty()).append('|').append(p.getProperty("to").orEmpty())
                    }
                },
                rows = p.getProperty("rows")?.toIntOrNull() ?: 0,
                fingerprint = p.getProperty("fingerprint").orEmpty(),
            )
        }.getOrElse { file.delete(); null }
    }

    private fun writeMarker(file: File, item: Work, rows: Int, fingerprint: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".${System.nanoTime()}.tmp")
        val p = Properties().apply {
            setProperty("schema", SCHEMA.toString())
            setProperty("identity", requestIdentity(item))
            setProperty("market", item.index.name)
            setProperty("instrument", item.contract.instrumentKey)
            setProperty("expiry", item.contract.expiry.toString())
            setProperty("strike", item.contract.strike.toString())
            setProperty("type", item.contract.optionType)
            setProperty("from", item.fromDate.toString())
            setProperty("to", item.toDate.toString())
            setProperty("rows", rows.toString())
            setProperty("fingerprint", fingerprint)
            setProperty("completed_at", System.currentTimeMillis().toString())
        }
        FileOutputStream(temp).use { p.store(it, "VARDHANI verified historical download") }
        if (file.exists() && !file.delete()) {
            temp.delete()
            error("Could not replace historical download marker")
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not finalize historical download marker")
        }
    }

    private fun requestIdentity(item: Work): String =
        "${item.index.name}|${item.contract.instrumentKey}|$INTERVAL|${item.fromDate}|${item.toDate}"

    private fun formatGiB(bytes: Long): String = "%.2f".format(bytes.toDouble() / GIB.toDouble())

    companion object {
        const val DEFAULT_STRIKES_EACH_SIDE = 5
        val ALLOWED_STRIKE_RADII: Set<Int> = setOf(2, 5, 10)
        private const val SCHEMA = 4
        private const val INTERVAL = "1minute"
        private const val CONTRACT_LOOKBACK_DAYS = 7L
        private const val SPOT_REFERENCE_LOOKBACK_DAYS = 10L
        private const val UNDERLYING_WARMUP_DAYS = 7L
        private const val COVERAGE_TOLERANCE_DAYS = 7L
        private const val MIN_UNDERLYING_ROWS = 60L
        private const val GIB = 1024L * 1024L * 1024L

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Pure deterministic planning helpers kept testable without Android/network dependencies. */
object HistoricalDownloadPlanner {
    fun expiries(available: List<LocalDate>, months: Int, today: LocalDate): List<LocalDate> {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val closed = available.filter { it.isBefore(today) }.distinct().sorted()
        if (months == PrelabelledTrainingWindowPlan.FULL) return closed
        val from = today.minusMonths(months.toLong())
        return closed.filter { !it.isBefore(from) }
    }

    fun windowStart(months: Int, today: LocalDate): LocalDate? =
        if (months == PrelabelledTrainingWindowPlan.FULL) null else today.minusMonths(months.toLong())

    /** Inclusive, gap-free chunks satisfying Upstox V3's <= one-month minute-data window. */
    fun monthChunks(fromDate: LocalDate, toDate: LocalDate): List<Pair<LocalDate, LocalDate>> {
        require(!fromDate.isAfter(toDate))
        val out = ArrayList<Pair<LocalDate, LocalDate>>()
        var cursor = fromDate
        while (!cursor.isAfter(toDate)) {
            val maxEnd = cursor.plusMonths(1).minusDays(1)
            val end = if (maxEnd.isBefore(toDate)) maxEnd else toDate
            out += cursor to end
            cursor = end.plusDays(1)
        }
        return out
    }

    fun selectContracts(
        contracts: List<UpstoxPlusHistoricalClient.ExpiredContract>,
        strikesEachSide: Int,
        referenceSpot: Double? = null,
    ): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        require(strikesEachSide in HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII)
        if (contracts.isEmpty()) return emptyList()
        val strikes = contracts.map { it.strike }.filter { it > 0.0 }.distinct().sorted()
        if (strikes.isEmpty()) return emptyList()
        val centre = referenceSpot?.takeIf { it.isFinite() && it > 0.0 } ?: strikes[strikes.size / 2]
        val selected = strikes.sortedWith(compareBy<Double> { abs(it - centre) }.thenBy { it })
            .take(strikesEachSide * 2 + 1).toSet()
        return contracts.asSequence()
            .filter { it.optionType == "CE" || it.optionType == "PE" }
            .filter { it.strike in selected }
            .distinctBy { "${it.instrumentKey}|${it.optionType}|${it.strike}" }
            .sortedWith(compareBy<UpstoxPlusHistoricalClient.ExpiredContract> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
            .toList()
    }
}
