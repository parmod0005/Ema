package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Persistent, deterministic store for historical option candles downloaded by VARDHANI.
 *
 * One atomic file is kept per market/expiry/strike/side contract. Re-downloading the
 * same contract merges by timestamp, so a crash/retry cannot duplicate training rows.
 * This store is deliberately independent from user-imported/pre-labelled corpora.
 */
class DownloadedHistoricalCorpusStore(context: Context) {
    data class SaveResult(
        val addedRows: Int,
        val duplicateRows: Int,
        val totalRows: Int,
    )

    private data class Header(
        val index: MarketIndex,
        val optionType: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val count: Int,
        val minEpochMs: Long,
        val maxEpochMs: Long,
    ) {
        val key: String get() = key(index, expiry, strike, optionType)
    }

    private data class Stored(val header: Header, val candles: List<UpstoxPlusHistoricalClient.Candle>)

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "vardhani_downloaded_historical/v$SCHEMA")
    private val contracts = File(root, "contracts").apply { mkdirs() }
    private val temp = File(root, "tmp").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("vardhani_downloaded_historical_stats", 0)
    private val zone = ZoneId.of("Asia/Kolkata")

    init {
        temp.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > TEMP_MAX_AGE_MS) file.delete()
        }
    }

    @Synchronized
    fun saveSeries(series: HistoricalOptionSeries): SaveResult {
        val type = series.optionType.uppercase()
        require(type == "CE" || type == "PE") { "Downloaded historical series must be CE or PE" }
        require(series.strike > 0.0 && series.lotSize > 0)
        if (series.candles.isEmpty()) return SaveResult(0, 0, 0)

        val contractKey = key(series.index, series.expiry, series.strike, type)
        val target = fileForKey(contractKey)
        val previous = if (target.isFile) readStored(target) else null
        if (previous != null) require(previous.header.key == contractKey) { "Downloaded corpus contract identity mismatch" }

        val previousRows = previous?.candles.orEmpty()
        val merged = (previousRows + series.candles)
            .filter { c -> minOf(c.open, c.high, c.low, c.close) > 0.0 }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
        val added = (merged.size - previousRows.size).coerceAtLeast(0)
        val duplicates = (series.candles.size - added).coerceAtLeast(0)
        if (added > 0 || previous == null) {
            writeAtomic(
                target = target,
                index = series.index,
                optionType = type,
                strike = series.strike,
                expiry = series.expiry,
                lotSize = series.lotSize,
                symbol = series.symbol,
                candles = merged,
            )
        }
        if (duplicates > 0) prefs.edit().putLong(KEY_DEDUPED, prefs.getLong(KEY_DEDUPED, 0L) + duplicates).apply()
        return SaveResult(added, duplicates, merged.size)
    }

    fun hasContract(index: MarketIndex, expiry: LocalDate, strike: Double, optionType: String): Boolean =
        fileForKey(key(index, expiry, strike, optionType.uppercase())).isFile

    /** Reads the selected window from phone storage. FULL is supported. */
    fun loadSeriesWindow(index: MarketIndex, months: Int): List<HistoricalOptionSeries> {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val files = contractFiles()
        val headers = files.mapNotNull { file -> runCatching { readHeader(file) }.getOrNull()?.let { file to it } }
            .filter { it.second.index == index }
        if (headers.isEmpty()) return emptyList()
        val latestEpoch = headers.maxOf { it.second.maxEpochMs }
        val latestDate = Instant.ofEpochMilli(latestEpoch).atZone(zone).toLocalDate()
        val cutoff = if (months == PrelabelledTrainingWindowPlan.FULL) LocalDate.MIN else latestDate.minusMonths(months.toLong())

        return headers.mapNotNull { (file, header) ->
            if (months != PrelabelledTrainingWindowPlan.FULL && Instant.ofEpochMilli(header.maxEpochMs).atZone(zone).toLocalDate().isBefore(cutoff)) return@mapNotNull null
            val stored = runCatching { readStored(file) }.getOrNull() ?: return@mapNotNull null
            val candles = stored.candles.filter { c ->
                months == PrelabelledTrainingWindowPlan.FULL || !c.time.toLocalDate().isBefore(cutoff)
            }
            if (candles.isEmpty()) null else HistoricalOptionSeries(
                index = header.index,
                optionType = header.optionType,
                strike = header.strike,
                expiry = header.expiry,
                lotSize = header.lotSize,
                symbol = header.symbol,
                source = "UPSTOX_DOWNLOADED",
                candles = candles,
            )
        }.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
    }

    fun summary(): LocalCorpusSummary {
        val headers = contractFiles().mapNotNull { runCatching { readHeader(it) }.getOrNull() }
        if (headers.isEmpty()) return LocalCorpusSummary()
        val rows = headers.sumOf { it.count.toLong() }
        val minEpoch = headers.minOf { it.minEpochMs }
        val maxEpoch = headers.maxOf { it.maxEpochMs }
        val deduped = prefs.getLong(KEY_DEDUPED, 0L)
        return LocalCorpusSummary(
            filesImported = 0,
            supportedFiles = 0,
            rowsRead = rows + deduped,
            rowsAccepted = rows,
            rowsRejected = 0,
            duplicatesRemoved = deduped,
            optionContracts = headers.size,
            niftyContracts = headers.count { it.index == MarketIndex.NIFTY },
            sensexContracts = headers.count { it.index == MarketIndex.SENSEX },
            ceContracts = headers.count { it.optionType == "CE" },
            peContracts = headers.count { it.optionType == "PE" },
            inferredLotSizeContracts = 0,
            fromDate = Instant.ofEpochMilli(minEpoch).atZone(zone).toLocalDate(),
            toDate = Instant.ofEpochMilli(maxEpoch).atZone(zone).toLocalDate(),
        )
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
        contracts.mkdirs()
        temp.mkdirs()
        prefs.edit().clear().apply()
    }

    private fun writeAtomic(
        target: File,
        index: MarketIndex,
        optionType: String,
        strike: Double,
        expiry: LocalDate,
        lotSize: Int,
        symbol: String,
        candles: List<UpstoxPlusHistoricalClient.Candle>,
    ) {
        require(candles.isNotEmpty())
        val minEpoch = candles.first().time.toInstant().toEpochMilli()
        val maxEpoch = candles.last().time.toInstant().toEpochMilli()
        val tmp = File(temp, target.name + ".${System.nanoTime()}.tmp")
        DataOutputStream(FileOutputStream(tmp).buffered(BUFFER)).use { out ->
            out.writeUTF(MAGIC)
            out.writeUTF(index.name)
            out.writeUTF(optionType)
            out.writeDouble(strike)
            out.writeUTF(expiry.toString())
            out.writeInt(lotSize)
            out.writeUTF(symbol.take(240))
            out.writeInt(candles.size)
            out.writeLong(minEpoch)
            out.writeLong(maxEpoch)
            candles.forEach { c ->
                out.writeLong(c.time.toInstant().toEpochMilli())
                out.writeInt(c.time.offset.totalSeconds)
                out.writeDouble(c.open)
                out.writeDouble(c.high)
                out.writeDouble(c.low)
                out.writeDouble(c.close)
                out.writeLong(c.volume)
                out.writeLong(c.openInterest)
            }
        }
        // Verify before replacing the last known-good contract file.
        val verified = readHeader(tmp)
        require(verified.count == candles.size && verified.key == key(index, expiry, strike, optionType)) { "Downloaded corpus verification failed" }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            error("Could not replace downloaded historical contract")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not finalize downloaded historical contract")
        }
    }

    private fun readStored(file: File): Stored = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { input ->
        val header = readHeader(input)
        val rows = ArrayList<UpstoxPlusHistoricalClient.Candle>(header.count)
        repeat(header.count) {
            val epoch = input.readLong()
            val offset = input.readInt().coerceIn(-64_800, 64_800)
            val o = input.readDouble(); val h = input.readDouble(); val l = input.readDouble(); val c = input.readDouble()
            val volume = input.readLong(); val oi = input.readLong()
            rows += UpstoxPlusHistoricalClient.Candle(Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.ofTotalSeconds(offset)), o, h, l, c, volume, oi)
        }
        Stored(header, rows)
    }

    private fun readHeader(file: File): Header = DataInputStream(FileInputStream(file).buffered(BUFFER)).use(::readHeader)

    private fun readHeader(input: DataInputStream): Header {
        require(input.readUTF() == MAGIC) { "Unsupported downloaded historical corpus schema" }
        val header = Header(
            index = MarketIndex.valueOf(input.readUTF()),
            optionType = input.readUTF(),
            strike = input.readDouble(),
            expiry = LocalDate.parse(input.readUTF()),
            lotSize = input.readInt(),
            symbol = input.readUTF(),
            count = input.readInt(),
            minEpochMs = input.readLong(),
            maxEpochMs = input.readLong(),
        )
        require(header.optionType == "CE" || header.optionType == "PE")
        require(header.count in 1..MAX_ROWS_PER_CONTRACT)
        require(header.minEpochMs <= header.maxEpochMs)
        return header
    }

    private fun contractFiles(): List<File> = contracts.listFiles { f -> f.isFile && f.extension == EXT }?.sortedBy { it.name }.orEmpty()
    private fun fileForKey(key: String): File = File(contracts, "${sha256(key)}.$EXT")

    companion object {
        private const val SCHEMA = 1
        private const val MAGIC = "VARDHANI-DOWNLOADED-HISTORICAL-V1"
        private const val EXT = "vhd"
        private const val BUFFER = 64 * 1024
        private const val MAX_ROWS_PER_CONTRACT = 1_000_000
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val KEY_DEDUPED = "deduped_rows"

        fun key(index: MarketIndex, expiry: LocalDate, strike: Double, optionType: String): String =
            "${index.name}|$expiry|$strike|${optionType.uppercase()}"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
