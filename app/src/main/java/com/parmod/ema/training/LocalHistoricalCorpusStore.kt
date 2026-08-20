package com.parmod.ema.training

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
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
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.floor

/**
 * Imports user-owned historical option corpus into VARDHANI app-private storage.
 * Supported input: CSV, XLSX, JSON and ZIP containing those formats.
 * Every accepted row is normalized to one option contract + OHLCV/OI candle.
 */
class LocalHistoricalCorpusStore(private val context: Context) {
    private val root = File(context.filesDir, "vardhani_local_corpus/v1")
    private val seriesDir = File(root, "series")
    private val statsFile = File(root, "stats.properties")

    data class ImportProgress(val completedFiles: Int, val totalFiles: Int, val message: String)

    private data class MutableStats(
        var filesImported: Int = 0,
        var supportedFiles: Int = 0,
        var rowsRead: Long = 0,
        var rowsAccepted: Long = 0,
        var rowsRejected: Long = 0,
        var duplicatesRemoved: Long = 0,
        var inferredLotSizeContracts: Int = 0,
        val warnings: MutableList<String> = mutableListOf(),
        val errors: MutableList<String> = mutableListOf(),
    )

    private data class ParsedContractKey(
        val index: MarketIndex,
        val optionType: String,
        val strike: Double,
        val expiry: LocalDate,
        val lotSize: Int,
        val symbol: String,
        val lotWasInferred: Boolean,
    )

    private class ParsedBatch {
        val candles = linkedMapOf<ParsedContractKey, MutableList<UpstoxPlusHistoricalClient.Candle>>()
    }

    init { seriesDir.mkdirs() }

