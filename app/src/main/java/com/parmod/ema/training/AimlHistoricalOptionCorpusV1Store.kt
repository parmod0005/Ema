package com.parmod.ema.training

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import com.parmod.ema.model.MarketIndex
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * Memory-safe importer for the existing `aiml-historical-option-row-v1` corpus.
 *
 * The source export is intentionally very large (multi-GB NDJSON).  This class never
 * materialises a ZIP entry or an NDJSON file in memory.  It parses top-level JSON
 * objects directly from the stream and writes a compact fixed-width binary cache.
 * The original chronological train/validation/test split is preserved.
 */
class AimlHistoricalOptionCorpusV1Store(private val context: Context) {
    enum class Split(val fileName: String, val property: String) {
        TRAIN("train.vhc", "trainRows"),
        VALIDATION("validation.vhc", "validationRows"),
        TEST("test.vhc", "testRows"),
    }

    data class ImportProgress(
        val completedFiles: Int,
        val totalFiles: Int,
        val rowsRead: Long,
        val rowsAccepted: Long,
        val message: String,
    )

    data class ImportResult(
        val recognized: Boolean,
        val summary: LocalCorpusSummary,
        val trainRows: Long = 0,
        val validationRows: Long = 0,
        val testRows: Long = 0,
        val message: String = "",
    )

    data class Record(
        val timestampMs: Long,
        val expiryEpochDay: Int,
        val index: MarketIndex,
        val optionType: String,
        val lotSize: Int,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val oi: Double,
        val spot: Double,
        val strike: Double,
        val strikeStep: Double,
        val signedMoneynessSteps: Double,
        val future1: Double,
        val future3: Double,
        val future5: Double,
        val future15: Double,
        val mfe1: Double,
        val mfe3: Double,
        val mfe5: Double,
        val mfe15: Double,
        val mae1: Double,
        val mae3: Double,
        val mae5: Double,
        val mae15: Double,
    ) {
        fun expiry(): LocalDate = LocalDate.ofEpochDay(expiryEpochDay.toLong())
    }

