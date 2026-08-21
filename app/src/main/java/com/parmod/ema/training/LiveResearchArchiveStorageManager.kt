package com.parmod.ema.training

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Quality-first storage maintenance for the persistent live research archive. */
class LiveResearchArchiveStorageManager(context: Context) {
    data class Policy(
        val quotaBytes: Long = DEFAULT_QUOTA_BYTES,
        val minimumFreeBytes: Long = DEFAULT_MIN_FREE_BYTES,
        val rawRetentionDays: Int = DEFAULT_RAW_RETENTION_DAYS,
    )

    data class Status(
        val policy: Policy,
        val archiveBytes: Long,
        val freeBytes: Long,
        val rawCaptureEnabled: Boolean,
        val compactedSessions: Int,
        val compressedRawFiles: Int,
        val prunedRawFiles: Int,
        val protectedHighValueSessions: Int,
        val compactTrainingRecords: Long,
        val lastMaintenanceAt: Long,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val store = LiveArchiveTrainingStore(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, 0)
    private val zone = ZoneId.of("Asia/Kolkata")

    fun policy(): Policy = Policy(
        quotaBytes = prefs.getLong(KEY_QUOTA, DEFAULT_QUOTA_BYTES).coerceIn(MIN_QUOTA_BYTES, MAX_QUOTA_BYTES),
        minimumFreeBytes = prefs.getLong(KEY_MIN_FREE, DEFAULT_MIN_FREE_BYTES).coerceIn(MIN_FREE_FLOOR, MAX_MIN_FREE_BYTES),
        rawRetentionDays = prefs.getInt(KEY_RETENTION, DEFAULT_RAW_RETENTION_DAYS).coerceIn(3, 60),
    )

    fun setQuotaGiB(gib: Int): Policy {
        require(gib in setOf(1, 2, 4, 8))
        prefs.edit().putLong(KEY_QUOTA, gib.toLong() * GIB).apply()
        return policy()
    }

    fun setRawRetentionDays(days: Int): Policy {
        require(days in 3..60)
        prefs.edit().putInt(KEY_RETENTION, days).apply()
        return policy()
    }

    fun rawCaptureAllowed(): Boolean {
        val p = policy()
        val root = archiveRoot()
        if (root.usableSpace in 1 until p.minimumFreeBytes) {
            prefs.edit().putBoolean(KEY_RAW_ENABLED, false).putString(KEY_MESSAGE, "STORAGE PROTECTION ACTIVE · low free space").apply()
            return false
        }
        return prefs.getBoolean(KEY_RAW_ENABLED, true)
    }

    @Synchronized
    fun maintain(allowPrune: Boolean = true): Status {
        val p = policy()
        val sessions = store.sessionsRoot().listFiles()?.filter(File::isDirectory)?.sortedBy { it.name }.orEmpty()
        val today = LocalDate.now(zone)
        var compacted = 0
        var compressed = 0
        var pruned = 0
        var highValue = 0
        var compactRecords = 0L

        sessions.forEach { session ->
            val sessionDate = runCatching { LocalDate.parse(session.name) }.getOrNull() ?: return@forEach
            if (!sessionDate.isBefore(today)) return@forEach // Never compact an active session.
            val compact = ensureTrainingCompact(session)
            if (compact.created) compacted++
            if (compact.valid) {
                compactRecords += compact.records
                if (compact.highValue) highValue++
                compressed += compressRawTicksLosslessly(session)
            }
        }

        var bytes = archiveBytes()
        var free = archiveRoot().usableSpace
        val pressure = bytes > p.quotaBytes || (free in 1 until p.minimumFreeBytes)
        if (pressure && allowPrune) {
            val cutoff = today.minusDays(p.rawRetentionDays.toLong())
            sessions.forEach { session ->
                if (bytes <= p.quotaBytes && (free <= 0L || free >= p.minimumFreeBytes)) return@forEach
                val date = runCatching { LocalDate.parse(session.name) }.getOrNull() ?: return@forEach
                if (!date.isBefore(cutoff)) return@forEach
                val manifest = readManifest(session) ?: return@forEach
                if (!manifest.optBoolean("verified", false) || manifest.optBoolean("high_value", false)) return@forEach
                if (!File(session, LiveArchiveTrainingStore.COMPACT_FILE).isFile) return@forEach
                session.listFiles()?.filter { it.isFile && it.name.startsWith("ticks") && (it.name.endsWith(".ndjson.gz") || it.name.endsWith(".ndjson")) }?.forEach { raw ->
                    if (raw.delete()) { pruned++; prefs.edit().putLong(KEY_PRUNED, prefs.getLong(KEY_PRUNED, 0L) + 1L).apply() }
                }
                if (pruned > 0) updateManifestPruned(session)
                bytes = archiveBytes(); free = archiveRoot().usableSpace
            }
        }

        bytes = archiveBytes(); free = archiveRoot().usableSpace
        val rawEnabled = bytes <= p.quotaBytes && (free <= 0L || free >= p.minimumFreeBytes)
        val message = when {
            !rawEnabled && free in 1 until p.minimumFreeBytes -> "STORAGE PROTECTION ACTIVE · raw tick/D30 capture paused; compact labels/outcomes continue"
            !rawEnabled -> "STORAGE QUOTA ACTIVE · raw tick/D30 capture paused; compact labels/outcomes continue"
            pruned > 0 -> "Storage maintained · $pruned redundant old raw file(s) pruned after verified compaction"
            compacted > 0 || compressed > 0 -> "Storage maintained · $compacted session(s) compacted · $compressed raw file(s) losslessly compressed"
            else -> "Storage healthy · no pruning required"
        }
        val now = System.currentTimeMillis()
        prefs.edit().putBoolean(KEY_RAW_ENABLED, rawEnabled).putLong(KEY_LAST_MAINTENANCE, now).putString(KEY_MESSAGE, message).apply()
        return Status(p, bytes, free, rawEnabled, compacted, compressed, pruned, highValue, compactRecords, now, message)
    }

    fun status(): Status {
        val p = policy(); val root = archiveRoot()
        return Status(
            policy = p,
            archiveBytes = archiveBytes(),
            freeBytes = root.usableSpace,
            rawCaptureEnabled = rawCaptureAllowed(),
            compactedSessions = 0,
            compressedRawFiles = 0,
            prunedRawFiles = prefs.getLong(KEY_PRUNED, 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            protectedHighValueSessions = countHighValueSessions(),
            compactTrainingRecords = countCompactRecords(),
            lastMaintenanceAt = prefs.getLong(KEY_LAST_MAINTENANCE, 0L),
            message = prefs.getString(KEY_MESSAGE, "Storage policy ready") ?: "Storage policy ready",
        )
    }

    private data class CompactResult(val created: Boolean, val valid: Boolean, val records: Long, val highValue: Boolean)

    private fun ensureTrainingCompact(session: File): CompactResult {
        val existing = readManifest(session)
        val compactFile = File(session, LiveArchiveTrainingStore.COMPACT_FILE)
        if (existing?.optBoolean("verified", false) == true && compactFile.isFile && existing.optString("compact_sha256") == sha256(compactFile)) {
            return CompactResult(false, true, existing.optLong("records"), existing.optBoolean("high_value"))
        }

        val records = store.allCompletedForSession(session)
        if (records.isEmpty()) return CompactResult(false, false, 0L, false)
        val temp = File(session, LiveArchiveTrainingStore.COMPACT_FILE + ".tmp")
        if (temp.exists()) temp.delete()
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER)).bufferedWriter(Charsets.UTF_8, BUFFER).use { writer ->
            records.forEach { r ->
                val features = org.json.JSONArray(); r.vector.forEach(features::put)
                writer.append(
                    JSONObject()
                        .put("schema", LiveArchiveTrainingStore.COMPACT_SCHEMA)
                        .put("feature_schema", com.parmod.ema.engine.NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
                        .put("id", r.id).put("market", r.index.name).put("engine", r.engine.name).put("side", r.side.name)
                        .put("observation_timestamp", r.observationTimestamp).put("outcome_timestamp", r.outcomeTimestamp)
                        .put("instrument_key", r.instrumentKey).put("entry_premium", r.entryPremium).put("lot_size", r.lotSize)
                        .put("features", features).put("success", r.success).put("exit_premium", r.exitPremium)
                        .put("mfe_return", r.mfeReturn).put("mae_return", r.maeReturn).put("net_return", r.netReturn)
                        .put("exit_reason", r.exitReason).toString(),
                ).append('\n')
            }
        }
        val verifiedCount = countGzipLines(temp)
        if (verifiedCount != records.size.toLong()) { temp.delete(); return CompactResult(false, false, 0L, false) }
        if (compactFile.exists() && !compactFile.delete()) { temp.delete(); return CompactResult(false, false, 0L, false) }
        if (!temp.renameTo(compactFile)) { temp.delete(); return CompactResult(false, false, 0L, false) }

        val high = isHighValue(records)
        val manifest = JSONObject()
            .put("schema", "vardhani-live-compaction-v1")
            .put("verified", true)
            .put("created_at", System.currentTimeMillis())
            .put("records", records.size)
            .put("feature_schema", com.parmod.ema.engine.NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
            .put("compact_sha256", sha256(compactFile))
            .put("observation_sources_sha256", sourceSetHash(session, "observations"))
            .put("outcome_sources_sha256", sourceSetHash(session, "outcomes"))
            .put("high_value", high)
            .put("raw_pruned", false)
        writeAtomic(File(session, LiveArchiveTrainingStore.COMPACTION_MANIFEST), manifest.toString(2))
        return CompactResult(true, true, records.size.toLong(), high)
    }

    private fun compressRawTicksLosslessly(session: File): Int {
        var count = 0
        session.listFiles()?.filter { it.isFile && it.name.startsWith("ticks") && it.name.endsWith(".ndjson") }?.forEach { source ->
            val target = File(source.parentFile, source.name + ".gz")
            if (target.isFile) {
                if (sha256(source) == sha256UncompressedGzip(target)) { source.delete(); return@forEach }
                target.delete()
            }
            val temp = File(target.parentFile, target.name + ".tmp")
            GZIPOutputStream(BufferedOutputStream(FileOutputStream(temp), BUFFER)).use { out -> FileInputStream(source).buffered(BUFFER).use { it.copyTo(out, BUFFER) } }
            if (sha256(source) != sha256UncompressedGzip(temp)) { temp.delete(); return@forEach }
            if (!temp.renameTo(target)) { temp.delete(); return@forEach }
            if (source.delete()) count++
        }
        return count
    }

    private fun isHighValue(records: List<LiveArchiveTrainingStore.Record>): Boolean = records.any { r ->
        kotlin.math.abs(r.mfeReturn) >= 0.20 || kotlin.math.abs(r.maeReturn) >= 0.15 || kotlin.math.abs(r.netReturn) >= 0.10 ||
            r.exitReason.contains("ORDER_FLOW", true) || r.exitReason.contains("INDEX_INVALIDATION", true)
    }

    private fun readManifest(session: File): JSONObject? = runCatching {
        File(session, LiveArchiveTrainingStore.COMPACTION_MANIFEST).takeIf(File::isFile)?.readText(Charsets.UTF_8)?.let(::JSONObject)
    }.getOrNull()

    private fun updateManifestPruned(session: File) {
        val file = File(session, LiveArchiveTrainingStore.COMPACTION_MANIFEST)
        val o = readManifest(session) ?: return
        o.put("raw_pruned", true).put("raw_pruned_at", System.currentTimeMillis())
        writeAtomic(file, o.toString(2))
    }

    private fun countHighValueSessions(): Int = store.sessionsRoot().listFiles()?.count { readManifest(it)?.optBoolean("high_value", false) == true } ?: 0
    private fun countCompactRecords(): Long = store.sessionsRoot().listFiles()?.sumOf { readManifest(it)?.takeIf { m -> m.optBoolean("verified", false) }?.optLong("records") ?: 0L } ?: 0L

    private fun countGzipLines(file: File): Long {
        var count = 0L
        BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(file), BUFFER), Charsets.UTF_8), BUFFER).use { reader ->
            while (true) { val line = reader.readLine() ?: break; if (line.isNotBlank()) count++ }
        }
        return count
    }

    private fun sourceSetHash(session: File, prefix: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        session.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".ndjson") }?.sortedBy { it.name }?.forEach { file ->
            md.update(file.name.toByteArray(Charsets.UTF_8)); md.update(sha256(file).toByteArray(Charsets.UTF_8))
        }
        return md.digest().hex()
    }

    private fun archiveRoot(): File = File(appContext.filesDir, "vardhani_live_research_archive/v1").apply { mkdirs() }
    private fun archiveBytes(): Long = archiveRoot().walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun writeAtomic(file: File, value: String) {
        file.parentFile?.mkdirs(); val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(value, Charsets.UTF_8)
        if (file.exists() && !file.delete()) { temp.delete(); error("Could not replace ${file.name}") }
        if (!temp.renameTo(file)) { temp.delete(); error("Could not commit ${file.name}") }
    }

    private fun sha256(file: File): String = FileInputStream(file).use(::sha256)
    private fun sha256UncompressedGzip(file: File): String = GZIPInputStream(BufferedInputStream(FileInputStream(file), BUFFER)).use(::sha256)
    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER)
        while (true) { val n = input.read(buffer); if (n <= 0) break; md.update(buffer, 0, n) }
        return md.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFS = "vardhani_live_archive_storage_policy"
        private const val KEY_QUOTA = "quota_bytes"
        private const val KEY_MIN_FREE = "minimum_free_bytes"
        private const val KEY_RETENTION = "raw_retention_days"
        private const val KEY_RAW_ENABLED = "raw_capture_enabled"
        private const val KEY_LAST_MAINTENANCE = "last_maintenance_at"
        private const val KEY_PRUNED = "pruned_raw_files"
        private const val KEY_MESSAGE = "message"
        private const val BUFFER = 64 * 1024
        private const val GIB = 1024L * 1024L * 1024L
        private const val DEFAULT_QUOTA_BYTES = 2L * GIB
        private const val DEFAULT_MIN_FREE_BYTES = 2L * GIB
        private const val DEFAULT_RAW_RETENTION_DAYS = 14
        private const val MIN_QUOTA_BYTES = 1L * GIB
        private const val MAX_QUOTA_BYTES = 8L * GIB
        private const val MIN_FREE_FLOOR = 512L * 1024L * 1024L
        private const val MAX_MIN_FREE_BYTES = 8L * GIB
    }
}
