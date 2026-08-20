package com.parmod.ema.training

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.floor

/**
 * Memory-safe VARDHANI local corpus store.
 *
 * Large imports are never materialized as one ByteArray or one giant candle map:
 * - CSV/TXT streams row-by-row,
 * - outer ZIP entries stream directly when possible,
 * - XLSX/JSON/nested ZIP entries spill to app-private temp files,
 * - parsed rows flush to append-only contract chunks periodically,
 * - training loads only the selected recent month window.
 */
class StreamingLocalHistoricalCorpusStore(private val context: Context) {
    data class ImportProgress(val completedFiles: Int, val totalFiles: Int, val message: String)

    private data class MutableStats(
        var filesImported: Int = 0,
        var supportedFiles: Int = 0,
        var rowsRead: Long = 0,
        var rowsAccepted: Long = 0,
        var rowsRejected: Long = 0,
        var duplicatesRemoved: Long = 0,
        var inferredLotSizeContracts: Int = 0,
        var bytesStreamed: Long = 0,
        val warnings: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    private data class ContractKey(
        val index: MarketIndex,
        val optionType: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val inferredLot: Boolean,
    ) {
        val stableKey: String get() = "${index.name}|$expiry|$strike|$optionType"
    }

    private class Batch {
        val candles = linkedMapOf<ContractKey, MutableList<UpstoxPlusHistoricalClient.Candle>>()
        var rows = 0
        fun clear() { candles.clear(); rows = 0 }
    }

    private val root = File(context.filesDir, "vardhani_local_corpus/v2")
    private val chunksDir = File(root, "chunks")
    private val tempDir = File(root, "tmp")
    private val statsFile = File(root, "stats.properties")
    private val chunkSequence = AtomicLong(System.currentTimeMillis())

    init {
        chunksDir.mkdirs()
        tempDir.mkdirs()
        cleanupTemps()
    }

    fun importUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): LocalCorpusSummary {
        require(uris.isNotEmpty()) { "Select at least one corpus file" }
        val stats = MutableStats()
        val batch = Batch()
        uris.forEachIndexed { index, uri ->
            if (shouldCancel()) error("Corpus import cancelled")
            val name = displayName(uri) ?: "import_${index + 1}"
            stats.filesImported++
            onProgress(ImportProgress(index, uris.size, "Streaming $name · ${stats.rowsAccepted} accepted"))
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    parseNamedStream(name, input, batch, stats, 0, shouldCancel) {
                        onProgress(ImportProgress(index, uris.size, "$name · ${stats.rowsRead} rows read · ${stats.rowsAccepted} accepted"))
                    }
                } ?: error("Unable to open $name")
                flushBatch(batch, stats, shouldCancel)
            }.onFailure { failure ->
                val message = "$name: ${(failure.message ?: failure::class.java.simpleName).take(220)}"
                if (stats.errors.lastOrNull() != message) stats.errors += message
            }
        }
        flushBatch(batch, stats, shouldCancel)
        updateStats(stats)
        onProgress(ImportProgress(uris.size, uris.size, "Import complete · ${stats.rowsAccepted} accepted candles"))
        return summary(extraWarnings = stats.warnings, extraErrors = stats.errors)
    }

    fun summary(extraWarnings: List<String> = emptyList(), extraErrors: List<String> = emptyList()): LocalCorpusSummary {
        val p = readStats()
        val keys = linkedMapOf<String, ChunkHeader>()
        chunksDir.listFiles { file -> file.isFile && file.extension == CHUNK_EXT }?.forEach { file ->
            runCatching { readHeader(file) }.getOrNull()?.let { keys[it.stableKey] = it }
        }
        val from = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val to = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val warnings = (p.getProperty("warnings").orEmpty().split('\n').filter(String::isNotBlank) + extraWarnings).distinct().takeLast(12)
        val errors = (p.getProperty("errors").orEmpty().split('\n').filter(String::isNotBlank) + extraErrors).distinct().takeLast(12)
        return LocalCorpusSummary(
            filesImported = p.getProperty("filesImported")?.toIntOrNull() ?: 0,
            supportedFiles = p.getProperty("supportedFiles")?.toIntOrNull() ?: 0,
            rowsRead = p.getProperty("rowsRead")?.toLongOrNull() ?: 0,
            rowsAccepted = p.getProperty("rowsAccepted")?.toLongOrNull() ?: 0,
            rowsRejected = p.getProperty("rowsRejected")?.toLongOrNull() ?: 0,
            duplicatesRemoved = p.getProperty("duplicatesRemoved")?.toLongOrNull() ?: 0,
            optionContracts = keys.size,
            niftyContracts = keys.values.count { it.index == MarketIndex.NIFTY },
            sensexContracts = keys.values.count { it.index == MarketIndex.SENSEX },
            ceContracts = keys.values.count { it.optionType == "CE" },
            peContracts = keys.values.count { it.optionType == "PE" },
            inferredLotSizeContracts = p.getProperty("inferredLotSizeContracts")?.toIntOrNull() ?: 0,
            fromDate = from,
            toDate = to,
            warnings = warnings,
            errors = errors,
        )
    }

    /** Load only the selected recent window; old rows are skipped while streaming chunk files. */
    fun loadSeriesWindow(index: MarketIndex, months: Int): List<HistoricalOptionSeries> {
        require(months in setOf(1, 3, 6, 12))
        val maxDate = summary().toDate ?: return emptyList()
        val cutoff = maxDate.minusMonths(months.toLong())
        val groups = linkedMapOf<String, MutableSeries>()
        chunksDir.listFiles { file -> file.isFile && file.extension == CHUNK_EXT }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching {
                    DataInputStream(FileInputStream(file).buffered()).use { input ->
                        val header = readHeader(input)
                        if (header.index != index) return@use
                        val target = groups.getOrPut(header.stableKey) { MutableSeries(header) }
                        repeat(header.count) {
                            val epochMs = input.readLong()
                            val offsetSec = input.readInt()
                            val open = input.readDouble(); val high = input.readDouble(); val low = input.readDouble(); val close = input.readDouble()
                            val volume = input.readLong(); val oi = input.readLong()
                            val time = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(offsetSec.coerceIn(-64800, 64800)))
                            val date = time.toLocalDate()
                            if (!date.isBefore(cutoff) && !date.isAfter(maxDate)) {
                                target.candles += UpstoxPlusHistoricalClient.Candle(time, open, high, low, close, volume, oi)
                            }
                        }
                    }
                }
            }
        return groups.values.mapNotNull { group ->
            val deduped = group.candles.sortedBy { it.time.toInstant().toEpochMilli() }.distinctBy { it.time.toInstant().toEpochMilli() }
            if (deduped.isEmpty()) null else group.header.toSeries(deduped)
        }.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
    }

    fun loadSeries(index: MarketIndex? = null): List<HistoricalOptionSeries> {
        val chosen = index ?: MarketIndex.NIFTY
        val first = loadSeriesWindow(chosen, 12)
        return if (index != null) first else first + loadSeriesWindow(MarketIndex.SENSEX, 12)
    }

    fun clear() {
        root.deleteRecursively()
        chunksDir.mkdirs(); tempDir.mkdirs()
    }

    private data class MutableSeries(val header: ChunkHeader, val candles: MutableList<UpstoxPlusHistoricalClient.Candle> = mutableListOf())

    private data class ChunkHeader(
        val index: MarketIndex,
        val optionType: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val source: String,
        val count: Int,
    ) {
        val stableKey: String get() = "${index.name}|$expiry|$strike|$optionType"
        fun toSeries(candles: List<UpstoxPlusHistoricalClient.Candle>) = HistoricalOptionSeries(index, optionType, strike, expiry, lotSize, symbol, source, candles)
    }

    private fun parseNamedStream(
        name: String,
        input: InputStream,
        batch: Batch,
        stats: MutableStats,
        depth: Int,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        if (depth > MAX_NESTED_ZIP_DEPTH) error("Nested ZIP depth exceeds safety limit")
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "txt" -> { stats.supportedFiles++; parseCsv(name, input, batch, stats, shouldCancel, progress) }
            "zip" -> { stats.supportedFiles++; parseZip(name, input, batch, stats, depth, shouldCancel, progress) }
            "xlsx" -> {
                stats.supportedFiles++
                val temp = spillToTemp(name, input, MAX_SPILL_BYTES, stats, shouldCancel)
                try { parseXlsxFile(name, temp, batch, stats, shouldCancel, progress) } finally { temp.delete() }
            }
            "json" -> { stats.supportedFiles++; parseJsonStream(name, input, batch, stats, shouldCancel, progress) }
            else -> stats.warnings += "$name skipped: supported formats are CSV/XLSX/JSON/ZIP"
        }
    }

    private fun parseZip(
        archiveName: String,
        input: InputStream,
        batch: Batch,
        stats: MutableStats,
        depth: Int,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        var entries = 0
        var expanded = 0L
        ZipInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { zip ->
            while (true) {
                if (shouldCancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                entries++
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains too many entries")
                val child = "$archiveName/${entry.name.substringAfterLast('/')}"
                val ext = child.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "csv" || ext == "txt") {
                    // Critical path: parse the compressed entry directly; never allocate the entry size.
                    parseNamedStream(child, CountingInputStream(zip) { n -> expanded += n; stats.bytesStreamed += n }, batch, stats, depth + 1, shouldCancel, progress)
                } else {
                    // Nested archives/XLSX need seekability. Spill to disk, not heap.
                    val temp = spillToTemp(child, CountingInputStream(zip) { n -> expanded += n; stats.bytesStreamed += n }, MAX_ZIP_EXPANDED_BYTES, stats, shouldCancel)
                    try {
                        FileInputStream(temp).buffered(BUFFER_SIZE).use { childInput ->
                            parseNamedStream(child, childInput, batch, stats, depth + 1, shouldCancel, progress)
                        }
                    } finally { temp.delete() }
                }
                if (expanded > MAX_ZIP_EXPANDED_BYTES) error("ZIP expanded data exceeds safety limit")
                flushIfNeeded(batch, stats, shouldCancel)
                zip.closeEntry()
            }
        }
    }

    private fun parseCsv(
        name: String,
        input: InputStream,
        batch: Batch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        val table = TableAccumulator(name, batch, stats, shouldCancel)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (shouldCancel()) error("Corpus import cancelled")
                val value = line ?: continue
                if (value.isBlank()) continue
                table.accept(parseCsvLine(value))
                if (stats.rowsRead > MAX_IMPORTED_ROWS) error("Imported row limit exceeded")
                if (stats.rowsRead % PROGRESS_ROWS == 0L) progress()
            }
        }
        flushIfNeeded(batch, stats, shouldCancel, force = true)
    }

    private fun parseJsonStream(
        name: String,
        input: InputStream,
        batch: Batch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> parseJsonArray(name, reader, batch, stats, shouldCancel, progress)
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    var found = false
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if ((key.equals("candles", true) || key.equals("data", true)) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                            parseJsonArray(name, reader, batch, stats, shouldCancel, progress); found = true
                        } else reader.skipValue()
                    }
                    reader.endObject()
                    if (!found) stats.warnings += "$name: JSON did not contain a candles/data array"
                }
                else -> { reader.skipValue(); stats.warnings += "$name: unsupported JSON root" }
            }
        }
        flushIfNeeded(batch, stats, shouldCancel, force = true)
    }

    private fun parseJsonArray(
        name: String,
        reader: JsonReader,
        batch: Batch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        val table = TableAccumulator(name, batch, stats, shouldCancel)
        var objectHeaders: List<String>? = null
        var initializedArrayHeaders = false
        reader.beginArray()
        while (reader.hasNext()) {
            if (shouldCancel()) error("Corpus import cancelled")
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val values = linkedMapOf<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        values[key] = jsonScalar(reader)
                    }
                    reader.endObject()
                    if (objectHeaders == null) { objectHeaders = values.keys.toList(); table.accept(objectHeaders!!) }
                    table.accept(objectHeaders!!.map { values[it].orEmpty() })
                }
                JsonToken.BEGIN_ARRAY -> {
                    if (!initializedArrayHeaders) { table.accept(listOf("timestamp", "open", "high", "low", "close", "volume", "oi")); initializedArrayHeaders = true }
                    val row = mutableListOf<String>()
                    reader.beginArray(); while (reader.hasNext()) row += jsonScalar(reader); reader.endArray()
                    table.accept(row)
                }
                else -> reader.skipValue()
            }
            if (stats.rowsRead % PROGRESS_ROWS == 0L && stats.rowsRead > 0) progress()
        }
        reader.endArray()
    }

    private fun jsonScalar(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }

    private fun parseXlsxFile(
        name: String,
        file: File,
        batch: Batch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        ZipFile(file).use { zip ->
            val shared = loadSharedStrings(zip, shouldCancel)
            val sheets = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase(Locale.ROOT).matches(Regex("xl/worksheets/sheet.*\\.xml")) }.take(MAX_XLSX_SHEETS + 1).toList()
            if (sheets.size > MAX_XLSX_SHEETS) error("XLSX contains too many sheets")
            if (sheets.isEmpty()) stats.warnings += "$name: no worksheet data found"
            sheets.forEach { entry ->
                if (shouldCancel()) error("Corpus import cancelled")
                val table = TableAccumulator("$name#${entry.name.substringAfterLast('/')}", batch, stats, shouldCancel)
                zip.getInputStream(entry).use { parseXlsxSheet(it, shared, table, shouldCancel, progress, stats) }
                flushIfNeeded(batch, stats, shouldCancel, force = true)
            }
        }
    }

    private fun loadSharedStrings(zip: ZipFile, shouldCancel: () -> Boolean): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inSi = false
        zip.getInputStream(entry).use { input ->
            saxFactory().newSAXParser().parse(input, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (tag(localName, qName) == "si") { if (shouldCancel()) error("Corpus import cancelled"); inSi = true; current.setLength(0) }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (inSi) current.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (tag(localName, qName) == "si") { result += current.toString(); inSi = false; if (result.size > MAX_SHARED_STRINGS) error("XLSX shared-string table exceeds memory-safe limit; export this workbook to CSV/ZIP") }
                }
            })
        }
        return result
    }

    private fun parseXlsxSheet(
        input: InputStream,
        shared: List<String>,
        table: TableAccumulator,
        shouldCancel: () -> Boolean,
        progress: () -> Unit,
        stats: MutableStats,
    ) {
        var row = sortedMapOf<Int, String>(); var col = 0; var type = ""; var value = StringBuilder(); var capture = false
        saxFactory().newSAXParser().parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tag(localName, qName)) {
                    "row" -> row = sortedMapOf()
                    "c" -> { col = columnIndex(attributes?.getValue("r").orEmpty()); type = attributes?.getValue("t").orEmpty(); value = StringBuilder() }
                    "v", "t" -> capture = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (capture) value.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                    "v", "t" -> capture = false
                    "c" -> { val raw = value.toString(); row[col] = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw }
                    "row" -> {
                        if (shouldCancel()) error("Corpus import cancelled")
                        val max = row.keys.maxOrNull() ?: -1
                        if (max >= 0) table.accept((0..max).map { row[it].orEmpty() })
                        if (stats.rowsRead % PROGRESS_ROWS == 0L && stats.rowsRead > 0) progress()
                    }
                }
            }
        })
    }

    private inner class TableAccumulator(
        private val sourceName: String,
        private val batch: Batch,
        private val stats: MutableStats,
        private val shouldCancel: () -> Boolean,
    ) {
        private var headers: Map<String, Int>? = null
        private val inferred = inferMetadata(sourceName)
        private var warningEmitted = false

        fun accept(row: List<String>) {
            if (row.all(String::isBlank)) return
            if (headers == null) {
                headers = row.mapIndexedNotNull { i, text -> normalizeHeader(text).takeIf(String::isNotBlank)?.let { it to i } }.toMap()
                return
            }
            if (shouldCancel()) error("Corpus import cancelled")
            stats.rowsRead++
            val h = headers!!
            val symbol = value(row, h, "tradingsymbol", "symbol", "instrument", "name").ifBlank { inferred.symbol }
            val index = parseIndex(value(row, h, "index", "underlying", "market", "indexname") + " $symbol $sourceName") ?: reject("rows without NIFTY/SENSEX option identity were skipped") ?: return
            val optionType = parseOptionType(value(row, h, "optiontype", "instrumenttype", "type") + " $symbol $sourceName") ?: reject("CE/PE identity is required for option-premium training") ?: return
            val strike = value(row, h, "strike", "strikeprice").toDoubleOrNull() ?: inferStrike("$symbol $sourceName", optionType) ?: run { stats.rowsRejected++; return }
            val tsText = value(row, h, "timestamp", "datetime", "dateandtime", "date_time").ifBlank {
                listOf(value(row, h, "date", "tradingdate"), value(row, h, "time", "tradingtime")).filter(String::isNotBlank).joinToString(" ")
            }
            val timestamp = parseTimestamp(tsText) ?: run { stats.rowsRejected++; return }
            val open = value(row, h, "open", "o").toDoubleOrNull(); val high = value(row, h, "high", "h").toDoubleOrNull(); val low = value(row, h, "low", "l").toDoubleOrNull(); val close = value(row, h, "close", "c", "ltp").toDoubleOrNull()
            if (open == null || high == null || low == null || close == null || minOf(open, high, low, close) <= 0.0) { stats.rowsRejected++; return }
            val expiry = parseDateFlexible(value(row, h, "expiry", "expirydate")) ?: inferred.expiry ?: timestamp.toLocalDate()
            val lotRaw = value(row, h, "lotsize", "lot", "quantityperlot", "minimumlot").toIntOrNull()
            val lot = (lotRaw ?: defaultLot(index)).coerceAtLeast(1)
            val key = ContractKey(index, optionType, strike, expiry, lot, symbol.ifBlank { inferred.symbol }, lotRaw == null)
            val volume = value(row, h, "volume", "vol", "v").toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
            val oi = value(row, h, "oi", "openinterest", "open_interest").toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
            batch.candles.getOrPut(key) { mutableListOf() } += UpstoxPlusHistoricalClient.Candle(timestamp, open, high, low, close, volume, oi)
            batch.rows++; stats.rowsAccepted++
            updateDateBounds(timestamp.toLocalDate(), stats)
            flushIfNeeded(batch, stats, shouldCancel)
        }

        private fun reject(message: String): Nothing? {
            stats.rowsRejected++
            if (!warningEmitted) { stats.warnings += "$sourceName: $message"; warningEmitted = true }
            return null
        }
    }

    private fun flushIfNeeded(batch: Batch, stats: MutableStats, shouldCancel: () -> Boolean, force: Boolean = false) {
        if (!force && batch.rows < FLUSH_ROWS) return
        flushBatch(batch, stats, shouldCancel)
    }

    private fun flushBatch(batch: Batch, stats: MutableStats, shouldCancel: () -> Boolean) {
        if (batch.rows == 0) return
        batch.candles.forEach { (key, rows) ->
            if (shouldCancel()) error("Corpus import cancelled")
            val sorted = rows.sortedBy { it.time.toInstant().toEpochMilli() }
            val deduped = sorted.distinctBy { it.time.toInstant().toEpochMilli() }
            stats.duplicatesRemoved += (rows.size - deduped.size)
            if (key.inferredLot) stats.inferredLotSizeContracts++
            if (deduped.isNotEmpty()) writeChunk(key, deduped)
        }
        batch.clear()
    }

    private fun writeChunk(key: ContractKey, candles: List<UpstoxPlusHistoricalClient.Candle>) {
        val hash = sha256(key.stableKey).take(20)
        val file = File(chunksDir, "${hash}_${chunkSequence.incrementAndGet()}.$CHUNK_EXT")
        val temp = File(tempDir, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered(BUFFER_SIZE)).use { out ->
            out.writeUTF(CHUNK_MAGIC); out.writeUTF(key.index.name); out.writeUTF(key.optionType); out.writeDouble(key.strike); out.writeUTF(key.expiry.toString()); out.writeInt(key.lotSize); out.writeUTF(key.symbol.take(240)); out.writeUTF("LOCAL_IMPORT"); out.writeInt(candles.size)
            candles.forEach { c -> out.writeLong(c.time.toInstant().toEpochMilli()); out.writeInt(c.time.offset.totalSeconds); out.writeDouble(c.open); out.writeDouble(c.high); out.writeDouble(c.low); out.writeDouble(c.close); out.writeLong(c.volume); out.writeLong(c.openInterest) }
        }
        check(temp.renameTo(file)) { "Could not finalize corpus chunk" }
    }

    private fun readHeader(file: File): ChunkHeader = DataInputStream(FileInputStream(file).buffered()).use(::readHeader)
    private fun readHeader(input: DataInputStream): ChunkHeader {
        require(input.readUTF() == CHUNK_MAGIC) { "Unsupported chunk version" }
        return ChunkHeader(MarketIndex.valueOf(input.readUTF()), input.readUTF(), input.readDouble(), LocalDate.parse(input.readUTF()), input.readInt(), input.readUTF(), input.readUTF(), input.readInt().also { require(it in 0..MAX_CHUNK_ROWS) })
    }

    private fun spillToTemp(name: String, input: InputStream, maxBytes: Long, stats: MutableStats, shouldCancel: () -> Boolean): File {
        val file = File(tempDir, "${sha256(name + System.nanoTime())}.spill")
        FileOutputStream(file).buffered(BUFFER_SIZE).use { out ->
            val buffer = ByteArray(BUFFER_SIZE); var total = 0L
            while (true) {
                if (shouldCancel()) error("Corpus import cancelled")
                val n = input.read(buffer); if (n <= 0) break
                total += n; stats.bytesStreamed += n
                if (total > maxBytes) error("$name exceeds ${(maxBytes / 1024 / 1024)} MB import safety limit")
                out.write(buffer, 0, n)
            }
        }
        return file
    }

    private class CountingInputStream(private val delegate: InputStream, private val count: (Long) -> Unit) : InputStream() {
        override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) count(it.toLong()) }
        override fun close() { /* do not close enclosing ZipInputStream */ }
    }

    private fun updateDateBounds(date: LocalDate, stats: MutableStats) {
        val p = readStats()
        val oldFrom = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val oldTo = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val pendingFrom = stats.warnings.firstOrNull { it.startsWith("__from=") }?.substringAfter('=')?.let { LocalDate.parse(it) }
        val pendingTo = stats.warnings.firstOrNull { it.startsWith("__to=") }?.substringAfter('=')?.let { LocalDate.parse(it) }
        val from = listOfNotNull(oldFrom, pendingFrom, date).minOrNull()!!
        val to = listOfNotNull(oldTo, pendingTo, date).maxOrNull()!!
        stats.warnings.removeAll { it.startsWith("__from=") || it.startsWith("__to=") }
        stats.warnings += "__from=$from"; stats.warnings += "__to=$to"
    }

    private fun updateStats(batch: MutableStats) {
        val p = readStats(); fun li(k: String) = p.getProperty(k)?.toLongOrNull() ?: 0L; fun ii(k: String) = p.getProperty(k)?.toIntOrNull() ?: 0
        p.setProperty("filesImported", (ii("filesImported") + batch.filesImported).toString()); p.setProperty("supportedFiles", (ii("supportedFiles") + batch.supportedFiles).toString())
        p.setProperty("rowsRead", (li("rowsRead") + batch.rowsRead).toString()); p.setProperty("rowsAccepted", (li("rowsAccepted") + batch.rowsAccepted).toString()); p.setProperty("rowsRejected", (li("rowsRejected") + batch.rowsRejected).toString()); p.setProperty("duplicatesRemoved", (li("duplicatesRemoved") + batch.duplicatesRemoved).toString()); p.setProperty("inferredLotSizeContracts", (ii("inferredLotSizeContracts") + batch.inferredLotSizeContracts).toString())
        batch.warnings.firstOrNull { it.startsWith("__from=") }?.substringAfter('=')?.let { p.setProperty("fromDate", it) }; batch.warnings.firstOrNull { it.startsWith("__to=") }?.substringAfter('=')?.let { p.setProperty("toDate", it) }
        val visibleWarnings = batch.warnings.filterNot { it.startsWith("__") }.distinct().takeLast(12); val visibleErrors = batch.errors.distinct().takeLast(12)
        p.setProperty("warnings", visibleWarnings.joinToString("\n")); p.setProperty("errors", visibleErrors.joinToString("\n")); statsFile.parentFile?.mkdirs(); FileOutputStream(statsFile).use { p.store(it, "VARDHANI streaming local corpus") }
    }

    private fun readStats() = Properties().apply { if (statsFile.isFile) runCatching { FileInputStream(statsFile).use { load(it) } } }
    private fun cleanupTemps() { tempDir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > TEMP_MAX_AGE_MS) it.delete() } }
    private fun displayName(uri: Uri): String? { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }; return uri.lastPathSegment }
    private fun value(row: List<String>, headers: Map<String, Int>, vararg aliases: String): String { aliases.forEach { headers[normalizeHeader(it)]?.let { idx -> return row.getOrNull(idx).orEmpty().trim() } }; return "" }
    private fun normalizeHeader(v: String) = v.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
    private fun parseIndex(text: String): MarketIndex? { val u = text.uppercase(Locale.ROOT); return when { "SENSEX" in u || "BSE" in u -> MarketIndex.SENSEX; "BANKNIFTY" in u || "BANK NIFTY" in u -> null; "NIFTY" in u -> MarketIndex.NIFTY; else -> null } }
    private fun parseOptionType(text: String): String? = Regex("(?:^|[^A-Z])(CE|PE)(?:$|[^A-Z])").find(text.uppercase(Locale.ROOT))?.groupValues?.get(1)
    private fun inferStrike(text: String, type: String): Double? = Regex("(\\d{4,6}(?:\\.\\d+)?)\\s*[-_ ]*$type\\b").find(text.uppercase(Locale.ROOT))?.groupValues?.get(1)?.toDoubleOrNull() ?: Regex("\\b(\\d{4,6}(?:\\.\\d+)?)\\b").findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()
    private data class Inferred(val symbol: String, val expiry: LocalDate?)
    private fun inferMetadata(name: String) = Inferred(name.substringAfterLast('/').substringBeforeLast('.'), findDateInText(name))
    private fun parseTimestamp(raw: String): OffsetDateTime? { val s = raw.trim(); if (s.isBlank()) return null; s.toLongOrNull()?.let { if (it > 10_000_000_000L) return Instant.ofEpochMilli(it).atOffset(IST); if (it > 1_000_000_000L) return Instant.ofEpochSecond(it).atOffset(IST) }; s.toDoubleOrNull()?.let { serial -> if (serial in 20_000.0..80_000.0) { val whole = floor(serial).toLong(); return LocalDate.of(1899,12,30).plusDays(whole).atStartOfDay().plusSeconds(((serial-whole)*86400).toLong()).atOffset(IST) } }; runCatching { return OffsetDateTime.parse(s) }; runCatching { return ZonedDateTime.parse(s).toOffsetDateTime() }; runCatching { return Instant.parse(s).atOffset(IST) }; DATE_TIME_PATTERNS.forEach { p -> try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)).atOffset(IST) } catch (_: DateTimeParseException) {} }; return parseDateFlexible(s)?.atStartOfDay()?.atOffset(IST) }
    private fun parseDateFlexible(raw: String): LocalDate? { val s = raw.trim(); if (s.isBlank()) return null; runCatching { return LocalDate.parse(s) }; DATE_PATTERNS.forEach { p -> try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)) } catch (_: DateTimeParseException) {} }; return null }
    private fun findDateInText(text: String): LocalDate? { Regex("\\d{4}-\\d{2}-\\d{2}").find(text)?.value?.let(::parseDateFlexible)?.let { return it }; return null }
    private fun defaultLot(index: MarketIndex) = if (index == MarketIndex.NIFTY) 65 else 20
    private fun parseCsvLine(line: String): List<String> { val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0; while (i < line.length) { val ch = line[i]; when { ch == '"' && quoted && i + 1 < line.length && line[i+1] == '"' -> { cur.append('"'); i++ }; ch == '"' -> quoted = !quoted; ch == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }; else -> cur.append(ch) }; i++ }; out += cur.toString(); return out }
    private fun columnIndex(ref: String): Int { var result = 0; var chars = 0; for (ch in ref) { if (!ch.isLetter()) break; result = result*26 + (ch.uppercaseChar()-'A'+1); chars++ }; return if (chars == 0) 0 else result-1 }
    private fun saxFactory() = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    private fun tag(local: String?, q: String?) = local?.takeIf(String::isNotEmpty) ?: q.orEmpty()
    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val IST = ZoneOffset.ofHoursMinutes(5, 30)
        private const val CHUNK_MAGIC = "VARDHANI_CORPUS_CHUNK_V2"
        private const val CHUNK_EXT = "vcc"
        private const val BUFFER_SIZE = 64 * 1024
        private const val FLUSH_ROWS = 20_000
        private const val PROGRESS_ROWS = 25_000L
        private const val MAX_CHUNK_ROWS = 100_000
        private const val MAX_IMPORTED_ROWS = 20_000_000L
        private const val MAX_ZIP_ENTRIES = 20_000
        private const val MAX_NESTED_ZIP_DEPTH = 3
        private const val MAX_ZIP_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_SPILL_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_XLSX_SHEETS = 128
        private const val MAX_SHARED_STRINGS = 500_000
        private const val TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val DATE_TIME_PATTERNS = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "d-MMM-yyyy HH:mm:ss", "d-MMM-yyyy HH:mm", "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm")
        private val DATE_PATTERNS = listOf("dd-MM-yyyy", "dd/MM/yyyy", "d-MMM-yyyy", "d MMM yyyy", "d MMM yy", "MM/dd/yyyy", "M/d/yyyy")
    }
}