    private data class MutableStats(
        var files: Int = 0,
        var supported: Int = 0,
        var rowsRead: Long = 0,
        var accepted: Long = 0,
        var rejected: Long = 0,
        var ceRows: Long = 0,
        var peRows: Long = 0,
        var e1Rows: Long = 0,
        var e2Rows: Long = 0,
        var e3Rows: Long = 0,
        var trainRows: Long = 0,
        var validationRows: Long = 0,
        var testRows: Long = 0,
        var mfe5Sum: Double = 0.0,
        var mae5Sum: Double = 0.0,
        var net5Sum: Double = 0.0,
        var minDate: LocalDate? = null,
        var maxDate: LocalDate? = null,
        val contracts: MutableSet<String> = linkedSetOf(),
        val expiries: MutableSet<String> = linkedSetOf(),
        val warnings: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    private data class Writers(
        val train: DataOutputStream,
        val validation: DataOutputStream,
        val test: DataOutputStream,
    ) : AutoCloseable {
        fun forSplit(split: Split): DataOutputStream = when (split) {
            Split.TRAIN -> train
            Split.VALIDATION -> validation
            Split.TEST -> test
        }
        override fun close() {
            runCatching { train.close() }
            runCatching { validation.close() }
            runCatching { test.close() }
        }
    }

    private val root = File(context.filesDir, "vardhani_prelabelled_corpus/aiml_option_v1")
    private val dataDir = File(root, "data")
    private val statsFile = File(root, "stats.properties")

    init { dataDir.mkdirs() }

    fun likelyPrelabelledCorpus(uris: List<Uri>): Boolean = uris.any { uri ->
        val name = displayName(uri).orEmpty().lowercase(Locale.ROOT)
        name.endsWith(".ndjson") || name.endsWith(".jsonl") ||
            (name.endsWith(".zip") && ("historical-corpus" in name || "aiml" in name))
    }

    fun importUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ImportResult {
        require(uris.isNotEmpty()) { "Select at least one corpus file" }
        val tempRoot = File(root, "import-${System.currentTimeMillis()}")
        val tempData = File(tempRoot, "data").apply { mkdirs() }
        val stats = MutableStats()
        var recognized = false
        val writers = Writers(
            writer(File(tempData, Split.TRAIN.fileName)),
            writer(File(tempData, Split.VALIDATION.fileName)),
            writer(File(tempData, Split.TEST.fileName)),
        )
        try {
            uris.forEachIndexed { fileIndex, uri ->
                if (shouldCancel()) error("Corpus import cancelled")
                val name = displayName(uri) ?: "corpus_${fileIndex + 1}"
                stats.files++
                onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, "Inspecting $name"))
                context.contentResolver.openInputStream(uri)?.use { input ->
                    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "zip" -> {
                            val found = parseZip(name, input, writers, stats, shouldCancel) { message ->
                                onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, message))
                            }
                            recognized = recognized || found
                        }
                        "ndjson", "jsonl" -> {
                            val split = splitFromName(name)
                            if (split != null) {
                                recognized = true
                                stats.supported++
                                parseNdjson(name, split, input, writers.forSplit(split), stats, shouldCancel) { message ->
                                    onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, message))
                                }
                            }
                        }
                    }
                } ?: error("Unable to open $name")
            }
        } catch (t: Throwable) {
            writers.close()
            tempRoot.deleteRecursively()
            throw t
        }
        writers.close()

        if (!recognized) {
            tempRoot.deleteRecursively()
            return ImportResult(false, summary(), message = "No aiml-historical-option-row-v1 train/validation/test corpus found")
        }
        if (stats.trainRows <= 0 || stats.validationRows <= 0 || stats.testRows <= 0) {
            tempRoot.deleteRecursively()
            return ImportResult(
                recognized = true,
                summary = summary(extraErrors = listOf("Pre-labelled corpus requires train.ndjson, validation.ndjson and test.ndjson with accepted rows")),
                trainRows = stats.trainRows,
                validationRows = stats.validationRows,
                testRows = stats.testRows,
                message = "Incomplete pre-labelled corpus",
            )
        }

        val newStats = File(tempRoot, "stats.properties")
        saveStats(newStats, stats)
        val backup = File(root, "previous")
        backup.deleteRecursively()
        if (dataDir.exists()) dataDir.renameTo(File(backup, "data"))
        if (statsFile.exists()) {
            backup.mkdirs()
            statsFile.renameTo(File(backup, "stats.properties"))
        }
        dataDir.deleteRecursively()
        check(tempData.renameTo(dataDir)) { "Could not activate compact historical corpus cache" }
        if (statsFile.exists()) statsFile.delete()
        check(newStats.renameTo(statsFile)) { "Could not activate historical corpus metadata" }
        tempRoot.deleteRecursively()
        backup.deleteRecursively()
        val current = summary()
        return ImportResult(
            recognized = true,
            summary = current,
            trainRows = stats.trainRows,
            validationRows = stats.validationRows,
            testRows = stats.testRows,
            message = "Pre-labelled corpus ready · train ${stats.trainRows} · validation ${stats.validationRows} · test ${stats.testRows}",
        )
    }

    fun ready(): Boolean = Split.entries.all { file(it).isFile && rows(it) > 0L }

    fun rows(split: Split): Long = loadStats().getProperty(split.property)?.toLongOrNull() ?: 0L

    fun summary(extraWarnings: List<String> = emptyList(), extraErrors: List<String> = emptyList()): LocalCorpusSummary {
        val p = loadStats()
        if (p.isEmpty) return LocalCorpusSummary()
        val contractCount = p.getProperty("contracts")?.toIntOrNull() ?: 0
        val market = p.getProperty("market", "NIFTY")
        return LocalCorpusSummary(
            filesImported = p.getProperty("files")?.toIntOrNull() ?: 0,
            supportedFiles = p.getProperty("supported")?.toIntOrNull() ?: 0,
            rowsRead = p.getProperty("rowsRead")?.toLongOrNull() ?: 0L,
            rowsAccepted = p.getProperty("accepted")?.toLongOrNull() ?: 0L,
            rowsRejected = p.getProperty("rejected")?.toLongOrNull() ?: 0L,
            duplicatesRemoved = 0L,
            optionContracts = contractCount,
            niftyContracts = if (market == "NIFTY") contractCount else 0,
            sensexContracts = if (market == "SENSEX") contractCount else 0,
            ceContracts = p.getProperty("ceContracts")?.toIntOrNull() ?: 0,
            peContracts = p.getProperty("peContracts")?.toIntOrNull() ?: 0,
            inferredLotSizeContracts = 0,
            fromDate = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            toDate = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            warnings = (p.getProperty("warnings").orEmpty().split('\n').filter(String::isNotBlank) + extraWarnings).distinct().takeLast(12),
            errors = (p.getProperty("errors").orEmpty().split('\n').filter(String::isNotBlank) + extraErrors).distinct().takeLast(12),
        )
    }

    fun clear() { root.deleteRecursively(); dataDir.mkdirs() }

    fun metadata(): Properties = loadStats()

    fun forEach(
        split: Split,
        stride: Int = 1,
        maxAccepted: Long = Long.MAX_VALUE,
        shouldCancel: () -> Boolean = { false },
        action: (Record) -> Unit,
    ): Long {
        require(stride >= 1)
        val expected = rows(split)
        if (expected <= 0L) return 0L
        val path = file(split)
        if (!path.isFile) return 0L
        var accepted = 0L
        DataInputStream(BufferedInputStream(FileInputStream(path), BUFFER)).use { input ->
            require(input.readUTF() == MAGIC) { "Unsupported compact corpus version" }
            var index = 0L
            while (index < expected) {
                if (shouldCancel()) error("Training cancelled")
                val record = readRecord(input)
                if (index % stride == 0L) {
                    action(record)
                    accepted++
                    if (accepted >= maxAccepted) break
                }
                index++
            }
        }
        return accepted
    }

    private fun parseZip(
        archive: String,
        input: InputStream,
        writers: Writers,
        stats: MutableStats,
        cancel: () -> Boolean,
        progress: (String) -> Unit,
    ): Boolean {
        var recognized = false
        var entries = 0
        ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
            while (true) {
                if (cancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                entries++
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains too many entries")
                val base = entry.name.substringAfterLast('/')
                val split = splitFromName(base)
                if (split != null && (base.endsWith(".ndjson", true) || base.endsWith(".jsonl", true))) {
                    recognized = true
                    stats.supported++
                    parseNdjson("$archive/$base", split, NonClosingInputStream(zip), writers.forSplit(split), stats, cancel, progress)
                }
                zip.closeEntry()
            }
        }
        return recognized
    }

    private fun parseNdjson(
        name: String,
        split: Split,
        input: InputStream,
        output: DataOutputStream,
        stats: MutableStats,
        cancel: () -> Boolean,
        progress: (String) -> Unit,
    ) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = true }
        var localRows = 0L
        while (true) {
            if (cancel()) error("Corpus import cancelled")
            val token = runCatching { reader.peek() }.getOrElse { break }
            if (token == JsonToken.END_DOCUMENT) break
            if (token != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                stats.rejected++
                continue
            }
            stats.rowsRead++
            val record = readJsonRecord(reader, stats)
            if (record == null) {
                stats.rejected++
            } else {
                writeRecord(output, record)
                stats.accepted++
                localRows++
                when (split) {
                    Split.TRAIN -> stats.trainRows++
                    Split.VALIDATION -> stats.validationRows++
                    Split.TEST -> stats.testRows++
                }
                if (record.optionType == "CE") stats.ceRows++ else stats.peRows++
                val contract = "${record.index.name}|${record.expiryEpochDay}|${record.strike}|${record.optionType}"
                stats.contracts += contract
                stats.expiries += record.expiryEpochDay.toString()
                val date = Instant.ofEpochMilli(record.timestampMs).atOffset(IST).toLocalDate()
                stats.minDate = listOfNotNull(stats.minDate, date).minOrNull()
                stats.maxDate = listOfNotNull(stats.maxDate, date).maxOrNull()
                val engine = proxyEngine(record)
                if (engine == 1) stats.e1Rows++ else if (engine == 2) stats.e2Rows++ else stats.e3Rows++
                val net = netReturn5(record)
                stats.mfe5Sum += record.mfe5
                stats.mae5Sum += record.mae5
                stats.net5Sum += net
            }
            if (stats.rowsRead % PROGRESS_EVERY == 0L) {
                output.flush()
                progress("$name · ${stats.rowsRead} rows read · ${stats.accepted} accepted")
            }
            if (stats.rowsRead > MAX_ROWS) error("Historical corpus exceeds supported row-count safety limit")
        }
        output.flush()
        progress("$name complete · $localRows accepted")
    }

    private fun readJsonRecord(reader: JsonReader, stats: MutableStats): Record? {
        var schema = ""
        var authority = false
        var capturedAt = ""
        var market = ""
        var expiry = ""
        var instrumentKey = ""
        var optionType = ""
        var strike = Double.NaN
        var lot = 0
        var spot = Double.NaN
        var step = Double.NaN
        var signed = Double.NaN
        var open = Double.NaN
        var high = Double.NaN
        var low = Double.NaN
        var close = Double.NaN
        var volume = Double.NaN
        var oi = Double.NaN
        var f1 = Double.NaN; var f3 = Double.NaN; var f5 = Double.NaN; var f15 = Double.NaN
        var mfe1 = Double.NaN; var mfe3 = Double.NaN; var mfe5 = Double.NaN; var mfe15 = Double.NaN
        var mae1 = Double.NaN; var mae3 = Double.NaN; var mae5 = Double.NaN; var mae15 = Double.NaN
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema" -> schema = nextString(reader)
                "execution_authority" -> authority = nextBoolean(reader)
                "captured_at" -> capturedAt = nextString(reader)
                "market" -> market = nextString(reader)
                "expiry" -> expiry = nextString(reader)
                "instrument_key" -> instrumentKey = nextString(reader)
                "option_type" -> optionType = nextString(reader).uppercase(Locale.ROOT)
                "strike" -> strike = nextDouble(reader)
                "lot_size" -> lot = nextDouble(reader).toInt()
                "spot" -> spot = nextDouble(reader)
                "strike_step" -> step = nextDouble(reader)
                "signed_moneyness_steps" -> signed = nextDouble(reader)
                "open" -> open = nextDouble(reader)
                "high" -> high = nextDouble(reader)
                "low" -> low = nextDouble(reader)
                "close" -> close = nextDouble(reader)
                "volume" -> volume = nextDouble(reader)
                "oi" -> oi = nextDouble(reader)
                "future_return_1m" -> f1 = nextDouble(reader)
                "future_return_3m" -> f3 = nextDouble(reader)
                "future_return_5m" -> f5 = nextDouble(reader)
                "future_return_15m" -> f15 = nextDouble(reader)
                "mfe_1m" -> mfe1 = nextDouble(reader)
                "mfe_3m" -> mfe3 = nextDouble(reader)
                "mfe_5m" -> mfe5 = nextDouble(reader)
                "mfe_15m" -> mfe15 = nextDouble(reader)
                "mae_1m" -> mae1 = nextDouble(reader)
                "mae_3m" -> mae3 = nextDouble(reader)
                "mae_5m" -> mae5 = nextDouble(reader)
                "mae_15m" -> mae15 = nextDouble(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (schema != ROW_SCHEMA || authority) return null
        val index = when (market.lowercase(Locale.ROOT)) {
            "nifty-50", "nifty", "nifty50" -> MarketIndex.NIFTY
            "sensex", "bse-sensex" -> MarketIndex.SENSEX
            else -> return null
        }
        if (optionType != "CE" && optionType != "PE") return null
        val timestamp = runCatching { OffsetDateTime.parse(capturedAt).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val expiryDay = runCatching { LocalDate.parse(expiry).toEpochDay().toInt() }.getOrNull() ?: return null
        val required = doubleArrayOf(strike, spot, step, signed, open, high, low, close, volume, oi, f1, f3, f5, f15, mfe1, mfe3, mfe5, mfe15, mae1, mae3, mae5, mae15)
        if (required.any { !it.isFinite() } || close <= 0.0 || open <= 0.0 || high <= 0.0 || low <= 0.0 || lot <= 0) return null
        if (high < maxOf(open, close, low) || low > minOf(open, close, high)) return null
        if (instrumentKey.isBlank()) stats.warnings += "Some rows lack instrument_key; contract counting may be approximate"
        return Record(timestamp, expiryDay, index, optionType, lot, open, high, low, close, volume, oi, spot, strike, step, signed, f1, f3, f5, f15, mfe1, mfe3, mfe5, mfe15, mae1, mae3, mae5, mae15)
    }

    private fun writer(file: File): DataOutputStream {
        file.parentFile?.mkdirs()
        return DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER)).also { it.writeUTF(MAGIC) }
    }

    private fun writeRecord(out: DataOutputStream, r: Record) {
        out.writeLong(r.timestampMs)
        out.writeInt(r.expiryEpochDay)
        out.writeByte(r.index.ordinal)
        out.writeByte(if (r.optionType == "CE") 0 else 1)
        out.writeInt(r.lotSize)
        floatArrayOf(
            r.open.toFloat(), r.high.toFloat(), r.low.toFloat(), r.close.toFloat(), r.volume.toFloat(), r.oi.toFloat(),
            r.spot.toFloat(), r.strike.toFloat(), r.strikeStep.toFloat(), r.signedMoneynessSteps.toFloat(),
            r.future1.toFloat(), r.future3.toFloat(), r.future5.toFloat(), r.future15.toFloat(),
            r.mfe1.toFloat(), r.mfe3.toFloat(), r.mfe5.toFloat(), r.mfe15.toFloat(),
            r.mae1.toFloat(), r.mae3.toFloat(), r.mae5.toFloat(), r.mae15.toFloat(),
        ).forEach(out::writeFloat)
    }

    private fun readRecord(input: DataInputStream): Record {
        val timestamp = input.readLong()
        val expiry = input.readInt()
        val indexOrdinal = input.readByte().toInt().coerceIn(0, MarketIndex.entries.lastIndex)
        val side = if (input.readByte().toInt() == 0) "CE" else "PE"
        val lot = input.readInt()
        val v = DoubleArray(22) { input.readFloat().toDouble() }
        return Record(timestamp, expiry, MarketIndex.entries[indexOrdinal], side, lot, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21])
    }

    private fun saveStats(file: File, stats: MutableStats) {
        val count = stats.accepted.coerceAtLeast(1L).toDouble()
        val ceContracts = stats.contracts.count { it.endsWith("|CE") }
        val peContracts = stats.contracts.count { it.endsWith("|PE") }
        val market = if (stats.contracts.any { it.startsWith("SENSEX|") } && stats.contracts.none { it.startsWith("NIFTY|") }) "SENSEX" else "NIFTY"
        val p = Properties().apply {
            setProperty("schema", ROW_SCHEMA)
            setProperty("files", stats.files.toString())
            setProperty("supported", stats.supported.toString())
            setProperty("rowsRead", stats.rowsRead.toString())
            setProperty("accepted", stats.accepted.toString())
            setProperty("rejected", stats.rejected.toString())
            setProperty("trainRows", stats.trainRows.toString())
            setProperty("validationRows", stats.validationRows.toString())
            setProperty("testRows", stats.testRows.toString())
            setProperty("ceRows", stats.ceRows.toString())
            setProperty("peRows", stats.peRows.toString())
            setProperty("e1Rows", stats.e1Rows.toString())
            setProperty("e2Rows", stats.e2Rows.toString())
            setProperty("e3Rows", stats.e3Rows.toString())
            setProperty("contracts", stats.contracts.size.toString())
            setProperty("ceContracts", ceContracts.toString())
            setProperty("peContracts", peContracts.toString())
            setProperty("expiries", stats.expiries.size.toString())
            setProperty("market", market)
            setProperty("avgMfe5", (stats.mfe5Sum / count).toString())
            setProperty("avgMae5", (stats.mae5Sum / count).toString())
            setProperty("avgNet5", (stats.net5Sum / count).toString())
            stats.minDate?.let { setProperty("fromDate", it.toString()) }
            stats.maxDate?.let { setProperty("toDate", it.toString()) }
            setProperty("warnings", stats.warnings.distinct().takeLast(12).joinToString("\n"))
            setProperty("errors", stats.errors.distinct().takeLast(12).joinToString("\n"))
        }
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { p.store(it, "VARDHANI aiml-historical-option-row-v1 compact corpus") }
    }

    private fun loadStats(): Properties = Properties().apply {
        if (statsFile.isFile) runCatching { FileInputStream(statsFile).use { load(it) } }
    }

    private fun file(split: Split): File = File(dataDir, split.fileName)
    private fun splitFromName(name: String): Split? {
        val base = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            base == "train.ndjson" || base == "train.jsonl" -> Split.TRAIN
            base == "validation.ndjson" || base == "validation.jsonl" || base == "val.ndjson" || base == "val.jsonl" -> Split.VALIDATION
            base == "test.ndjson" || base == "test.jsonl" -> Split.TEST
            else -> null
        }
    }

    private fun nextString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }
    private fun nextDouble(reader: JsonReader): Double = nextString(reader).toDoubleOrNull() ?: Double.NaN
    private fun nextBoolean(reader: JsonReader): Boolean = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.STRING -> reader.nextString().equals("true", true)
        JsonToken.NULL -> { reader.nextNull(); false }
        else -> { reader.skipValue(); false }
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    companion object {
        const val ROW_SCHEMA = "aiml-historical-option-row-v1"
        private const val MAGIC = "VARDHANI_AIML_OPTION_V1_COMPACT"
        private const val BUFFER = 1024 * 1024
        private const val PROGRESS_EVERY = 25_000L
        private const val MAX_ROWS = 20_000_000L
        private const val MAX_ZIP_ENTRIES = 50_000
        private val IST = java.time.ZoneOffset.ofHoursMinutes(5, 30)
        private const val SLIPPAGE_EACH_SIDE = 0.0015
        private const val FLAT_ROUND_TRIP_COST = 70.80

        fun proxyEngine(r: Record): Int {
            val range = (r.high - r.low).coerceAtLeast(0.01)
            val body = abs(r.close - r.open) / range
            return when {
                abs(r.signedMoneynessSteps) <= 1.5 && body >= 0.55 -> 3
                r.volume > 0.0 && r.oi > 0.0 && abs(r.signedMoneynessSteps) <= 2.5 -> 2
                else -> 1
            }
        }

        fun netReturn5(r: Record): Double {
            val deployed = (r.close * r.lotSize).coerceAtLeast(1.0)
            return r.future5 - 2.0 * SLIPPAGE_EACH_SIDE - FLAT_ROUND_TRIP_COST / deployed
        }

        fun success5(r: Record): Boolean {
            val stopHit = r.mae5 <= -0.075
            val targetHit = r.mfe5 >= 0.10
            return when {
                stopHit -> false // conservative stop-first when both barriers are reachable
                targetHit -> true
                else -> netReturn5(r) > 0.0
            }
        }

        fun relativeActivity(volume: Double): Double = (ln(1.0 + volume.coerceAtLeast(0.0)) / 4.0).coerceIn(0.0, 3.0)
    }

    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }
}
