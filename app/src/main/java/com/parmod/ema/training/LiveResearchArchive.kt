package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.BuildConfig
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Append-only live research archive designed to survive normal APK upgrades and be
 * portable across uninstall/signature/device changes via explicit ZIP export/import.
 *
 * Raw transport is intentionally sampled to one row/second/instrument. This preserves
 * option/spot/D30/OI/volume microstructure for future feature revisions without turning
 * a phone into an unbounded tick recorder. Completed 1m bars are stored separately.
 */
object LiveResearchArchive {
    data class Summary(
        val sessions: Int = 0,
        val files: Int = 0,
        val bytes: Long = 0L,
        val tickRows: Long = 0L,
        val minuteRows: Long = 0L,
        val importedArchives: Int = 0,
        val schemaVersion: Int = ARCHIVE_SCHEMA,
        val featureSchemaVersion: Int = NumericalMetaBrain.FEATURE_SCHEMA_VERSION,
    )

    data class DepthRow(
        val bidPrice: Double,
        val bidQty: Long,
        val askPrice: Double,
        val askQty: Long,
    )

    private lateinit var appContext: Context
    private var initialized = false
    private val lastTickWrite = HashMap<String, Long>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val dayFormat = DateTimeFormatter.ISO_LOCAL_DATE

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        root().mkdirs()
        initialized = true
    }

    @Synchronized
    fun captureTick(
        index: MarketIndex,
        instrumentKey: String,
        kind: String,
        timestamp: Long,
        ltp: Double?,
        oi: Long?,
        volume: Long?,
        bid: Double?,
        ask: Double?,
        totalBuyQty: Long?,
        totalSellQty: Long?,
        depth: List<DepthRow>,
    ) {
        if (!initialized || instrumentKey.isBlank() || timestamp <= 0L) return
        val sampleKey = "${index.name}|$instrumentKey"
        val last = lastTickWrite[sampleKey] ?: 0L
        if (timestamp - last < TICK_SAMPLE_MS) return
        lastTickWrite[sampleKey] = timestamp
        val row = JSONObject()
            .put("schema", "vardhani-live-tick-v$ARCHIVE_SCHEMA")
            .put("feature_schema", NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("market", index.name)
            .put("kind", kind)
            .put("instrument_key", instrumentKey)
            .put("timestamp", timestamp)
            .put("ltp", ltp ?: JSONObject.NULL)
            .put("oi", oi ?: JSONObject.NULL)
            .put("volume", volume ?: JSONObject.NULL)
            .put("bid", bid ?: JSONObject.NULL)
            .put("ask", ask ?: JSONObject.NULL)
            .put("tbq", totalBuyQty ?: JSONObject.NULL)
            .put("tsq", totalSellQty ?: JSONObject.NULL)
            .put("depth_levels", depth.size)
        if (depth.isNotEmpty()) {
            val arr = JSONArray()
            depth.take(MAX_ARCHIVED_DEPTH).forEach { d ->
                arr.put(JSONArray().put(d.bidPrice).put(d.bidQty).put(d.askPrice).put(d.askQty))
            }
            row.put("depth", arr)
        }
        append(sessionFile(timestamp, "ticks.ndjson"), row.toString())
        increment(KEY_TICK_ROWS)
    }

    @Synchronized
    fun captureMinuteBar(
        index: MarketIndex,
        timestamp: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        ticks: Long,
    ) {
        if (!initialized || timestamp <= 0L || close <= 0.0) return
        val row = JSONObject()
            .put("schema", "vardhani-live-minute-v$ARCHIVE_SCHEMA")
            .put("feature_schema", NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("market", index.name)
            .put("timestamp", timestamp)
            .put("open", open)
            .put("high", high)
            .put("low", low)
            .put("close", close)
            .put("ticks", ticks)
        append(sessionFile(timestamp, "underlying_1m.ndjson"), row.toString())
        increment(KEY_MINUTE_ROWS)
    }

    @Synchronized
    fun summary(): Summary {
        if (!initialized) return Summary()
        val sessionDirs = sessionsRoot().listFiles()?.filter { it.isDirectory }.orEmpty()
        val allFiles = sessionDirs.flatMap { it.walkTopDown().filter(File::isFile).toList() }
        val prefs = prefs()
        return Summary(
            sessions = sessionDirs.size,
            files = allFiles.size,
            bytes = allFiles.sumOf { it.length() },
            tickRows = prefs.getLong(KEY_TICK_ROWS, 0L),
            minuteRows = prefs.getLong(KEY_MINUTE_ROWS, 0L),
            importedArchives = prefs.getInt(KEY_IMPORTED, 0),
        )
    }

    /** Portable backup. Caller owns the SAF/MediaStore OutputStream. */
    @Synchronized
    fun exportZip(output: OutputStream) {
        check(initialized) { "LiveResearchArchive is not initialized" }
        ZipOutputStream(BufferedOutputStream(output, BUFFER)).use { zip ->
            val manifest = JSONObject()
                .put("schema", "vardhani-live-archive-v$ARCHIVE_SCHEMA")
                .put("feature_schema", NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
                .put("created_at", System.currentTimeMillis())
                .put("app_version", BuildConfig.VERSION_NAME)
                .put("summary", JSONObject().apply {
                    val s = summary()
                    put("sessions", s.sessions); put("files", s.files); put("bytes", s.bytes)
                    put("tick_rows", s.tickRows); put("minute_rows", s.minuteRows)
                })
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()
            val base = sessionsRoot()
            if (base.exists()) {
                base.walkTopDown().filter(File::isFile).forEach { file ->
                    val relative = file.relativeTo(base).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("sessions/$relative"))
                    FileInputStream(file).use { it.copyTo(zip, BUFFER) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Imports a portable archive without overwriting an existing session file. Identical
     * files are skipped by SHA-256; conflicting files receive an imported suffix, so no
     * older session can be silently destroyed.
     */
    @Synchronized
    fun importZip(input: InputStream): Int {
        check(initialized) { "LiveResearchArchive is not initialized" }
        var imported = 0
        val temp = File(root(), "import-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_IMPORT_ENTRIES) error("Research archive contains too many files")
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val normalized = entry.name.replace('\\', '/')
                    if (!normalized.startsWith("sessions/") || ".." in normalized.split('/')) {
                        zip.closeEntry(); continue
                    }
                    val relative = normalized.removePrefix("sessions/")
                    if (relative.isBlank()) { zip.closeEntry(); continue }
                    val staged = File(temp, relative)
                    staged.parentFile?.mkdirs()
                    FileOutputStream(staged).use { out -> copyLimited(zip, out, MAX_IMPORTED_FILE_BYTES) }
                    val target = File(sessionsRoot(), relative)
                    target.parentFile?.mkdirs()
                    when {
                        !target.exists() -> { staged.copyTo(target); imported++ }
                        sha256(target) == sha256(staged) -> Unit
                        else -> {
                            val alternate = File(target.parentFile, target.nameWithoutExtension + ".imported-${sha256(staged).take(10)}." + target.extension)
                            if (!alternate.exists()) { staged.copyTo(alternate); imported++ }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            temp.deleteRecursively()
        }
        if (imported > 0) prefs().edit().putInt(KEY_IMPORTED, prefs().getInt(KEY_IMPORTED, 0) + 1).apply()
        return imported
    }

    private fun append(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun sessionFile(timestamp: Long, name: String): File {
        val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().format(dayFormat)
        return File(File(sessionsRoot(), day), name)
    }

    private fun root(): File = File(appContext.filesDir, "vardhani_live_research_archive/v$ARCHIVE_SCHEMA")
    private fun sessionsRoot(): File = File(root(), "sessions").apply { mkdirs() }
    private fun prefs() = appContext.getSharedPreferences("vardhani_live_archive_stats", 0)
    private fun increment(key: String) = prefs().edit().putLong(key, prefs().getLong(key, 0L) + 1L).apply()

    private fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            total += n
            if (total > limit) error("Imported research file exceeds safety limit")
            output.write(buffer, 0, n)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val b = ByteArray(BUFFER)
            while (true) {
                val n = input.read(b)
                if (n <= 0) break
                md.update(b, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val ARCHIVE_SCHEMA = 1
    private const val TICK_SAMPLE_MS = 1_000L
    private const val MAX_ARCHIVED_DEPTH = 30
    private const val MAX_IMPORT_ENTRIES = 10_000
    private const val MAX_IMPORTED_FILE_BYTES = 2L * 1024 * 1024 * 1024
    private const val BUFFER = 64 * 1024
    private const val KEY_TICK_ROWS = "tick_rows"
    private const val KEY_MINUTE_ROWS = "minute_rows"
    private const val KEY_IMPORTED = "imported_archives"
}
