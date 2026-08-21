package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/** Reads completed live observations/outcomes as exact, deduplicated training evidence. */
class LiveArchiveTrainingStore(context: Context) {
    data class Record(
        val id: String,
        val index: MarketIndex,
        val engine: EngineId,
        val side: PositionSide,
        val observationTimestamp: Long,
        val outcomeTimestamp: Long,
        val instrumentKey: String,
        val entryPremium: Double,
        val lotSize: Int,
        val vector: DoubleArray,
        val success: Boolean,
        val exitPremium: Double,
        val mfeReturn: Double,
        val maeReturn: Double,
        val netReturn: Double,
        val exitReason: String,
        val migratedLegacyVector: Boolean = false,
    ) {
        val canonicalKey: String
            get() = "${index.name}|$instrumentKey|$observationTimestamp|${engine.name}|${side.name}"

        fun features(): NumericalMetaBrain.Features = ArchivedFeatureVectorAdapter.toFeatures(vector)
    }

    data class Summary(
        val records: Int,
        val duplicatesRemoved: Int,
        val conflictsRejected: Int,
        val incompatibleRejected: Int,
        val legacyVectorsMigrated: Int,
        val fromTimestamp: Long,
        val toTimestamp: Long,
        val niftyRecords: Int,
        val sensexRecords: Int,
    ) {
        val trainable: Boolean get() = records > 0
    }

    data class LoadResult(val records: List<Record>, val summary: Summary)

    private data class Outcome(
        val id: String,
        val index: MarketIndex,
        val timestamp: Long,
        val success: Boolean,
        val exitPremium: Double,
        val mfeReturn: Double,
        val maeReturn: Double,
        val netReturn: Double,
        val exitReason: String,
    )

    private val appContext = context.applicationContext
    private val zone = ZoneId.of("Asia/Kolkata")

