package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Persistent deterministic store for historical option candles downloaded by VARDHANI.
 *
 * One verified file is kept per market/expiry/strike/side contract. Re-downloads merge
 * by timestamp. Files are fully decoded/validated before a resume marker may trust them.
 */
class DownloadedHistoricalCorpusStore(context: Context) {
    data class SaveResult(
        val addedRows: Int,
        val duplicateRows: Int,
        val totalRows: Int,
        val fingerprint: String,
        val fileBytes: Long,
    )

    data class Verification(
        val verified: Boolean,
        val rows: Int = 0,
        val fingerprint: String = "",
        val fileBytes: Long = 0L,
        val reason: String = "",
    )

    data class StorageStatus(
        val corpusBytes: Long,
        val freeBytes: Long,
        val minimumFreeBytes: Long = MINIMUM_FREE_BYTES,
    ) {
        val canDownload: Boolean get() = freeBytes <= 0L || freeBytes >= minimumFreeBytes
    }

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
        val contractKey: String
            get() = DownloadedHistoricalCorpusStore.key(index, expiry, strike, optionType)
    }

    private data class Stored(val header: Header, val candles: List<UpstoxPlusHistoricalClient.Candle>)

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "vardhani_downloaded_historical/v$SCHEMA")
    private val contracts = File(root, "contracts").apply { mkdirs() }
    private val temp = File(root, "tmp").apply { mkdirs() }
    private val corrupt = File(root, "corrupt").apply { mkdirs() }
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
        require(series.candles.isNotEmpty()) { "Downloaded historical series is empty" }

        val contractKey = key(series.index, series.expiry, series.strike, type)
        val target = fileForKey(contractKey)
        val previous = if (target.isFile) {
            runCatching { readStored(target) }.getOrElse {
                quarantine(target, "decode")
                null
            }
        } else null
        if (previous != null) require(previous.header.contractKey == contractKey) { "Downloaded corpus contract identity mismatch" }

        val previousRows = previous?.candles.orEmpty()
        val inputClean = series.candles
            .filter { c -> minOf(c.open, c.high, c.low, c.close) > 0.0 }
            .sortedBy { it.time.toInstant().toEpochMilli() }
            .distinctBy { it.time.toInstant().toEpochMilli() }
        require(inputClean.isNotEmpty()) { "Downloaded contract contained no valid OHLC candles" }
        val merged = (previousRows + inputClean)
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
        if (duplicates > 0) {
            prefs.edit().putLong(KEY_DEDUPED, prefs.getLong(KEY_DEDUPED, 0L) + duplicates).apply()
        }
        val verification = verifyFile(target, contractKey)
        require(verification.verified) { "Downloaded corpus verification failed: ${verification.reason}" }
        return SaveResult(added, duplicates, verification.rows, verification.fingerprint, verification.fileBytes)
    }

    fun verifyContract(
        index: MarketIndex,
        expiry: LocalDate,
        strike: Double,
        optionType: String,
        expectedFingerprint: String? = null,
        expectedRows: Int? = null,
    ): Verification {
        val contractKey = key(index, expiry, strike, optionType.uppercase())
        val verification = verifyFile(fileForKey(contractKey), contractKey)
        if (!verification.verified) return verification
        if (!expectedFingerprint.isNullOrBlank() && verification.fingerprint != expectedFingerprint) {
            return verification.copy(verified = false, reason = "fingerprint mismatch")
        }
        if (expectedRows != null && expectedRows > 0 && verification.rows != expectedRows) {
            return verification.copy(verified = false, reason = "row-count mismatch")
        }
        return verification
    }

    /** Reads the selected window from phone storage. FULL means all locally downloaded history. */
    fun loadSeriesWindow(index: MarketIndex, months: Int): List<HistoricalOptionSeries> {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        val headers = contractFiles().mapNotNull { file ->
            runCatching { readHeader(file) }.getOrNull()?.let { file to it }
        }.filter { it.second.index == index }
        if (headers.isEmpty()) return emptyList()
        val latestEpoch = headers.maxOf { it.second.maxEpochMs }
        val latestDate = Instant.ofEpochMilli(latestEpoch).atZone(zone).toLocalDate()
        val cutoff = if (months == PrelabelledTrainingWindowPlan.FULL) LocalDate.MIN else latestDate.minusMonths(months.toLong())

        return headers.mapNotNull { (file, header) ->
            if (months != PrelabelledTrainingWindowPlan.FULL && Instant.ofEpochMilli(header.maxEpochMs).atZone(zone).toLocalDate().isBefore(cutoff)) {
                return@mapNotNull null
            }
            val stored = runCatching { readStored(file) }.getOrElse {
                quarantine(file, "load")
                return@mapNotNull null
            }
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
        val errors = mutableListOf<String>()
        val headers = contractFiles().mapNotNull { file ->
            runCatching { readHeader(file) }.getOrElse {
                errors += "Corrupt downloaded contract ${file.name.take(18)}…"
                null
            }
        }
        if (headers.isEmpty()) return LocalCorpusSummary(errors = errors)
        val rows = headers.sumOf { it.count.toLong() }
        val minEpoch = headers.minOf { it.minEpochMs }
        val maxEpoch = headers.maxOf { it.maxEpochMs }
        val deduped = prefs.getLong(KEY_DEDUPED, 0L)
        return LocalCorpusSummary(
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
            errors = errors,
        )
    }

    fun storageStatus(): StorageStatus = StorageStatus(
        corpusBytes = root.walkTopDown().filter(File::isFile).sumOf(File::length),
        freeBytes = root.usableSpace,
    )

    @Synchronized
    fun clear() {
        root.deleteRecursively()
        contracts.mkdirs()
        temp.mkdirs()
        corrupt.mkdirs()
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
        val expectedKey = key(index, expiry, strike, optionType)
        val verified = verifyFile(tmp, expectedKey)
        require(verified.verified && verified.rows == candles.size) { "Downloaded corpus temporary-file verification failed: ${verified.reason}" }
        replaceSafely(tmp, target)
        val finalVerification = verifyFile(target, expectedKey)
        require(finalVerification.verified && finalVerification.rows == candles.size) { "Downloaded corpus final-file verification failed: ${finalVerification.reason}" }
    }

    private fun replaceSafely(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onSuccess { return }

        val backup = File(temp, target.name + ".${System.nanoTime()}.bak")
        var backedUp = false
        try {
            if (target.exists()) {
                Files.move(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
                backedUp = true
            }
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            if (target.exists()) target.delete()
            if (backedUp && backup.exists()) runCatching {
                Files.move(backup.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            source.delete()
            throw error
        }
    }

    private fun verifyFile(file: File, expectedKey: String): Verification {
        if (!file.isFile || file.length() <= 0L) return Verification(false, reason = "missing file")
        return runCatching {
            val stored = readStored(file)
            require(stored.header.contractKey == expectedKey) { "contract identity mismatch" }
            Verification(
                verified = true,
                rows = stored.header.count,
                fingerprint = sha256(file),
                fileBytes = file.length(),
            )
        }.getOrElse { Verification(false, reason = (it.message ?: it::class.java.simpleName).take(160)) }
    }

    private fun readStored(file: File): Stored = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { input ->
        val header = readHeader(input)
        val rows = ArrayList<UpstoxPlusHistoricalClient.Candle>(header.count)
        var previousEpoch = Long.MIN_VALUE
        repeat(header.count) {
            val epoch = input.readLong()
            require(epoch >= previousEpoch) { "non-monotonic candle timestamps" }
            previousEpoch = epoch
            val offset = input.readInt().coerceIn(-64_800, 64_800)
            val o = input.readDouble(); val h = input.readDouble(); val l = input.readDouble(); val c = input.readDouble()
            require(minOf(o, h, l, c) > 0.0) { "invalid OHLC candle" }
            val volume = input.readLong(); val oi = input.readLong()
            rows += UpstoxPlusHistoricalClient.Candle(
                Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.ofTotalSeconds(offset)),
                o, h, l, c, volume.coerceAtLeast(0L), oi.coerceAtLeast(0L),
            )
        }
        require(rows.isNotEmpty())
        require(rows.first().time.toInstant().toEpochMilli() == header.minEpochMs) { "minimum timestamp mismatch" }
        require(rows.last().time.toInstant().toEpochMilli() == header.maxEpochMs) { "maximum timestamp mismatch" }
        require(input.read() == -1) { "unexpected trailing bytes" }
        Stored(header, rows)
    }

    private fun readHeader(file: File): Header = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { input -> readHeader(input) }

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
        require(header.strike > 0.0 && header.lotSize > 0)
        require(header.count in 1..MAX_ROWS_PER_CONTRACT)
        require(header.minEpochMs in 1..header.maxEpochMs)
        return header
    }

    private fun quarantine(file: File, reason: String) {
        if (!file.exists()) return
        corrupt.mkdirs()
        val target = File(corrupt, file.name + ".${System.currentTimeMillis()}.$reason")
        if (!file.renameTo(target)) file.delete()
    }

    private fun contractFiles(): List<File> =
        contracts.listFiles { f -> f.isFile && f.extension == EXT }?.sortedBy { it.name }.orEmpty()

    private fun fileForKey(contractKey: String): File = File(contracts, "${sha256(contractKey)}.$EXT")

    companion object {
        private const val SCHEMA = 1
        private const val MAGIC = "VARDHANI-DOWNLOADED-HISTORICAL-V1"
        private const val EXT = "vhd"
        private const val BUFFER = 64 * 1024
        private const val MAX_ROWS_PER_CONTRACT = 1_000_000
        private const val TEMP_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val KEY_DEDUPED = "deduped_rows"
        const val MINIMUM_FREE_BYTES = 2L * 1024L * 1024L * 1024L

        fun key(index: MarketIndex, expiry: LocalDate, strike: Double, optionType: String): String =
            "${index.name}|$expiry|$strike|${optionType.uppercase()}"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).buffered(BUFFER).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
