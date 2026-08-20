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
import java.time.ZoneOffset
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * Memory-safe VARDHANI importer for the existing `aiml-historical-option-row-v1`
 * research corpus. Multi-gigabyte train/validation/test NDJSON entries are consumed
 * directly from a ZIP stream and converted once into a compact binary cache.
 * No whole-entry ByteArray or 2 GB spill file is used for these streamable files.
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
    )

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
    )

    private data class Writers(
        val train: DataOutputStream,
        val validation: DataOutputStream,
        val test: DataOutputStream,
    ) : AutoCloseable {
        fun output(split: Split): DataOutputStream = when (split) {
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
        name.endsWith(".ndjson") || name.endsWith(".jsonl") || name.endsWith(".zip")
    }

    fun importUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ImportResult {
        require(uris.isNotEmpty()) { "Select at least one corpus file" }
        root.mkdirs()
        val tempRoot = File(root, "import-${System.currentTimeMillis()}")
        val tempData = File(tempRoot, "data").apply { mkdirs() }
        val tempStats = File(tempRoot, "stats.properties")
        val stats = MutableStats()
        var recognized = false
        val writers = Writers(
            newWriter(File(tempData, Split.TRAIN.fileName)),
            newWriter(File(tempData, Split.VALIDATION.fileName)),
            newWriter(File(tempData, Split.TEST.fileName)),
        )

        try {
            uris.forEachIndexed { fileIndex, uri ->
                if (shouldCancel()) error("Corpus import cancelled")
                val name = displayName(uri) ?: "corpus_${fileIndex + 1}"
                stats.files++
                onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, "Inspecting $name"))
                val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open $name")
                input.use { source ->
                    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                        "zip" -> {
                            recognized = parseZip(
                                archiveName = name,
                                input = source,
                                writers = writers,
                                stats = stats,
                                shouldCancel = shouldCancel,
                                onMessage = { msg -> onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, msg)) },
                            ) || recognized
                        }
                        "ndjson", "jsonl" -> {
                            val split = splitFromName(name)
                            if (split != null) {
                                recognized = true
                                stats.supported++
                                parseNdjson(
                                    name = name,
                                    split = split,
                                    input = source,
                                    output = writers.output(split),
                                    stats = stats,
                                    shouldCancel = shouldCancel,
                                    onMessage = { msg -> onProgress(ImportProgress(fileIndex, uris.size, stats.rowsRead, stats.accepted, msg)) },
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            writers.close()
            tempRoot.deleteRecursively()
            throw t
        }
        writers.close()

        if (!recognized) {
            tempRoot.deleteRecursively()
            return ImportResult(false, summary(), message = "No compatible pre-labelled train/validation/test NDJSON corpus found")
        }
        if (stats.trainRows <= 0 || stats.validationRows <= 0 || stats.testRows <= 0) {
            tempRoot.deleteRecursively()
            return ImportResult(
                recognized = true,
                summary = summary(extraErrors = listOf("Pre-labelled corpus requires train.ndjson, validation.ndjson and test.ndjson with accepted rows")),
                trainRows = stats.trainRows,
                validationRows = stats.validationRows,
                testRows = stats.testRows,
                message = "Incomplete pre-labelled corpus · train ${stats.trainRows} · validation ${stats.validationRows} · test ${stats.testRows}",
            )
        }

        saveStats(tempStats, stats)
        activateAtomically(tempData, tempStats)
        tempRoot.deleteRecursively()
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

    fun metadata(): Properties = loadStats()

    fun summary(extraWarnings: List<String> = emptyList(), extraErrors: List<String> = emptyList()): LocalCorpusSummary {
        val p = loadStats()
        if (p.isEmpty) return LocalCorpusSummary(errors = extraErrors, warnings = extraWarnings)
        return LocalCorpusSummary(
            filesImported = p.int("files"),
            supportedFiles = p.int("supported"),
            rowsRead = p.long("rowsRead"),
            rowsAccepted = p.long("accepted"),
            rowsRejected = p.long("rejected"),
            duplicatesRemoved = 0L,
            optionContracts = p.int("contracts"),
            niftyContracts = p.int("niftyContracts"),
            sensexContracts = p.int("sensexContracts"),
            ceContracts = p.int("ceContracts"),
            peContracts = p.int("peContracts"),
            inferredLotSizeContracts = 0,
            fromDate = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            toDate = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            warnings = (p.getProperty("warnings").orEmpty().split('\n').filter(String::isNotBlank) + extraWarnings).distinct().takeLast(12),
            errors = extraErrors.distinct().takeLast(12),
        )
    }

    fun clear() {
        root.deleteRecursively()
        dataDir.mkdirs()
    }

    /** Streams the compact cache; no split is loaded into heap as one collection. */
    fun forEach(
        split: Split,
        stride: Int = 1,
        maxAccepted: Long = Long.MAX_VALUE,
        shouldCancel: () -> Boolean = { false },
        action: (Record) -> Unit,
    ): Long {
        require(stride >= 1)
        val expected = rows(split)
        val path = file(split)
        if (expected <= 0L || !path.isFile) return 0L
        var emitted = 0L
        DataInputStream(BufferedInputStream(FileInputStream(path), BUFFER)).use { input ->
            require(input.readUTF() == MAGIC) { "Unsupported compact historical corpus version" }
            var rowIndex = 0L
            while (rowIndex < expected) {
                if (shouldCancel()) error("Training cancelled")
                val record = readRecord(input)
                if (rowIndex % stride == 0L) {
                    action(record)
                    emitted++
                    if (emitted >= maxAccepted) break
                }
                rowIndex++
            }
        }
        return emitted
    }

    private fun parseZip(
        archiveName: String,
        input: InputStream,
        writers: Writers,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
        onMessage: (String) -> Unit,
    ): Boolean {
        var recognized = false
        var entries = 0
        ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
            while (true) {
                if (shouldCancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                entries++
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains too many entries")
                val base = entry.name.substringAfterLast('/')
                val split = splitFromName(base)
                if (split != null && (base.endsWith(".ndjson", true) || base.endsWith(".jsonl", true))) {
                    recognized = true
                    stats.supported++
                    parseNdjson(
                        name = "$archiveName/$base",
                        split = split,
                        input = NonClosingInputStream(zip),
                        output = writers.output(split),
                        stats = stats,
                        shouldCancel = shouldCancel,
                        onMessage = onMessage,
                    )
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
        shouldCancel: () -> Boolean,
        onMessage: (String) -> Unit,
    ) {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8)).apply { isLenient = true }
        var localAccepted = 0L
        while (true) {
            if (shouldCancel()) error("Corpus import cancelled")
            val token = try {
                reader.peek()
            } catch (_: Throwable) {
                break
            }
            if (token == JsonToken.END_DOCUMENT) break
            if (token != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                stats.rejected++
                continue
            }
            stats.rowsRead++
            val record = try {
                readJsonRecord(reader, stats)
            } catch (_: Throwable) {
                null
            }
            if (record == null) {
                stats.rejected++
            } else {
                writeRecord(output, record)
                stats.accepted++
                localAccepted++
                when (split) {
                    Split.TRAIN -> stats.trainRows++
                    Split.VALIDATION -> stats.validationRows++
                    Split.TEST -> stats.testRows++
                }
                if (record.optionType == "CE") stats.ceRows++ else stats.peRows++
                when (proxyEngine(record)) {
                    2 -> stats.e2Rows++
                    3 -> stats.e3Rows++
                    else -> stats.e1Rows++
                }
                val date = Instant.ofEpochMilli(record.timestampMs).atOffset(IST).toLocalDate()
                stats.minDate = listOfNotNull(stats.minDate, date).minOrNull()
                stats.maxDate = listOfNotNull(stats.maxDate, date).maxOrNull()
                stats.expiries += record.expiryEpochDay.toString()
                stats.mfe5Sum += record.mfe5
                stats.mae5Sum += record.mae5
                stats.net5Sum += netReturn5(record)
            }
            if (stats.rowsRead > MAX_ROWS) error("Historical corpus exceeds supported row-count safety limit")
            if (stats.rowsRead % PROGRESS_EVERY == 0L) {
                output.flush()
                onMessage("$name · ${stats.rowsRead} rows read · ${stats.accepted} accepted")
            }
        }
        output.flush()
        onMessage("$name complete · $localAccepted accepted")
    }

    private fun readJsonRecord(reader: JsonReader, stats: MutableStats): Record? {
        var schema = ""
        var executionAuthority = false
        var capturedAt = ""
        var market = ""
        var expiry = ""
        var instrumentKey = ""
        var optionType = ""
        var strike = Double.NaN
        var lotSize = 0
        var spot = Double.NaN
        var strikeStep = Double.NaN
        var signedMoneyness = Double.NaN
        var open = Double.NaN
        var high = Double.NaN
        var low = Double.NaN
        var close = Double.NaN
        var volume = Double.NaN
        var oi = Double.NaN
        var future1 = Double.NaN
        var future3 = Double.NaN
        var future5 = Double.NaN
        var future15 = Double.NaN
        var mfe1 = Double.NaN
        var mfe3 = Double.NaN
        var mfe5 = Double.NaN
        var mfe15 = Double.NaN
        var mae1 = Double.NaN
        var mae3 = Double.NaN
        var mae5 = Double.NaN
        var mae15 = Double.NaN

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schema" -> schema = nextString(reader)
                "execution_authority" -> executionAuthority = nextBoolean(reader)
                "captured_at" -> capturedAt = nextString(reader)
                "market" -> market = nextString(reader)
                "expiry" -> expiry = nextString(reader)
                "instrument_key" -> instrumentKey = nextString(reader)
                "option_type" -> optionType = nextString(reader).uppercase(Locale.ROOT)
                "strike" -> strike = nextDouble(reader)
                "lot_size" -> lotSize = nextDouble(reader).toInt()
                "spot" -> spot = nextDouble(reader)
                "strike_step" -> strikeStep = nextDouble(reader)
                "signed_moneyness_steps" -> signedMoneyness = nextDouble(reader)
                "open" -> open = nextDouble(reader)
                "high" -> high = nextDouble(reader)
                "low" -> low = nextDouble(reader)
                "close" -> close = nextDouble(reader)
                "volume" -> volume = nextDouble(reader)
                "oi" -> oi = nextDouble(reader)
                "future_return_1m" -> future1 = nextDouble(reader)
                "future_return_3m" -> future3 = nextDouble(reader)
                "future_return_5m" -> future5 = nextDouble(reader)
                "future_return_15m" -> future15 = nextDouble(reader)
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

        if (schema != ROW_SCHEMA || executionAuthority) return null
        val index = when (market.lowercase(Locale.ROOT)) {
            "nifty-50", "nifty", "nifty50" -> MarketIndex.NIFTY
            "sensex", "bse-sensex" -> MarketIndex.SENSEX
            else -> return null
        }
        if (optionType != "CE" && optionType != "PE") return null
        val timestampMs = runCatching { OffsetDateTime.parse(capturedAt).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val expiryEpochDay = runCatching { LocalDate.parse(expiry).toEpochDay().toInt() }.getOrNull() ?: return null
        val required = doubleArrayOf(
            strike, spot, strikeStep, signedMoneyness, open, high, low, close, volume, oi,
            future1, future3, future5, future15, mfe1, mfe3, mfe5, mfe15, mae1, mae3, mae5, mae15,
        )
        if (required.any { !it.isFinite() }) return null
        if (lotSize <= 0 || open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return null
        if (high < maxOf(open, close, low) || low > minOf(open, close, high)) return null

        val contractIdentity = if (instrumentKey.isNotBlank()) instrumentKey else "${index.name}|$expiry|$strike|$optionType"
        stats.contracts += "$contractIdentity|$optionType"
        stats.expiries += expiry

        return Record(
            timestampMs = timestampMs,
            expiryEpochDay = expiryEpochDay,
            index = index,
            optionType = optionType,
            lotSize = lotSize,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            oi = oi,
            spot = spot,
            strike = strike,
            strikeStep = strikeStep,
            signedMoneynessSteps = signedMoneyness,
            future1 = future1,
            future3 = future3,
            future5 = future5,
            future15 = future15,
            mfe1 = mfe1,
            mfe3 = mfe3,
            mfe5 = mfe5,
            mfe15 = mfe15,
            mae1 = mae1,
            mae3 = mae3,
            mae5 = mae5,
            mae15 = mae15,
        )
    }

    private fun activateAtomically(newData: File, newStats: File) {
        val backup = File(root, "previous-active")
        backup.deleteRecursively()
        backup.mkdirs()
        val backupData = File(backup, "data")
        val backupStats = File(backup, "stats.properties")
        val hadData = dataDir.exists() && dataDir.listFiles()?.isNotEmpty() == true
        val hadStats = statsFile.isFile

        if (hadData && !dataDir.renameTo(backupData)) error("Could not preserve previous corpus before activation")
        if (hadStats && !statsFile.renameTo(backupStats)) {
            if (backupData.exists()) backupData.renameTo(dataDir)
            error("Could not preserve previous corpus metadata before activation")
        }

        try {
            dataDir.deleteRecursively()
            check(newData.renameTo(dataDir)) { "Could not activate compact historical corpus cache" }
            if (statsFile.exists()) statsFile.delete()
            check(newStats.renameTo(statsFile)) { "Could not activate historical corpus metadata" }
            backup.deleteRecursively()
        } catch (t: Throwable) {
            dataDir.deleteRecursively()
            statsFile.delete()
            if (backupData.exists()) backupData.renameTo(dataDir)
            if (backupStats.exists()) backupStats.renameTo(statsFile)
            throw t
        }
    }

    private fun newWriter(file: File): DataOutputStream {
        file.parentFile?.mkdirs()
        return DataOutputStream(BufferedOutputStream(FileOutputStream(file), BUFFER)).also { it.writeUTF(MAGIC) }
    }

    private fun writeRecord(out: DataOutputStream, r: Record) {
        out.writeLong(r.timestampMs)
        out.writeInt(r.expiryEpochDay)
        out.writeByte(r.index.ordinal)
        out.writeByte(if (r.optionType == "CE") 0 else 1)
        out.writeInt(r.lotSize)
        doubleArrayOf(
            r.open, r.high, r.low, r.close, r.volume, r.oi, r.spot, r.strike, r.strikeStep, r.signedMoneynessSteps,
            r.future1, r.future3, r.future5, r.future15, r.mfe1, r.mfe3, r.mfe5, r.mfe15, r.mae1, r.mae3, r.mae5, r.mae15,
        ).forEach { out.writeFloat(it.toFloat()) }
    }

    private fun readRecord(input: DataInputStream): Record {
        val timestamp = input.readLong()
        val expiry = input.readInt()
        val indexOrdinal = input.readByte().toInt().coerceIn(0, MarketIndex.entries.lastIndex)
        val side = if (input.readByte().toInt() == 0) "CE" else "PE"
        val lot = input.readInt()
        val v = DoubleArray(22) { input.readFloat().toDouble() }
        return Record(
            timestamp, expiry, MarketIndex.entries[indexOrdinal], side, lot,
            v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9],
            v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18], v[19], v[20], v[21],
        )
    }

    private fun saveStats(file: File, s: MutableStats) {
        val denominator = s.accepted.coerceAtLeast(1L).toDouble()
        val niftyContracts = s.contracts.count { "NSE" in it.uppercase(Locale.ROOT) || "NIFTY" in it.uppercase(Locale.ROOT) }
        val sensexContracts = s.contracts.size - niftyContracts
        val ceContracts = s.contracts.count { it.endsWith("|CE") }
        val peContracts = s.contracts.count { it.endsWith("|PE") }
        val p = Properties().apply {
            setProperty("schema", ROW_SCHEMA)
            setProperty("files", s.files.toString())
            setProperty("supported", s.supported.toString())
            setProperty("rowsRead", s.rowsRead.toString())
            setProperty("accepted", s.accepted.toString())
            setProperty("rejected", s.rejected.toString())
            setProperty("trainRows", s.trainRows.toString())
            setProperty("validationRows", s.validationRows.toString())
            setProperty("testRows", s.testRows.toString())
            setProperty("ceRows", s.ceRows.toString())
            setProperty("peRows", s.peRows.toString())
            setProperty("e1Rows", s.e1Rows.toString())
            setProperty("e2Rows", s.e2Rows.toString())
            setProperty("e3Rows", s.e3Rows.toString())
            setProperty("contracts", s.contracts.size.toString())
            setProperty("niftyContracts", niftyContracts.toString())
            setProperty("sensexContracts", sensexContracts.toString())
            setProperty("ceContracts", ceContracts.toString())
            setProperty("peContracts", peContracts.toString())
            setProperty("expiries", s.expiries.size.toString())
            setProperty("market", when {
                niftyContracts > 0 && sensexContracts == 0 -> "NIFTY"
                sensexContracts > 0 && niftyContracts == 0 -> "SENSEX"
                else -> "MIXED"
            })
            setProperty("avgMfe5", (s.mfe5Sum / denominator).toString())
            setProperty("avgMae5", (s.mae5Sum / denominator).toString())
            setProperty("avgNet5", (s.net5Sum / denominator).toString())
            s.minDate?.let { setProperty("fromDate", it.toString()) }
            s.maxDate?.let { setProperty("toDate", it.toString()) }
            setProperty("warnings", s.warnings.distinct().takeLast(12).joinToString("\n"))
        }
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { p.store(it, "VARDHANI pre-labelled historical option corpus") }
    }

    private fun loadStats(): Properties = Properties().apply {
        if (statsFile.isFile) runCatching { FileInputStream(statsFile).use { load(it) } }
    }

    private fun file(split: Split): File = File(dataDir, split.fileName)

    private fun splitFromName(name: String): Split? {
        val base = name.substringAfterLast('/').lowercase(Locale.ROOT)
        return when (base) {
            "train.ndjson", "train.jsonl" -> Split.TRAIN
            "validation.ndjson", "validation.jsonl", "val.ndjson", "val.jsonl" -> Split.VALIDATION
            "test.ndjson", "test.jsonl" -> Split.TEST
            else -> null
        }
    }

    private fun nextString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }

    private fun nextDouble(reader: JsonReader): Double = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextDouble()
        JsonToken.STRING -> reader.nextString().toDoubleOrNull() ?: Double.NaN
        JsonToken.NULL -> { reader.nextNull(); Double.NaN }
        else -> { reader.skipValue(); Double.NaN }
    }

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

    private fun Properties.int(key: String): Int = getProperty(key)?.toIntOrNull() ?: 0
    private fun Properties.long(key: String): Long = getProperty(key)?.toLongOrNull() ?: 0L

    companion object {
        const val ROW_SCHEMA = "aiml-historical-option-row-v1"
        private const val MAGIC = "VARDHANI_AIML_OPTION_V1_COMPACT"
        private const val BUFFER = 1024 * 1024
        private const val PROGRESS_EVERY = 25_000L
        private const val MAX_ROWS = 20_000_000L
        private const val MAX_ZIP_ENTRIES = 50_000
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
        private const val TARGET_RETURN = 0.10
        private const val STOP_RETURN = 0.075
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

        /** Cost-aware conservative 5m realised-return proxy from precomputed future labels. */
        fun netReturn5(r: Record): Double {
            val rawReturn = when {
                r.mae5 <= -STOP_RETURN -> -STOP_RETURN
                r.mfe5 >= TARGET_RETURN -> TARGET_RETURN
                else -> r.future5
            }
            val deployed = (r.close * r.lotSize).coerceAtLeast(1.0)
            val costs = 2.0 * SLIPPAGE_EACH_SIDE + FLAT_ROUND_TRIP_COST / deployed
            return rawReturn - costs
        }

        fun success5(r: Record): Boolean = netReturn5(r) > 0.0

        fun relativeActivity(volume: Double): Double =
            (ln(1.0 + volume.coerceAtLeast(0.0)) / 4.0).coerceIn(0.0, 3.0)
    }

    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }
}