    fun load(
        months: Int,
        markets: Set<MarketIndex>,
        shouldCancel: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): LoadResult {
        require(months in PrelabelledTrainingWindowPlan.ALLOWED_MONTHS)
        require(markets.isNotEmpty())
        val sessions = sessionsRoot().listFiles()?.filter(File::isDirectory)?.sortedBy { it.name }.orEmpty()
        if (sessions.isEmpty()) return emptyResult()

        val outcomes = LinkedHashMap<String, Outcome>()
        var incompatible = 0
        sessions.forEachIndexed { i, dir ->
            if (shouldCancel()) error("Training cancelled")
            outcomeFiles(dir).forEach { file ->
                readLines(file) { line ->
                    if (shouldCancel()) error("Training cancelled")
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@readLines
                    val parsed = parseOutcome(o) ?: return@readLines
                    val old = outcomes[parsed.id]
                    if (old == null) outcomes[parsed.id] = parsed
                    else if (old != parsed) incompatible++
                }
            }
            if ((i + 1) % 10 == 0) onProgress("LIVE ARCHIVE · outcomes indexed ${i + 1}/${sessions.size} sessions")
        }

        val records = ArrayList<Record>()
        val ids = HashSet<String>()
        val canonical = HashSet<String>()
        var duplicates = 0
        var conflicts = incompatible
        var incompatibleRows = 0
        var migrated = 0

        sessions.forEachIndexed { i, dir ->
            if (shouldCancel()) error("Training cancelled")
            val compact = File(dir, COMPACT_FILE)
            if (compact.isFile) {
                readLines(compact) { line ->
                    val record = runCatching { parseCompact(JSONObject(line)) }.getOrNull()
                    if (record == null) incompatibleRows++
                    else addDeduplicated(record, ids, canonical, records).also { code ->
                        if (code == 1) duplicates++ else if (code == 2) conflicts++
                        if (record.migratedLegacyVector && code == 0) migrated++
                    }
                }
            } else {
                observationFiles(dir).forEach { file ->
                    readLines(file) { line ->
                        if (shouldCancel()) error("Training cancelled")
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: run { incompatibleRows++; return@readLines }
                        val id = o.optString("id")
                        val outcome = outcomes[id] ?: return@readLines
                        val record = runCatching { parseObservation(o, outcome) }.getOrNull()
                        if (record == null) incompatibleRows++
                        else addDeduplicated(record, ids, canonical, records).also { code ->
                            if (code == 1) duplicates++ else if (code == 2) conflicts++
                            if (record.migratedLegacyVector && code == 0) migrated++
                        }
                    }
                }
            }
            if ((i + 1) % 10 == 0) onProgress("LIVE ARCHIVE · replay indexed ${i + 1}/${sessions.size} sessions · ${records.size} unique labels")
        }

        records.sortBy { it.observationTimestamp }
        if (records.isEmpty()) return LoadResult(emptyList(), Summary(0, duplicates, conflicts, incompatibleRows, migrated, 0L, 0L, 0, 0))
        val latest = records.last().observationTimestamp
        val latestDate = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate()
        val cutoff = if (months == PrelabelledTrainingWindowPlan.FULL) Long.MIN_VALUE
        else latestDate.minusMonths(months.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val filtered = records.filter { it.index in markets && it.observationTimestamp >= cutoff }
        if (filtered.isEmpty()) return LoadResult(emptyList(), Summary(0, duplicates, conflicts, incompatibleRows, migrated, 0L, 0L, 0, 0))
        return LoadResult(
            filtered,
            Summary(
                records = filtered.size,
                duplicatesRemoved = duplicates,
                conflictsRejected = conflicts,
                incompatibleRejected = incompatibleRows,
                legacyVectorsMigrated = filtered.count { it.migratedLegacyVector },
                fromTimestamp = filtered.first().observationTimestamp,
                toTimestamp = filtered.last().observationTimestamp,
                niftyRecords = filtered.count { it.index == MarketIndex.NIFTY },
                sensexRecords = filtered.count { it.index == MarketIndex.SENSEX },
            ),
        )
    }

    fun allCompletedForSession(sessionDir: File): List<Record> {
        val outcomes = LinkedHashMap<String, Outcome>()
        outcomeFiles(sessionDir).forEach { file -> readLines(file) { line -> runCatching { parseOutcome(JSONObject(line)) }.getOrNull()?.let { outcomes.putIfAbsent(it.id, it) } } }
        val records = ArrayList<Record>()
        val ids = HashSet<String>()
        val canonical = HashSet<String>()
        observationFiles(sessionDir).forEach { file ->
            readLines(file) { line ->
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@readLines
                val outcome = outcomes[o.optString("id")] ?: return@readLines
                val record = runCatching { parseObservation(o, outcome) }.getOrNull() ?: return@readLines
                if (addDeduplicated(record, ids, canonical, records) == 0) Unit
            }
        }
        return records.sortedBy { it.observationTimestamp }
    }

    internal fun sessionsRoot(): File = File(appContext.filesDir, "vardhani_live_research_archive/v1/sessions").apply { mkdirs() }

    private fun parseObservation(o: JSONObject, outcome: Outcome): Record? {
        if (!o.optString("schema").startsWith("vardhani-live-observation-v")) return null
        val archiveSchema = o.optInt("archive_schema", 0)
        val featureSchema = o.optInt("feature_schema", 0)
        if (archiveSchema <= 0 || featureSchema <= 0 || featureSchema > NumericalMetaBrain.FEATURE_SCHEMA_VERSION) return null
        val id = o.optString("id"); if (id.isBlank() || id != outcome.id) return null
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        if (outcome.index != index) return null
        val engine = enumValue<EngineId>(o.optString("engine")) ?: return null
        val side = enumValue<PositionSide>(o.optString("side")) ?: return null
        val a = o.optJSONArray("features") ?: return null
        if (a.length() !in NumericalMetaBrain.LEGACY_FEATURE_COUNT..NumericalMetaBrain.FEATURE_COUNT) return null
        val raw = DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }
        if (raw.any { !it.isFinite() }) return null
        val vector = ArchivedFeatureVectorAdapter.normalizeLegacy(raw)
        // Adapter validation catches malformed engine/market/side/intercept vectors.
        val reconstructed = ArchivedFeatureVectorAdapter.toFeatures(vector)
        if (reconstructed.index != index || reconstructed.engine != engine || reconstructed.side != side) return null
        return Record(
            id = id,
            index = index,
            engine = engine,
            side = side,
            observationTimestamp = o.optLong("timestamp", 0L),
            outcomeTimestamp = outcome.timestamp,
            instrumentKey = o.optString("instrument_key"),
            entryPremium = o.optDouble("entry_premium", 0.0),
            lotSize = o.optInt("lot_size", 0),
            vector = vector,
            success = outcome.success,
            exitPremium = outcome.exitPremium,
            mfeReturn = outcome.mfeReturn,
            maeReturn = outcome.maeReturn,
            netReturn = outcome.netReturn,
            exitReason = outcome.exitReason,
            migratedLegacyVector = raw.size < NumericalMetaBrain.FEATURE_COUNT,
        ).takeIf { it.observationTimestamp > 0L && it.outcomeTimestamp >= it.observationTimestamp && it.entryPremium > 0.0 && it.lotSize > 0 }
    }