    fun importUris(
        uris: List<Uri>,
        onProgress: (ImportProgress) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): LocalCorpusSummary {
        require(uris.isNotEmpty()) { "Select at least one corpus file" }
        val batchStats = MutableStats()
        val parsed = ParsedBatch()
        uris.forEachIndexed { index, uri ->
            if (shouldCancel()) error("Corpus import cancelled")
            val name = displayName(uri) ?: "import_${index + 1}"
            onProgress(ImportProgress(index, uris.size, "Importing $name"))
            batchStats.filesImported++
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    parseNamedStream(name, input, parsed, batchStats, 0, shouldCancel)
                } ?: error("Unable to open $name")
            }.onFailure { error ->
                batchStats.errors += "$name: ${(error.message ?: error::class.java.simpleName).take(180)}"
            }
        }
        onProgress(ImportProgress(uris.size, uris.size, "Normalizing and deduplicating imported contracts"))
        persistParsed(parsed, batchStats, shouldCancel)
        updateCumulativeStats(batchStats)
        return summary(batchStats.warnings, batchStats.errors)
    }

    fun loadSeries(index: MarketIndex? = null): List<HistoricalOptionSeries> =
        seriesDir.listFiles { file -> file.isFile && file.extension == SERIES_EXTENSION }
            ?.mapNotNull { runCatching { readSeries(it) }.getOrNull() }
            ?.filter { index == null || it.index == index }
            ?.sortedWith(compareBy<HistoricalOptionSeries> { it.expiry }.thenBy { it.strike }.thenBy { it.optionType })
            .orEmpty()

    fun summary(extraWarnings: List<String> = emptyList(), extraErrors: List<String> = emptyList()): LocalCorpusSummary {
        val series = loadSeries()
        val p = readStats()
        val dates = series.flatMap { s -> s.candles.map { it.time.toLocalDate() } }
        return LocalCorpusSummary(
            filesImported = p.getProperty("filesImported")?.toIntOrNull() ?: 0,
            supportedFiles = p.getProperty("supportedFiles")?.toIntOrNull() ?: 0,
            rowsRead = p.getProperty("rowsRead")?.toLongOrNull() ?: 0L,
            rowsAccepted = series.sumOf { it.candles.size }.toLong(),
            rowsRejected = p.getProperty("rowsRejected")?.toLongOrNull() ?: 0L,
            duplicatesRemoved = p.getProperty("duplicatesRemoved")?.toLongOrNull() ?: 0L,
            optionContracts = series.size,
            niftyContracts = series.count { it.index == MarketIndex.NIFTY },
            sensexContracts = series.count { it.index == MarketIndex.SENSEX },
            ceContracts = series.count { it.optionType == "CE" },
            peContracts = series.count { it.optionType == "PE" },
            inferredLotSizeContracts = p.getProperty("inferredLotSizeContracts")?.toIntOrNull() ?: 0,
            fromDate = dates.minOrNull(),
            toDate = dates.maxOrNull(),
            warnings = (p.getProperty("warnings").orEmpty().split("\n").filter(String::isNotBlank) + extraWarnings).takeLast(12),
            errors = (p.getProperty("errors").orEmpty().split("\n").filter(String::isNotBlank) + extraErrors).takeLast(12),
        )
    }

    fun clear() {
        root.deleteRecursively()
        seriesDir.mkdirs()
    }

    private fun parseNamedStream(
        name: String,
        input: InputStream,
        parsed: ParsedBatch,
        stats: MutableStats,
        depth: Int,
        shouldCancel: () -> Boolean,
    ) {
        if (depth > 2) error("Nested ZIP depth exceeds safety limit")
        when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "csv", "txt" -> { stats.supportedFiles++; parseCsv(name, input, parsed, stats, shouldCancel) }
            "xlsx" -> { stats.supportedFiles++; parseXlsx(name, readLimited(input, MAX_XLSX_BYTES), parsed, stats, shouldCancel) }
            "json" -> { stats.supportedFiles++; parseJson(name, readLimited(input, MAX_JSON_BYTES), parsed, stats, shouldCancel) }
            "zip" -> { stats.supportedFiles++; parseZip(name, input, parsed, stats, depth, shouldCancel) }
            else -> stats.warnings += "$name skipped: supported formats are CSV/XLSX/JSON/ZIP"
        }
    }

    private fun parseZip(
        archiveName: String,
        input: InputStream,
        parsed: ParsedBatch,
        stats: MutableStats,
        depth: Int,
        shouldCancel: () -> Boolean,
    ) {
        var entries = 0
        var totalExpanded = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                if (shouldCancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                if (entries > MAX_ZIP_ENTRIES) error("ZIP contains too many entries")
                val childName = "$archiveName/${entry.name.substringAfterLast('/')}"
                val bytes = readLimited(zip, MAX_ZIP_ENTRY_BYTES)
                totalExpanded += bytes.size
                if (totalExpanded > MAX_ZIP_EXPANDED_BYTES) error("ZIP expanded data exceeds safety limit")
                parseNamedStream(childName, ByteArrayInputStream(bytes), parsed, stats, depth + 1, shouldCancel)
                zip.closeEntry()
            }
        }
    }

    private fun parseCsv(
        name: String,
        input: InputStream,
        parsed: ParsedBatch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
    ) {
        val table = TableAccumulator(name, parsed, stats)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (shouldCancel()) error("Corpus import cancelled")
                val value = line ?: continue
                if (value.isBlank()) continue
                table.accept(parseCsvLine(value))
                if (stats.rowsRead > MAX_IMPORTED_ROWS) error("Imported row limit exceeded")
            }
        }
    }

    private fun parseJson(
        name: String,
        bytes: ByteArray,
        parsed: ParsedBatch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
    ) {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) return
        val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> when {
                root.opt("candles") is JSONArray -> root.getJSONArray("candles")
                root.opt("data") is JSONArray -> root.getJSONArray("data")
                root.optJSONObject("data")?.opt("candles") is JSONArray -> root.getJSONObject("data").getJSONArray("candles")
                else -> JSONArray().put(root)
            }
            else -> JSONArray()
        }
        if (array.length() == 0) return
        val table = TableAccumulator(name, parsed, stats)
        val first = array.opt(0)
        if (first is JSONObject) {
            val headers = buildList { val keys = first.keys(); while (keys.hasNext()) add(keys.next()) }
            table.accept(headers)
            for (i in 0 until array.length()) {
                if (shouldCancel()) error("Corpus import cancelled")
                val obj = array.optJSONObject(i) ?: continue
                table.accept(headers.map { obj.opt(it)?.toString().orEmpty() })
            }
        } else if (first is JSONArray) {
            table.accept(listOf("timestamp", "open", "high", "low", "close", "volume", "oi"))
            for (i in 0 until array.length()) {
                if (shouldCancel()) error("Corpus import cancelled")
                val row = array.optJSONArray(i) ?: continue
                table.accept((0 until row.length()).map { row.opt(it)?.toString().orEmpty() })
            }
        }
    }

    private fun parseXlsx(
        name: String,
        bytes: ByteArray,
        parsed: ParsedBatch,
        stats: MutableStats,
        shouldCancel: () -> Boolean,
    ) {
        val sharedStrings = loadSharedStrings(bytes)
        var sheets = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                if (shouldCancel()) error("Corpus import cancelled")
                val entry = zip.nextEntry ?: break
                val path = entry.name.lowercase(Locale.ROOT)
                if (!entry.isDirectory && path.startsWith("xl/worksheets/sheet") && path.endsWith(".xml")) {
                    sheets++
                    if (sheets > MAX_XLSX_SHEETS) error("XLSX contains too many sheets")
                    val table = TableAccumulator("$name#${entry.name.substringAfterLast('/')}", parsed, stats)
                    parseXlsxSheet(zip, sharedStrings, table, shouldCancel)
                }
                zip.closeEntry()
            }
        }
        if (sheets == 0) stats.warnings += "$name: no worksheet data found"
    }

    private fun loadSharedStrings(bytes: ByteArray): List<String> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name.equals("xl/sharedStrings.xml", true)) {
                    val result = mutableListOf<String>()
                    val current = StringBuilder()
                    var inSi = false
                    namespaceAwareSaxFactory().newSAXParser().parse(zip, object : DefaultHandler() {
                        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                            if (tag(localName, qName) == "si") { inSi = true; current.setLength(0) }
                        }
                        override fun characters(ch: CharArray, start: Int, length: Int) {
                            if (inSi) current.append(ch, start, length)
                        }
                        override fun endElement(uri: String?, localName: String?, qName: String?) {
                            if (tag(localName, qName) == "si") { result += current.toString(); inSi = false }
                        }
                    })
                    return result
                }
                zip.closeEntry()
            }
        }
        return emptyList()
    }

    private fun parseXlsxSheet(
        input: InputStream,
        sharedStrings: List<String>,
        table: TableAccumulator,
        shouldCancel: () -> Boolean,
    ) {
        var row = sortedMapOf<Int, String>()
        var cellColumn = 0
        var cellType = ""
        var value = StringBuilder()
        var capture = false
        namespaceAwareSaxFactory().newSAXParser().parse(input, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tag(localName, qName)) {
                    "row" -> row = sortedMapOf()
                    "c" -> {
                        cellColumn = columnIndex(attributes?.getValue("r").orEmpty())
                        cellType = attributes?.getValue("t").orEmpty()
                        value = StringBuilder()
                    }
                    "v", "t" -> capture = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (capture) value.append(ch, start, length)
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tag(localName, qName)) {
                    "v", "t" -> capture = false
                    "c" -> {
                        val raw = value.toString()
                        val decoded = if (cellType == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                        row[cellColumn] = decoded
                    }
                    "row" -> {
                        if (shouldCancel()) error("Corpus import cancelled")
                        val maxCol = row.keys.maxOrNull() ?: -1
                        if (maxCol >= 0) table.accept((0..maxCol).map { row[it].orEmpty() })
                    }
                }
            }
        })
    }

    private inner class TableAccumulator(
        private val sourceName: String,
        private val parsed: ParsedBatch,
        private val stats: MutableStats,
    ) {
        private var headers: Map<String, Int>? = null
        private val inferred = inferMetadata(sourceName)
        private var nonOptionWarningEmitted = false

        fun accept(row: List<String>) {
            if (row.all(String::isBlank)) return
            val h = headers
            if (h == null) {
                headers = row.mapIndexedNotNull { index, text -> normalizeHeader(text).takeIf(String::isNotBlank)?.let { it to index } }.toMap()
                return
            }
            stats.rowsRead++
            val map = h
            val symbol = value(row, map, "tradingsymbol", "symbol", "instrument", "name").ifBlank { inferred.symbol }
            val index = parseIndex(value(row, map, "index", "underlying", "market", "indexname") + " $symbol $sourceName") ?: run {
                stats.rowsRejected++
                if (!nonOptionWarningEmitted) { stats.warnings += "$sourceName: rows without NIFTY/SENSEX option identity were skipped"; nonOptionWarningEmitted = true }
                return
            }
            val optionType = parseOptionType(value(row, map, "optiontype", "instrumenttype", "type") + " $symbol $sourceName") ?: run {
                stats.rowsRejected++
                if (!nonOptionWarningEmitted) { stats.warnings += "$sourceName: underlying-only rows are not used for option-premium AI labels; CE/PE rows are required"; nonOptionWarningEmitted = true }
                return
            }
            val strike = value(row, map, "strike", "strikeprice").toDoubleOrNull()
                ?: inferStrike("$symbol $sourceName", optionType)
                ?: run { stats.rowsRejected++; return }
            val timestampText = value(row, map, "timestamp", "datetime", "dateandtime", "date_time").ifBlank {
                val date = value(row, map, "date", "tradingdate")
                val time = value(row, map, "time", "tradingtime")
                listOf(date, time).filter(String::isNotBlank).joinToString(" ")
            }
            val timestamp = parseTimestamp(timestampText) ?: run { stats.rowsRejected++; return }
            val open = value(row, map, "open", "o").toDoubleOrNull()
            val high = value(row, map, "high", "h").toDoubleOrNull()
            val low = value(row, map, "low", "l").toDoubleOrNull()
            val close = value(row, map, "close", "c", "ltp").toDoubleOrNull()
            if (open == null || high == null || low == null || close == null || minOf(open, high, low, close) <= 0.0) {
                stats.rowsRejected++
                return
            }
            val expiryRaw = value(row, map, "expiry", "expirydate")
            val expiry = parseDateFlexible(expiryRaw) ?: inferred.expiry ?: timestamp.toLocalDate()
            val lotRaw = value(row, map, "lotsize", "lot", "quantityperlot", "minimumlot").toIntOrNull()
            val lotSize = (lotRaw ?: defaultLot(index)).coerceAtLeast(1)
            val key = ParsedContractKey(index, optionType, strike, expiry, lotSize, symbol.ifBlank { inferred.symbol }, lotRaw == null)
            val volume = value(row, map, "volume", "vol", "v").toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
            val oi = value(row, map, "oi", "openinterest", "open_interest").toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
            parsed.candles.getOrPut(key) { mutableListOf() } += UpstoxPlusHistoricalClient.Candle(timestamp, open, high, low, close, volume, oi)
            stats.rowsAccepted++
        }
    }

    private data class InferredMetadata(val symbol: String, val expiry: LocalDate?)

    private fun inferMetadata(name: String): InferredMetadata = InferredMetadata(
        symbol = name.substringAfterLast('/').substringBeforeLast('.'),
        expiry = findDateInText(name),
    )

    private fun persistParsed(parsed: ParsedBatch, stats: MutableStats, shouldCancel: () -> Boolean) {
        parsed.candles.forEach { (key, incoming) ->
            if (shouldCancel()) error("Corpus import cancelled")
            if (incoming.isEmpty()) return@forEach
            val file = seriesFile(key.index, key.expiry, key.strike, key.optionType)
            val existing = if (file.isFile) runCatching { readSeries(file) }.getOrNull()?.candles.orEmpty() else emptyList()
            val merged = (existing + incoming)
                .filter { it.close > 0.0 }
                .sortedBy { it.time.toInstant().toEpochMilli() }
                .distinctBy { it.time.toInstant().toEpochMilli() }
            stats.duplicatesRemoved += existing.size + incoming.size - merged.size
            if (key.lotWasInferred) stats.inferredLotSizeContracts++
            writeSeries(
                file,
                HistoricalOptionSeries(
                    index = key.index,
                    optionType = key.optionType,
                    strike = key.strike,
                    expiry = key.expiry,
                    lotSize = key.lotSize,
                    symbol = key.symbol,
                    source = "LOCAL_IMPORT",
                    candles = merged,
                ),
            )
        }
        if (stats.inferredLotSizeContracts > 0) {
            stats.warnings += "${stats.inferredLotSizeContracts} contract(s) lacked lot_size; current index lot fallback was used. Cost-normalized metrics for those contracts are approximate."
        }
    }

    private fun writeSeries(file: File, series: HistoricalOptionSeries) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { out ->
            out.writeUTF(SERIES_MAGIC)
            out.writeUTF(series.index.name)
            out.writeUTF(series.optionType)
            out.writeDouble(series.strike)
            out.writeUTF(series.expiry.toString())
            out.writeInt(series.lotSize)
            out.writeUTF(series.symbol.take(240))
            out.writeUTF(series.source)
            out.writeInt(series.candles.size)
            series.candles.forEach { c ->
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
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Could not finalize imported corpus contract" }
    }

    private fun readSeries(file: File): HistoricalOptionSeries = DataInputStream(FileInputStream(file).buffered()).use { input ->
        require(input.readUTF() == SERIES_MAGIC) { "Unsupported corpus file version" }
        val index = MarketIndex.valueOf(input.readUTF())
        val optionType = input.readUTF()
        val strike = input.readDouble()
        val expiry = LocalDate.parse(input.readUTF())
        val lotSize = input.readInt()
        val symbol = input.readUTF()
        val source = input.readUTF()
        val count = input.readInt()
        require(count in 0..MAX_SERIES_ROWS) { "Imported corpus contract exceeds row limit" }
        val candles = ArrayList<UpstoxPlusHistoricalClient.Candle>(count)
        repeat(count) {
            val epochMs = input.readLong()
            val offset = ZoneOffset.ofTotalSeconds(input.readInt().coerceIn(-18 * 3600, 18 * 3600))
            candles += UpstoxPlusHistoricalClient.Candle(
                time = Instant.ofEpochMilli(epochMs).atOffset(offset),
                open = input.readDouble(),
                high = input.readDouble(),
                low = input.readDouble(),
                close = input.readDouble(),
                volume = input.readLong(),
                openInterest = input.readLong(),
            )
        }
        HistoricalOptionSeries(index, optionType, strike, expiry, lotSize, symbol, source, candles)
    }

    private fun updateCumulativeStats(batch: MutableStats) {
        val p = readStats()
        fun long(name: String) = p.getProperty(name)?.toLongOrNull() ?: 0L
        fun int(name: String) = p.getProperty(name)?.toIntOrNull() ?: 0
        p.setProperty("filesImported", (int("filesImported") + batch.filesImported).toString())
        p.setProperty("supportedFiles", (int("supportedFiles") + batch.supportedFiles).toString())
        p.setProperty("rowsRead", (long("rowsRead") + batch.rowsRead).toString())
        p.setProperty("rowsRejected", (long("rowsRejected") + batch.rowsRejected).toString())
        p.setProperty("duplicatesRemoved", (long("duplicatesRemoved") + batch.duplicatesRemoved).toString())
        p.setProperty("inferredLotSizeContracts", (int("inferredLotSizeContracts") + batch.inferredLotSizeContracts).toString())
        p.setProperty("warnings", batch.warnings.takeLast(12).joinToString("\n"))
        p.setProperty("errors", batch.errors.takeLast(12).joinToString("\n"))
        statsFile.parentFile?.mkdirs()
        FileOutputStream(statsFile).use { p.store(it, "VARDHANI local historical corpus") }
    }

    private fun readStats(): Properties = Properties().apply {
        if (statsFile.isFile) runCatching { FileInputStream(statsFile).use { input -> load(input) } }
    }

    private fun seriesFile(index: MarketIndex, expiry: LocalDate, strike: Double, optionType: String): File {
        val key = "${index.name}|$expiry|$strike|$optionType"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(seriesDir, "$digest.$SERIES_EXTENSION")
    }

    private fun displayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    private fun value(row: List<String>, headers: Map<String, Int>, vararg aliases: String): String {
        aliases.forEach { alias ->
            val index = headers[normalizeHeader(alias)] ?: return@forEach
            return row.getOrNull(index).orEmpty().trim()
        }
        return ""
    }

    private fun normalizeHeader(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    private fun parseIndex(text: String): MarketIndex? {
        val u = text.uppercase(Locale.ROOT)
        return when {
            "SENSEX" in u || "BSE" in u -> MarketIndex.SENSEX
            "BANKNIFTY" in u || "BANK NIFTY" in u -> null
            "NIFTY" in u -> MarketIndex.NIFTY
            else -> null
        }
    }

    private fun parseOptionType(text: String): String? = Regex("(?:^|[^A-Z])(CE|PE)(?:$|[^A-Z])").find(text.uppercase(Locale.ROOT))?.groupValues?.get(1)

    private fun inferStrike(text: String, optionType: String): Double? {
        val u = text.uppercase(Locale.ROOT)
        val regex = Regex("(\\d{4,6}(?:\\.\\d+)?)\\s*[-_ ]*$optionType\\b")
        return regex.find(u)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("\\b(\\d{4,6}(?:\\.\\d+)?)\\b").findAll(u).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()
    }

    private fun parseTimestamp(raw: String): OffsetDateTime? {
        val s = raw.trim()
        if (s.isBlank()) return null
        s.toLongOrNull()?.let { n ->
            if (n > 10_000_000_000L) return Instant.ofEpochMilli(n).atOffset(IST)
            if (n > 1_000_000_000L) return Instant.ofEpochSecond(n).atOffset(IST)
        }
        s.toDoubleOrNull()?.let { serial ->
            if (serial in 20_000.0..80_000.0) {
                val whole = floor(serial).toLong()
                val seconds = ((serial - whole) * 86_400.0).toLong()
                return LocalDate.of(1899, 12, 30).plusDays(whole).atStartOfDay().plusSeconds(seconds).atOffset(IST)
            }
        }
        runCatching { return OffsetDateTime.parse(s) }
        runCatching { return ZonedDateTime.parse(s).toOffsetDateTime() }
        runCatching { return Instant.parse(s).atOffset(IST) }
        for (pattern in DATE_TIME_PATTERNS) {
            try { return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).atOffset(IST) } catch (_: DateTimeParseException) { }
        }
        parseDateFlexible(s)?.let { return it.atStartOfDay().atOffset(IST) }
        return null
    }

    private fun parseDateFlexible(raw: String): LocalDate? {
        val s = raw.trim()
        if (s.isBlank()) return null
        runCatching { return LocalDate.parse(s) }
        for (pattern in DATE_PATTERNS) {
            try { return LocalDate.parse(s, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) } catch (_: DateTimeParseException) { }
        }
        return null
    }

    private fun findDateInText(text: String): LocalDate? {
        Regex("\\d{4}-\\d{2}-\\d{2}").find(text)?.value?.let(::parseDateFlexible)?.let { return it }
        Regex("\\d{1,2}[-_/ ](?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[-_/ ]\\d{2,4}", RegexOption.IGNORE_CASE)
            .find(text)?.value?.replace('_', ' ')?.replace('/', ' ')?.let { candidate ->
                for (p in listOf("d MMM yy", "d MMM yyyy")) {
                    try { return LocalDate.parse(candidate.replace('-', ' '), DateTimeFormatter.ofPattern(p, Locale.ENGLISH)) } catch (_: Exception) { }
                }
            }
        return null
    }

    private fun defaultLot(index: MarketIndex): Int = if (index == MarketIndex.NIFTY) 65 else 20

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += current.toString(); current.setLength(0) }
                else -> current.append(ch)
            }
            i++
        }
        out += current.toString()
        return out
    }

    private fun columnIndex(ref: String): Int {
        var result = 0
        var chars = 0
        for (ch in ref) {
            if (!ch.isLetter()) break
            result = result * 26 + (ch.uppercaseChar() - 'A' + 1)
            chars++
        }
        return if (chars == 0) 0 else result - 1
    }

    private fun namespaceAwareSaxFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    private fun tag(localName: String?, qName: String?): String = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty()

    private fun readLimited(input: InputStream, maxBytes: Long): ByteArray {
        val buffer = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) error("Input exceeds ${maxBytes / (1024 * 1024)} MB safety limit")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private val IST: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)
        private const val SERIES_MAGIC = "VARDHANI_CORPUS_V1"
        private const val SERIES_EXTENSION = "vcs"
        private const val MAX_ZIP_ENTRIES = 500
        private const val MAX_ZIP_ENTRY_BYTES = 128L * 1024 * 1024
        private const val MAX_ZIP_EXPANDED_BYTES = 1024L * 1024 * 1024
        private const val MAX_XLSX_BYTES = 256L * 1024 * 1024
        private const val MAX_JSON_BYTES = 256L * 1024 * 1024
        private const val MAX_IMPORTED_ROWS = 5_000_000L
        private const val MAX_SERIES_ROWS = 2_000_000
        private const val MAX_XLSX_SHEETS = 64
        private val DATE_TIME_PATTERNS = listOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm",
            "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "d-MMM-yyyy HH:mm:ss", "d-MMM-yyyy HH:mm",
            "M/d/yyyy H:mm:ss", "M/d/yyyy H:mm",
        )
        private val DATE_PATTERNS = listOf(
            "dd-MM-yyyy", "dd/MM/yyyy", "d-MMM-yyyy", "d MMM yyyy", "d MMM yy", "MM/dd/yyyy", "M/d/yyyy",
        )
    }
}
