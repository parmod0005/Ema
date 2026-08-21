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
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/** Reads completed live observations/outcomes as exact, deduplicated, fail-closed training evidence. */
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

    private class OutcomeIndex {
        val values = LinkedHashMap<String, Outcome>()
        private val blocked = HashSet<String>()
        var duplicates = 0
            private set
        var conflicts = 0
            private set

        fun add(value: Outcome) {
            if (value.id in blocked) return
            val old = values[value.id]
            when {
                old == null -> values[value.id] = value
                old == value -> duplicates++
                else -> {
                    conflicts++
                    blocked += value.id
                    values.remove(value.id)
                }
            }
        }
    }

    private class EvidenceIndex {
        val records = ArrayList<Record>()
        private val byId = HashMap<String, Record>()
        private val byCanonical = HashMap<String, Record>()
        private val blockedIds = HashSet<String>()
        private val blockedCanonical = HashSet<String>()
        var duplicates = 0
            private set
        var conflicts = 0
            private set
        var migrated = 0
            private set

        fun add(value: Record) {
            if (value.id in blockedIds || value.canonicalKey in blockedCanonical) return
            val oldId = byId[value.id]
            if (oldId != null) {
                if (sameEvidence(oldId, value)) duplicates++ else rejectConflict(oldId, value)
                return
            }
            val oldCanonical = byCanonical[value.canonicalKey]
            if (oldCanonical != null) {
                if (sameEvidence(oldCanonical, value, ignoreId = true)) duplicates++ else rejectConflict(oldCanonical, value)
                return
            }
            byId[value.id] = value
            byCanonical[value.canonicalKey] = value
            records += value
            if (value.migratedLegacyVector) migrated++
        }

        private fun rejectConflict(old: Record, incoming: Record) {
            conflicts++
            blockedIds += old.id
            blockedIds += incoming.id
            blockedCanonical += old.canonicalKey
            blockedCanonical += incoming.canonicalKey
            byId.remove(old.id)
            byCanonical.remove(old.canonicalKey)
            records.remove(old)
            if (old.migratedLegacyVector) migrated--
        }

        private fun sameEvidence(a: Record, b: Record, ignoreId: Boolean = false): Boolean =
            (ignoreId || a.id == b.id) &&
                a.index == b.index && a.engine == b.engine && a.side == b.side &&
                a.observationTimestamp == b.observationTimestamp && a.outcomeTimestamp == b.outcomeTimestamp &&
                a.instrumentKey == b.instrumentKey && a.entryPremium == b.entryPremium && a.lotSize == b.lotSize &&
                a.vector.contentEquals(b.vector) && a.success == b.success && a.exitPremium == b.exitPremium &&
                a.mfeReturn == b.mfeReturn && a.maeReturn == b.maeReturn && a.netReturn == b.netReturn &&
                a.exitReason == b.exitReason
    }

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

        val outcomes = OutcomeIndex()
        var incompatibleRows = 0
        sessions.forEachIndexed { i, dir ->
            if (shouldCancel()) error("Training cancelled")
            outcomeFiles(dir).forEach { file ->
                readLines(file) { line ->
                    if (shouldCancel()) error("Training cancelled")
                    val json = runCatching { JSONObject(line) }.getOrNull()
                    val parsed = json?.let(::parseOutcome)
                    if (parsed == null) incompatibleRows++ else outcomes.add(parsed)
                }
            }
            if ((i + 1) % 10 == 0) onProgress("LIVE ARCHIVE · outcomes indexed ${i + 1}/${sessions.size} sessions")
        }

        val evidence = EvidenceIndex()
        sessions.forEachIndexed { i, dir ->
            if (shouldCancel()) error("Training cancelled")
            val compact = File(dir, COMPACT_FILE)
            if (compactIsFresh(dir, compact)) {
                readLines(compact) { line ->
                    if (shouldCancel()) error("Training cancelled")
                    val record = runCatching { parseCompact(JSONObject(line)) }.getOrNull()
                    if (record == null) incompatibleRows++ else evidence.add(record)
                }
            } else {
                observationFiles(dir).forEach { file ->
                    readLines(file) { line ->
                        if (shouldCancel()) error("Training cancelled")
                        val json = runCatching { JSONObject(line) }.getOrNull()
                        if (json == null) {
                            incompatibleRows++
                            return@readLines
                        }
                        val outcome = outcomes.values[json.optString("id")] ?: return@readLines
                        val record = runCatching { parseObservation(json, outcome) }.getOrNull()
                        if (record == null) incompatibleRows++ else evidence.add(record)
                    }
                }
            }
            if ((i + 1) % 10 == 0) onProgress("LIVE ARCHIVE · replay indexed ${i + 1}/${sessions.size} sessions · ${evidence.records.size} unique labels")
        }

        evidence.records.sortBy { it.observationTimestamp }
        val duplicates = outcomes.duplicates + evidence.duplicates
        val conflicts = outcomes.conflicts + evidence.conflicts
        if (evidence.records.isEmpty()) return LoadResult(emptyList(), Summary(0, duplicates, conflicts, incompatibleRows, evidence.migrated, 0L, 0L, 0, 0))
        val latest = evidence.records.last().observationTimestamp
        val latestDate = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate()
        val cutoff = if (months == PrelabelledTrainingWindowPlan.FULL) Long.MIN_VALUE
        else latestDate.minusMonths(months.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val filtered = evidence.records.filter { it.index in markets && it.observationTimestamp >= cutoff }
        if (filtered.isEmpty()) return LoadResult(emptyList(), Summary(0, duplicates, conflicts, incompatibleRows, 0, 0L, 0L, 0, 0))
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
        val outcomes = OutcomeIndex()
        outcomeFiles(sessionDir).forEach { file ->
            readLines(file) { line -> runCatching { parseOutcome(JSONObject(line)) }.getOrNull()?.let(outcomes::add) }
        }
        val evidence = EvidenceIndex()
        observationFiles(sessionDir).forEach { file ->
            readLines(file) { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@readLines
                val outcome = outcomes.values[json.optString("id")] ?: return@readLines
                runCatching { parseObservation(json, outcome) }.getOrNull()?.let(evidence::add)
            }
        }
        return evidence.records.sortedBy { it.observationTimestamp }
    }

    internal fun sessionsRoot(): File = File(appContext.filesDir, "vardhani_live_research_archive/v1/sessions").apply { mkdirs() }

    private fun compactIsFresh(dir: File, compact: File): Boolean {
        if (!compact.isFile) return false
        val manifest = runCatching { JSONObject(File(dir, COMPACTION_MANIFEST).readText(Charsets.UTF_8)) }.getOrNull() ?: return false
        if (!manifest.optBoolean("verified", false)) return false
        if (manifest.optInt("feature_schema", 0) > NumericalMetaBrain.FEATURE_SCHEMA_VERSION) return false
        if (manifest.optString("compact_sha256") != sha256(compact)) return false
        if (manifest.optString("observation_sources_sha256") != sourceSetHash(dir, "observations")) return false
        if (manifest.optString("outcome_sources_sha256") != sourceSetHash(dir, "outcomes")) return false
        return true
    }

    private fun parseObservation(o: JSONObject, outcome: Outcome): Record? {
        if (!o.optString("schema").startsWith("vardhani-live-observation-v")) return null
        val archiveSchema = o.optInt("archive_schema", 0)
        val featureSchema = o.optInt("feature_schema", 0)
        if (archiveSchema <= 0 || featureSchema <= 0 || featureSchema > NumericalMetaBrain.FEATURE_SCHEMA_VERSION) return null
        val id = o.optString("id")
        if (id.isBlank() || id != outcome.id) return null
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        if (outcome.index != index) return null
        val engine = enumValue<EngineId>(o.optString("engine")) ?: return null
        val side = enumValue<PositionSide>(o.optString("side")) ?: return null
        val a = o.optJSONArray("features") ?: return null
        if (a.length() != NumericalMetaBrain.LEGACY_FEATURE_COUNT && a.length() != NumericalMetaBrain.FEATURE_COUNT) return null
        val raw = DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }
        if (raw.any { !it.isFinite() }) return null
        val vector = ArchivedFeatureVectorAdapter.normalizeLegacy(raw)
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
            migratedLegacyVector = raw.size == NumericalMetaBrain.LEGACY_FEATURE_COUNT,
        ).takeIf { it.observationTimestamp > 0L && it.outcomeTimestamp >= it.observationTimestamp && it.entryPremium > 0.0 && it.lotSize > 0 }
    }

    private fun parseOutcome(o: JSONObject): Outcome? {
        if (!o.optString("schema").startsWith("vardhani-live-outcome-v")) return null
        val id = o.optString("id")
        if (id.isBlank()) return null
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        val ts = o.optLong("timestamp", 0L)
        if (ts <= 0L) return null
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
        if (a.length() != NumericalMetaBrain.LEGACY_FEATURE_COUNT && a.length() != NumericalMetaBrain.FEATURE_COUNT) return null
        val raw = DoubleArray(a.length()) { a.optDouble(it, Double.NaN) }
        if (raw.any { !it.isFinite() }) return null
        val vector = ArchivedFeatureVectorAdapter.normalizeLegacy(raw)
        val index = enumValue<MarketIndex>(o.optString("market")) ?: return null
        val engine = enumValue<EngineId>(o.optString("engine")) ?: return null
        val side = enumValue<PositionSide>(o.optString("side")) ?: return null
        val f = ArchivedFeatureVectorAdapter.toFeatures(vector)
        if (f.index != index || f.engine != engine || f.side != side) return null
        return Record(
            id = o.optString("id"),
            index = index,
            engine = engine,
            side = side,
            observationTimestamp = o.optLong("observation_timestamp"),
            outcomeTimestamp = o.optLong("outcome_timestamp"),
            instrumentKey = o.optString("instrument_key"),
            entryPremium = o.optDouble("entry_premium"),
            lotSize = o.optInt("lot_size"),
            vector = vector,
            success = o.optBoolean("success"),
            exitPremium = o.optDouble("exit_premium"),
            mfeReturn = o.optDouble("mfe_return"),
            maeReturn = o.optDouble("mae_return"),
            netReturn = o.optDouble("net_return"),
            exitReason = o.optString("exit_reason"),
            migratedLegacyVector = raw.size == NumericalMetaBrain.LEGACY_FEATURE_COUNT,
        ).takeIf {
            it.id.isNotBlank() && it.observationTimestamp > 0 && it.outcomeTimestamp >= it.observationTimestamp &&
                it.entryPremium > 0.0 && it.lotSize > 0 && it.exitPremium.isFinite() && it.mfeReturn.isFinite() &&
                it.maeReturn.isFinite() && it.netReturn.isFinite() && it.vector.all(Double::isFinite)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T? = runCatching { enumValueOf<T>(value) }.getOrNull()

    private fun observationFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("observations") && it.name.endsWith(".ndjson") }.orEmpty()

    private fun outcomeFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("outcomes") && it.name.endsWith(".ndjson") }.orEmpty()

    private fun readLines(file: File, action: (String) -> Unit) {
        val input = if (file.name.endsWith(".gz")) GZIPInputStream(FileInputStream(file), BUFFER) else FileInputStream(file)
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER).useLines { lines ->
            lines.filter(String::isNotBlank).forEach(action)
        }
    }

    private fun sourceSetHash(session: File, prefix: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        session.listFiles()?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".ndjson") }?.sortedBy { it.name }?.forEach { file ->
            md.update(file.name.toByteArray(Charsets.UTF_8))
            md.update(sha256(file).toByteArray(Charsets.UTF_8))
        }
        return md.digest().hex()
    }

    private fun sha256(file: File): String = FileInputStream(file).use(::sha256)

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            md.update(buffer, 0, n)
        }
        return md.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun emptyResult() = LoadResult(emptyList(), Summary(0, 0, 0, 0, 0, 0L, 0L, 0, 0))

    companion object {
        const val COMPACT_SCHEMA = "vardhani-live-training-compact-v1"
        const val COMPACT_FILE = "training_compact.ndjson.gz"
        const val COMPACTION_MANIFEST = "compaction.json"
        private const val BUFFER = 64 * 1024
    }
}