    private fun parseOutcome(o: JSONObject): Outcome? {
        if (!o.optString("schema").startsWith("vardhani-live-outcome-v")) return null
        val id = o.optString("id"); if (id.isBlank()) return null
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        val ts = o.optLong("timestamp", 0L); if (ts <= 0L) return null
        val exit = o.optDouble("exit_premium", Double.NaN)
        val mfe = o.optDouble("mfe_return", Double.NaN)
        val mae = o.optDouble("mae_return", Double.NaN)
        val net = o.optDouble("net_return", Double.NaN)
        if (!exit.isFinite() || !mfe.isFinite() || !mae.isFinite() || !net.isFinite()) return null
        return Outcome(id, index, ts, o.optBoolean("success"), exit, mfe, mae, net, o.optString("exit_reason"))
    }

    private fun parseCompact(o: JSONObject): Record? {
        if (o.optString("schema") != COMPACT_SCHEMA) return null
        val featureSchema = o.optInt("feature_schema", 0)
        if (featureSchema <= 0 || featureSchema > NumericalMetaBrain.FEATURE_SCHEMA_VERSION) return null
        val a = o.optJSONArray("features") ?: return null
        if (a.length() !in NumericalMetaBrain.LEGACY_FEATURE_COUNT..NumericalMetaBrain.FEATURE_COUNT) return null
        val raw = DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }; if (raw.any { !it.isFinite() }) return null
        val vector = ArchivedFeatureVectorAdapter.normalizeLegacy(raw)
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        val engine = enumValue<EngineId>(o.optString("engine")) ?: return null
        val side = enumValue<PositionSide>(o.optString("side")) ?: return null
        val f = ArchivedFeatureVectorAdapter.toFeatures(vector)
        if (f.index != index || f.engine != engine || f.side != side) return null
        return Record(
            id = o.optString("id"), index = index, engine = engine, side = side,
            observationTimestamp = o.optLong("observation_timestamp"), outcomeTimestamp = o.optLong("outcome_timestamp"),
            instrumentKey = o.optString("instrument_key"), entryPremium = o.optDouble("entry_premium"), lotSize = o.optInt("lot_size"),
            vector = vector, success = o.optBoolean("success"), exitPremium = o.optDouble("exit_premium"),
            mfeReturn = o.optDouble("mfe_return"), maeReturn = o.optDouble("mae_return"), netReturn = o.optDouble("net_return"),
            exitReason = o.optString("exit_reason"), migratedLegacyVector = raw.size < NumericalMetaBrain.FEATURE_COUNT,
        ).takeIf { it.id.isNotBlank() && it.observationTimestamp > 0 && it.outcomeTimestamp >= it.observationTimestamp && it.vector.all(Double::isFinite) }
    }

    private fun addDeduplicated(record: Record, ids: MutableSet<String>, canonical: MutableSet<String>, output: MutableList<Record>): Int {
        if (!ids.add(record.id)) return 1
        if (!canonical.add(record.canonicalKey)) { ids.remove(record.id); return 2 }
        output += record
        return 0
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T? = runCatching { enumValueOf<T>(value) }.getOrNull()

    private fun observationFiles(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.name.startsWith("observations") && it.name.endsWith(".ndjson") }.orEmpty()
    private fun outcomeFiles(dir: File): List<File> = dir.listFiles()?.filter { it.isFile && it.name.startsWith("outcomes") && it.name.endsWith(".ndjson") }.orEmpty()

    private fun readLines(file: File, action: (String) -> Unit) {
        val input = if (file.name.endsWith(".gz")) GZIPInputStream(FileInputStream(file), BUFFER) else FileInputStream(file)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER).useLines { lines -> lines.filter(String::isNotBlank).forEach(action) }
    }

    private fun emptyResult() = LoadResult(emptyList(), Summary(0, 0, 0, 0, 0, 0L, 0L, 0, 0))

    companion object {
        const val COMPACT_SCHEMA = "vardhani-live-training-compact-v1"
        const val COMPACT_FILE = "training_compact.ndjson.gz"
        const val COMPACTION_MANIFEST = "compaction.json"
        private const val BUFFER = 64 * 1024
    }
}
