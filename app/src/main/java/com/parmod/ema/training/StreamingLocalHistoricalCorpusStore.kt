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

/** Memory-safe local historical option corpus storage for VARDHANI. */
class StreamingLocalHistoricalCorpusStore(private val context: Context) {
    data class ImportProgress(val completedFiles: Int, val totalFiles: Int, val message: String)

    private data class Stats(
        var files: Int = 0,
        var supported: Int = 0,
        var read: Long = 0,
        var accepted: Long = 0,
        var rejected: Long = 0,
        var deduped: Long = 0,
        var inferredLots: Int = 0,
        var minDate: LocalDate? = null,
        var maxDate: LocalDate? = null,
        val warnings: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    private data class Key(
        val index: MarketIndex,
        val type: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val inferredLot: Boolean,
    ) {
        val id: String get() = "${index.name}|$expiry|$strike|$type"
    }

    private data class Header(
        val index: MarketIndex,
        val type: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val count: Int,
    ) {
        val id: String get() = "${index.name}|$expiry|$strike|$type"
    }

    private class Batch {
        val rows = linkedMapOf<Key, MutableList<UpstoxPlusHistoricalClient.Candle>>()
        var size = 0
        fun clear() { rows.clear(); size = 0 }
    }

    private val root = File(context.filesDir, "vardhani_local_corpus/v2")
    private val chunks = File(root, "chunks")
    private val temp = File(root, "tmp")
    private val statsFile = File(root, "stats.properties")
    private val sequence = AtomicLong(System.currentTimeMillis())

    init {
        chunks.mkdirs()
        temp.mkdirs()
        temp.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > TEMP_MAX_AGE_MS) it.delete() }
    }

    fun importUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): LocalCorpusSummary {
        require(uris.isNotEmpty()) { "Select at least one corpus file" }
        val stats = Stats()
        val batch = Batch()
        uris.forEachIndexed { i, uri ->
            if (shouldCancel()) error("Corpus import cancelled")
            val name = displayName(uri) ?: "import_${i + 1}"
            stats.files++
            onProgress(ImportProgress(i, uris.size, "Streaming $name · ${stats.accepted} accepted"))
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    parse(name, input, batch, stats, 0, shouldCancel) {
                        onProgress(ImportProgress(i, uris.size, "$name · ${stats.read} rows read · ${stats.accepted} accepted"))
                    }
                } ?: error("Unable to open $name")
                flush(batch, stats, shouldCancel)
            }.onFailure { e ->
                val msg = "$name: ${(e.message ?: e::class.java.simpleName).take(220)}"
                if (msg !in stats.errors) stats.errors += msg
            }
        }
        flush(batch, stats, shouldCancel)
        saveStats(stats)
        onProgress(ImportProgress(uris.size, uris.size, "Import complete · ${stats.accepted} accepted candles"))
        return summary(stats.warnings, stats.errors)
    }

    fun summary(extraWarnings: List<String> = emptyList(), extraErrors: List<String> = emptyList()): LocalCorpusSummary {
        val props = loadStats()
        val unique = linkedMapOf<String, Header>()
        chunks.listFiles { f -> f.isFile && f.extension == EXT }?.forEach { f ->
            runCatching { readHeader(f) }.getOrNull()?.let { unique[it.id] = it }
        }
        return LocalCorpusSummary(
            filesImported = props.int("files"),
            supportedFiles = props.int("supported"),
            rowsRead = props.long("read"),
            rowsAccepted = props.long("accepted"),
            rowsRejected = props.long("rejected"),
            duplicatesRemoved = props.long("deduped"),
            optionContracts = unique.size,
            niftyContracts = unique.values.count { it.index == MarketIndex.NIFTY },
            sensexContracts = unique.values.count { it.index == MarketIndex.SENSEX },
            ceContracts = unique.values.count { it.type == "CE" },
            peContracts = unique.values.count { it.type == "PE" },
            inferredLotSizeContracts = props.int("inferredLots"),
            fromDate = props.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            toDate = props.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            warnings = (props.getProperty("warnings").orEmpty().split('\n').filter(String::isNotBlank) + extraWarnings).distinct().takeLast(12),
            errors = (props.getProperty("errors").orEmpty().split('\n').filter(String::isNotBlank) + extraErrors).distinct().takeLast(12),
        )
    }

    /** Reads only the selected recent window, skipping older rows while the chunk is streamed. */
    fun loadSeriesWindow(index: MarketIndex, months: Int): List<HistoricalOptionSeries> {
        require(months in setOf(1, 3, 6, 12))
        val end = summary().toDate ?: return emptyList()
        val start = end.minusMonths(months.toLong())
        data class MutableSeries(val header: Header, val candles: MutableList<UpstoxPlusHistoricalClient.Candle> = mutableListOf())
        val grouped = linkedMapOf<String, MutableSeries>()
        chunks.listFiles { f -> f.isFile && f.extension == EXT }?.sortedBy { it.name }?.forEach { f ->
            runCatching {
                DataInputStream(FileInputStream(f).buffered(BUFFER)).use { input ->
                    val h = readHeader(input)
                    if (h.index != index) return@use
                    val target = grouped.getOrPut(h.id) { MutableSeries(h) }
                    repeat(h.count) {
                        val epoch = input.readLong()
                        val offset = input.readInt().coerceIn(-64800, 64800)
                        val o = input.readDouble(); val hi = input.readDouble(); val lo = input.readDouble(); val c = input.readDouble()
                        val vol = input.readLong(); val oi = input.readLong()
                        val time = Instant.ofEpochMilli(epoch).atOffset(ZoneOffset.ofTotalSeconds(offset))
                        val date = time.toLocalDate()
                        if (!date.isBefore(start) && !date.isAfter(end)) target.candles += UpstoxPlusHistoricalClient.Candle(time, o, hi, lo, c, vol, oi)
                    }
                }
            }
        }
        return grouped.values.mapNotNull { m ->
            val candles = m.candles.sortedBy { it.time.toInstant().toEpochMilli() }.distinctBy { it.time.toInstant().toEpochMilli() }
            if (candles.isEmpty()) null else HistoricalOptionSeries(m.header.index, m.header.type, m.header.strike, m.header.expiry, m.header.lotSize, m.header.symbol, "LOCAL_IMPORT", candles)
        }.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
    }

    fun clear() {
        root.deleteRecursively()
        chunks.mkdirs()
        temp.mkdirs()
    }

    private fun parse(
        name: String,
        input: InputStream,
        batch: Batch,
        stats: Stats,
        depth: Int,
        cancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        if (depth > MAX_DEPTH) error("Nested ZIP depth exceeds safety limit")
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "txt" -> { stats.supported++; parseCsv(name, input, batch, stats, cancel, progress) }
            "json" -> { stats.supported++; parseJson(name, input, batch, stats, cancel, progress) }
            "zip" -> { stats.supported++; parseZip(name, input, batch, stats, depth, cancel, progress) }
            "xlsx" -> {
                stats.supported++
                val file = spill(name, input, cancel)
                try { parseXlsx(name, file, batch, stats, cancel, progress) } finally { file.delete() }
            }
            else -> stats.warnings += "$name skipped: supported formats are CSV/XLSX/JSON/ZIP"
        }
    }

    private fun parseZip(
        archive: String,
        input: InputStream,
        batch: Batch,
        stats: Stats,
        depth: Int,
        cancel: () -> Boolean,
        progress: () -> Unit,
    ) {
        var entries = 0
        ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
            while (true) {
                if (cancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) { zip.closeEntry(); continue }
                entries++
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains too many entries")
                val child = "$archive/${entry.name.substringAfterLast('/')}"
                val ext = child.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext == "csv" || ext == "txt" || ext == "json") {
                    // Direct streaming: no ByteArray allocation for large archive entries.
                    parse(child, NonClosingInputStream(zip), batch, stats, depth + 1, cancel, progress)
                } else {
                    // XLSX/nested ZIP require seekability; spill compressed entry to app-private disk.
                    val file = spill(child, NonClosingInputStream(zip), cancel)
                    try { FileInputStream(file).buffered(BUFFER).use { parse(child, it, batch, stats, depth + 1, cancel, progress) } } finally { file.delete() }
                }
                flushIfNeeded(batch, stats, cancel, true)
                zip.closeEntry()
            }
        }
    }

    private fun parseCsv(name: String, input: InputStream, batch: Batch, stats: Stats, cancel: () -> Boolean, progress: () -> Unit) {
        val table = Table(name, batch, stats, cancel)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (cancel()) error("Corpus import cancelled")
                val value = line ?: continue
                if (value.isBlank()) continue
                table.accept(csv(value))
                if (stats.read > MAX_ROWS) error("Imported row limit exceeded")
                if (stats.read > 0 && stats.read % PROGRESS_EVERY == 0L) progress()
            }
        }
        flushIfNeeded(batch, stats, cancel, true)
    }

    private fun parseJson(name: String, input: InputStream, batch: Batch, stats: Stats, cancel: () -> Boolean, progress: () -> Unit) {
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> jsonArray(name, reader, batch, stats, cancel, progress)
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    var found = false
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if ((key.equals("candles", true) || key.equals("data", true)) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                            jsonArray(name, reader, batch, stats, cancel, progress); found = true
                        } else reader.skipValue()
                    }
                    reader.endObject()
                    if (!found) stats.warnings += "$name: JSON did not contain a candles/data array"
                }
                else -> reader.skipValue()
            }
        }
        flushIfNeeded(batch, stats, cancel, true)
    }

    private fun jsonArray(name: String, reader: JsonReader, batch: Batch, stats: Stats, cancel: () -> Boolean, progress: () -> Unit) {
        val table = Table(name, batch, stats, cancel)
        var headers: List<String>? = null
        var arrayHeaders = false
        reader.beginArray()
        while (reader.hasNext()) {
            if (cancel()) error("Corpus import cancelled")
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val values = linkedMapOf<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) { val k = reader.nextName(); values[k] = scalar(reader) }
                    reader.endObject()
                    if (headers == null) { headers = values.keys.toList(); table.accept(headers!!) }
                    table.accept(headers!!.map { values[it].orEmpty() })
                }
                JsonToken.BEGIN_ARRAY -> {
                    if (!arrayHeaders) { table.accept(listOf("timestamp", "open", "high", "low", "close", "volume", "oi")); arrayHeaders = true }
                    val row = mutableListOf<String>()
                    reader.beginArray(); while (reader.hasNext()) row += scalar(reader); reader.endArray(); table.accept(row)
                }
                else -> reader.skipValue()
            }
            if (stats.read > 0 && stats.read % PROGRESS_EVERY == 0L) progress()
        }
        reader.endArray()
    }

    private fun scalar(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); "" }
        else -> { reader.skipValue(); "" }
    }

    private fun parseXlsx(name: String, file: File, batch: Batch, stats: Stats, cancel: () -> Boolean, progress: () -> Unit) {
        ZipFile(file).use { zip ->
            val shared = sharedStrings(zip, cancel)
            val sheets = zip.entries().asSequence().filter { !it.isDirectory && it.name.lowercase(Locale.ROOT).matches(Regex("xl/worksheets/sheet.*\\.xml")) }.take(MAX_SHEETS + 1).toList()
            if (sheets.size > MAX_SHEETS) error("XLSX contains too many sheets")
            if (sheets.isEmpty()) stats.warnings += "$name: no worksheet data found"
            sheets.forEach { entry ->
                val table = Table("$name#${entry.name.substringAfterLast('/')}", batch, stats, cancel)
                zip.getInputStream(entry).use { sheet(it, shared, table, stats, cancel, progress) }
                flushIfNeeded(batch, stats, cancel, true)
            }
        }
    }

    private fun sharedStrings(zip: ZipFile, cancel: () -> Boolean): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        val text = StringBuilder()
        var active = false
        zip.getInputStream(entry).use { input ->
            sax().newSAXParser().parse(input, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (tag(localName, qName) == "si") { if (cancel()) error("Corpus import cancelled"); active = true; text.setLength(0) }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (active) text.append(ch, start, length) }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (tag(localName, qName) == "si") { out += text.toString(); active = false; if (out.size > MAX_SHARED_STRINGS) error("XLSX shared-string table is too large; export this workbook as CSV/ZIP for memory-safe import") }
                }
            })
        }
        return out
    }

    private fun sheet(input: InputStream, shared: List<String>, table: Table, stats: Stats, cancel: () -> Boolean, progress: () -> Unit) {
        var row = sortedMapOf<Int, String>()
        var col = 0
        var type = ""
        var value = StringBuilder()
        var capture = false
        sax().newSAXParser().parse(input, object : DefaultHandler() {
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
                        if (cancel()) error("Corpus import cancelled")
                        val max = row.keys.maxOrNull() ?: -1
                        if (max >= 0) table.accept((0..max).map { row[it].orEmpty() })
                        if (stats.read > 0 && stats.read % PROGRESS_EVERY == 0L) progress()
                    }
                }
            }
        })
    }

    private inner class Table(private val source: String, private val batch: Batch, private val stats: Stats, private val cancel: () -> Boolean) {
        private var header: Map<String, Int>? = null
        private val inferred = Inferred(source.substringAfterLast('/').substringBeforeLast('.'), dateInText(source))
        private var warned = false

        fun accept(row: List<String>) {
            if (row.all(String::isBlank)) return
            if (header == null) {
                header = row.mapIndexedNotNull { i, v -> norm(v).takeIf(String::isNotBlank)?.let { it to i } }.toMap()
                return
            }
            if (cancel()) error("Corpus import cancelled")
            stats.read++
            val h = header!!
            val symbol = get(row, h, "tradingsymbol", "symbol", "instrument", "name").ifBlank { inferred.symbol }
            val index = parseIndex(get(row, h, "index", "underlying", "market", "indexname") + " $symbol $source") ?: reject("rows without NIFTY/SENSEX option identity were skipped") ?: return
            val type = parseType(get(row, h, "optiontype", "instrumenttype", "type") + " $symbol $source") ?: reject("CE/PE identity is required for option-premium training") ?: return
            val strike = get(row, h, "strike", "strikeprice").toDoubleOrNull() ?: inferStrike("$symbol $source", type) ?: run { stats.rejected++; return }
            val timestampText = get(row, h, "timestamp", "datetime", "dateandtime", "date_time").ifBlank { listOf(get(row, h, "date", "tradingdate"), get(row, h, "time", "tradingtime")).filter(String::isNotBlank).joinToString(" ") }
            val timestamp = parseTimestamp(timestampText) ?: run { stats.rejected++; return }
            val o = get(row, h, "open", "o").toDoubleOrNull(); val hi = get(row, h, "high", "h").toDoubleOrNull(); val lo = get(row, h, "low", "l").toDoubleOrNull(); val c = get(row, h, "close", "c", "ltp").toDoubleOrNull()
            if (o == null || hi == null || lo == null || c == null || minOf(o, hi, lo, c) <= 0.0) { stats.rejected++; return }
            val expiry = parseDate(get(row, h, "expiry", "expirydate")) ?: inferred.expiry ?: timestamp.toLocalDate()
            val lotRaw = get(row, h, "lotsize", "lot", "quantityperlot", "minimumlot").toIntOrNull()
            val lot = (lotRaw ?: if (index == MarketIndex.NIFTY) 65 else 20).coerceAtLeast(1)
            val key = Key(index, type, strike, expiry, lot, symbol.ifBlank { inferred.symbol }, lotRaw == null)
            val volume = get(row, h, "volume", "vol", "v").toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
            val oi = get(row, h, "oi", "openinterest", "open_interest").toDoubleOrNull()?.toLong()?.coerceAtLeast(0) ?: 0
            batch.rows.getOrPut(key) { mutableListOf() } += UpstoxPlusHistoricalClient.Candle(timestamp, o, hi, lo, c, volume, oi)
            batch.size++
            stats.accepted++
            stats.minDate = listOfNotNull(stats.minDate, timestamp.toLocalDate()).minOrNull()
            stats.maxDate = listOfNotNull(stats.maxDate, timestamp.toLocalDate()).maxOrNull()
            flushIfNeeded(batch, stats, cancel, false)
        }

        private fun reject(message: String): Nothing? {
            stats.rejected++
            if (!warned) { stats.warnings += "$source: $message"; warned = true }
            return null
        }
    }

    private fun flushIfNeeded(batch: Batch, stats: Stats, cancel: () -> Boolean, force: Boolean) {
        if (force || batch.size >= FLUSH_ROWS) flush(batch, stats, cancel)
    }

    private fun flush(batch: Batch, stats: Stats, cancel: () -> Boolean) {
        if (batch.size == 0) return
        batch.rows.forEach { (key, rows) ->
            if (cancel()) error("Corpus import cancelled")
            val clean = rows.sortedBy { it.time.toInstant().toEpochMilli() }.distinctBy { it.time.toInstant().toEpochMilli() }
            stats.deduped += rows.size - clean.size
            if (key.inferredLot) stats.inferredLots++
            if (clean.isNotEmpty()) writeChunk(key, clean)
        }
        batch.clear()
    }

    private fun writeChunk(key: Key, rows: List<UpstoxPlusHistoricalClient.Candle>) {
        val file = File(chunks, "${sha(key.id).take(18)}_${sequence.incrementAndGet()}.$EXT")
        val tmp = File(temp, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(tmp).buffered(BUFFER)).use { out ->
            out.writeUTF(MAGIC); out.writeUTF(key.index.name); out.writeUTF(key.type); out.writeDouble(key.strike); out.writeUTF(key.expiry.toString()); out.writeInt(key.lotSize); out.writeUTF(key.symbol.take(240)); out.writeInt(rows.size)
            rows.forEach { c -> out.writeLong(c.time.toInstant().toEpochMilli()); out.writeInt(c.time.offset.totalSeconds); out.writeDouble(c.open); out.writeDouble(c.high); out.writeDouble(c.low); out.writeDouble(c.close); out.writeLong(c.volume); out.writeLong(c.openInterest) }
        }
        check(tmp.renameTo(file)) { "Could not finalize imported corpus chunk" }
    }

    private fun readHeader(file: File): Header = DataInputStream(FileInputStream(file).buffered(BUFFER)).use { readHeader(it) }
    private fun readHeader(input: DataInputStream): Header {
        require(input.readUTF() == MAGIC) { "Unsupported corpus chunk version" }
        val h = Header(MarketIndex.valueOf(input.readUTF()), input.readUTF(), input.readDouble(), LocalDate.parse(input.readUTF()), input.readInt(), input.readUTF(), input.readInt())
        require(h.count in 0..MAX_CHUNK_ROWS) { "Invalid corpus chunk row count" }
        return h
    }

    private fun spill(name: String, input: InputStream, cancel: () -> Boolean): File {
        val file = File(temp, "${sha(name + System.nanoTime())}.spill")
        var total = 0L
        FileOutputStream(file).buffered(BUFFER).use { out ->
            val b = ByteArray(BUFFER)
            while (true) {
                if (cancel()) error("Corpus import cancelled")
                val n = input.read(b); if (n <= 0) break
                total += n
                if (total > MAX_SPILL) error("$name exceeds ${MAX_SPILL / 1024 / 1024} MB import safety limit")
                out.write(b, 0, n)
            }
        }
        return file
    }

    private fun saveStats(stats: Stats) {
        val p = loadStats()
        p.setProperty("files", (p.int("files") + stats.files).toString())
        p.setProperty("supported", (p.int("supported") + stats.supported).toString())
        p.setProperty("read", (p.long("read") + stats.read).toString())
        p.setProperty("accepted", (p.long("accepted") + stats.accepted).toString())
        p.setProperty("rejected", (p.long("rejected") + stats.rejected).toString())
        p.setProperty("deduped", (p.long("deduped") + stats.deduped).toString())
        p.setProperty("inferredLots", (p.int("inferredLots") + stats.inferredLots).toString())
        val oldFrom = p.getProperty("fromDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val oldTo = p.getProperty("toDate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        listOfNotNull(oldFrom, stats.minDate).minOrNull()?.let { p.setProperty("fromDate", it.toString()) }
        listOfNotNull(oldTo, stats.maxDate).maxOrNull()?.let { p.setProperty("toDate", it.toString()) }
        p.setProperty("warnings", stats.warnings.distinct().takeLast(12).joinToString("\n"))
        p.setProperty("errors", stats.errors.distinct().takeLast(12).joinToString("\n"))
        statsFile.parentFile?.mkdirs()
        FileOutputStream(statsFile).use { p.store(it, "VARDHANI streaming local corpus") }
    }

    private fun loadStats(): Properties = Properties().apply { if (statsFile.isFile) runCatching { FileInputStream(statsFile).use { load(it) } } }
    private fun Properties.int(key: String) = getProperty(key)?.toIntOrNull() ?: 0
    private fun Properties.long(key: String) = getProperty(key)?.toLongOrNull() ?: 0L
    private fun displayName(uri: Uri): String? { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }; return uri.lastPathSegment }
    private fun get(row: List<String>, h: Map<String, Int>, vararg names: String): String { names.forEach { n -> h[norm(n)]?.let { return row.getOrNull(it).orEmpty().trim() } }; return "" }
    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
    private fun parseIndex(s: String): MarketIndex? { val u = s.uppercase(Locale.ROOT); return when { "SENSEX" in u || "BSE" in u -> MarketIndex.SENSEX; "BANKNIFTY" in u || "BANK NIFTY" in u -> null; "NIFTY" in u -> MarketIndex.NIFTY; else -> null } }
    private fun parseType(s: String) = Regex("(?:^|[^A-Z])(CE|PE)(?:$|[^A-Z])").find(s.uppercase(Locale.ROOT))?.groupValues?.get(1)
    private fun inferStrike(s: String, type: String): Double? = Regex("(\\d{4,6}(?:\\.\\d+)?)\\s*[-_ ]*$type\\b").find(s.uppercase(Locale.ROOT))?.groupValues?.get(1)?.toDoubleOrNull() ?: Regex("\\b(\\d{4,6}(?:\\.\\d+)?)\\b").findAll(s).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()
    private data class Inferred(val symbol: String, val expiry: LocalDate?)
    private fun dateInText(s: String): LocalDate? = Regex("\\d{4}-\\d{2}-\\d{2}").find(s)?.value?.let { parseDate(it) }
    private fun parseTimestamp(raw: String): OffsetDateTime? {
        val s = raw.trim(); if (s.isBlank()) return null
        s.toLongOrNull()?.let { n -> if (n > 10_000_000_000L) return Instant.ofEpochMilli(n).atOffset(IST); if (n > 1_000_000_000L) return Instant.ofEpochSecond(n).atOffset(IST) }
        s.toDoubleOrNull()?.let { n -> if (n in 20_000.0..80_000.0) { val whole = floor(n).toLong(); return LocalDate.of(1899,12,30).plusDays(whole).atStartOfDay().plusSeconds(((n-whole)*86400).toLong()).atOffset(IST) } }
        runCatching { return OffsetDateTime.parse(s) }; runCatching { return ZonedDateTime.parse(s).toOffsetDateTime() }; runCatching { return Instant.parse(s).atOffset(IST) }
        DATE_TIMES.forEach { p -> try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)).atOffset(IST) } catch (_: DateTimeParseException) {} }
        return parseDate(s)?.atStartOfDay()?.atOffset(IST)
    }
    private fun parseDate(raw: String): LocalDate? { val s = raw.trim(); if (s.isBlank()) return null; runCatching { return LocalDate.parse(s) }; DATES.forEach { p -> try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(p, Locale.ENGLISH)) } catch (_: DateTimeParseException) {} }; return null }
    private fun csv(line: String): List<String> { val out = mutableListOf<String>(); val cur = StringBuilder(); var quoted = false; var i = 0; while (i < line.length) { val ch = line[i]; when { ch == '"' && quoted && i + 1 < line.length && line[i+1] == '"' -> { cur.append('"'); i++ }; ch == '"' -> quoted = !quoted; ch == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }; else -> cur.append(ch) }; i++ }; out += cur.toString(); return out }
    private fun columnIndex(ref: String): Int { var n = 0; var count = 0; for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1); count++ }; return if (count == 0) 0 else n - 1 }
    private fun sax() = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    private fun tag(local: String?, q: String?) = local?.takeIf { it.isNotEmpty() } ?: q.orEmpty()
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun close() = Unit
    }

    companion object {
        private val IST = ZoneOffset.ofHoursMinutes(5, 30)
        private const val MAGIC = "VARDHANI_CORPUS_CHUNK_V2"
        private const val EXT = "vcc"
        private const val BUFFER = 64 * 1024
        private const val FLUSH_ROWS = 20_000
        private const val MAX_CHUNK_ROWS = 100_000
        private const val MAX_ROWS = 20_000_000L
        private const val PROGRESS_EVERY = 25_000L
        private const val MAX_ZIP_ENTRIES = 20_000
        private const val MAX_DEPTH = 3
        private const val MAX_SPILL = 2L * 1024 * 1024 * 1024
        private const val MAX_SHEETS = 128
        private const val MAX_SHARED_STRINGS = 500_000
        private const val TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000
        private val DATE_TIMES = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "d-MMM-yyyy HH:mm:ss", "d-MMM-yyyy HH:mm", "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm")
        private val DATES = listOf("dd-MM-yyyy", "dd/MM/yyyy", "d-MMM-yyyy", "d MMM yyyy", "d MMM yy", "MM/dd/yyyy", "M/d/yyyy")
    }
}
