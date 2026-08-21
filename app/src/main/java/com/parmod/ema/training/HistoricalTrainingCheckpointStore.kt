package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Generation-boundary checkpointing for long on-device historical research.
 *
 * Checkpoints never contain locked-TEST metrics and are accepted only when corpus,
 * window, feature schema, algorithm schema, market scope and frozen Production all
 * match. A REFIT_COMPLETE checkpoint may contain the already-fitted Candidate model
 * so an interrupted locked-test scoring pass does not require another multi-million
 * row refit. Re-reading locked TEST after an interruption is deterministic scoring;
 * no policy/model update is permitted from its partial or final results.
 */
class HistoricalTrainingCheckpointStore(context: Context) {
    enum class Stage { SEARCH_GENERATION_COMPLETE, REFIT_COMPLETE }

    data class State(
        val identity: String,
        val scope: String,
        val months: Int,
        val stage: Stage,
        val generation: Int,
        val candidatesEvaluated: Int,
        val seenSignatures: List<String>,
        val best: HistoricalCorpusTrainer.CandidateEvaluation,
        val candidateByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics> = emptyMap(),
        val productionByMarket: Map<MarketIndex, HistoricalCorpusTrainer.Metrics> = emptyMap(),
        val champion: NumericalMetaBrain.ModelState? = null,
        val savedAt: Long = System.currentTimeMillis(),
    )

    private val root = File(context.filesDir, "vardhani_historical_training_checkpoints/v$CHECKPOINT_SCHEMA").apply { mkdirs() }

    fun identity(
        store: AimlHistoricalOptionCorpusV1Store,
        plan: PrelabelledTrainingWindowPlan.Plan,
        scope: String,
        production: NumericalMetaBrain.ModelState,
    ): String {
        val p = store.metadata()
        val raw = buildString {
            append("checkpoint=").append(CHECKPOINT_SCHEMA)
            append("|algorithm=").append(ALGORITHM_SCHEMA)
            append("|feature=").append(NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
            append("|scope=").append(scope)
            append("|window=").append(plan.requestedMonths)
            append("|from=").append(plan.fromDate)
            append("|to=").append(plan.toDate)
            append("|tb=").append(plan.trainBoundaryMs)
            append("|cb=").append(plan.calibrationBoundaryMs)
            append("|xb=").append(plan.testBoundaryMs)
            listOf("schema", "market", "accepted", "trainRows", "validationRows", "testRows", "contracts", "fromDate", "toDate").forEach { key ->
                append('|').append(key).append('=').append(p.getProperty(key).orEmpty())
            }
            append("|prod=").append(modelFingerprint(production))
        }
        return sha256(raw)
    }

    fun load(scope: String, months: Int, expectedIdentity: String): State? {
        val file = file(scope, months)
        if (!file.isFile) return null
        val state = runCatching { decode(JSONObject(file.readText(Charsets.UTF_8))) }.getOrNull() ?: run {
            file.delete(); return null
        }
        if (state.identity != expectedIdentity || state.scope != scope || state.months != months) {
            file.delete()
            return null
        }
        return state
    }

    @Synchronized
    fun save(state: State) {
        val target = file(state.scope, state.months)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encode(state).toString(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("Could not replace historical training checkpoint")
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not commit historical training checkpoint")
        }
    }

    fun clear(scope: String, months: Int) { file(scope, months).delete() }
    fun clearAll() { root.deleteRecursively(); root.mkdirs() }
    fun has(scope: String, months: Int): Boolean = file(scope, months).isFile

    private fun file(scope: String, months: Int): File =
        File(root, "${scope.lowercase().replace(Regex("[^a-z0-9_-]"), "_")}_${if (months == 0) "full" else "${months}m"}.json")

    private fun encode(s: State) = JSONObject()
        .put("checkpoint_schema", CHECKPOINT_SCHEMA)
        .put("algorithm_schema", ALGORITHM_SCHEMA)
        .put("feature_schema", NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
        .put("identity", s.identity)
        .put("scope", s.scope)
        .put("months", s.months)
        .put("stage", s.stage.name)
        .put("generation", s.generation)
        .put("candidates_evaluated", s.candidatesEvaluated)
        .put("saved_at", s.savedAt)
        .put("seen", JSONArray().apply { s.seenSignatures.forEach(::put) })
        .put("best", encodeEvaluation(s.best))
        .put("candidate_by_market", encodeMetricsMap(s.candidateByMarket))
        .put("production_by_market", encodeMetricsMap(s.productionByMarket))
        .put("champion", s.champion?.let(::encodeModel) ?: JSONObject.NULL)

    private fun decode(o: JSONObject): State {
        require(o.optInt("checkpoint_schema") == CHECKPOINT_SCHEMA)
        require(o.optInt("algorithm_schema") == ALGORITHM_SCHEMA)
        require(o.optInt("feature_schema") == NumericalMetaBrain.FEATURE_SCHEMA_VERSION)
        val seen = o.optJSONArray("seen") ?: JSONArray()
        return State(
            identity = o.getString("identity"),
            scope = o.getString("scope"),
            months = o.getInt("months"),
            stage = Stage.valueOf(o.getString("stage")),
            generation = o.getInt("generation"),
            candidatesEvaluated = o.getInt("candidates_evaluated"),
            seenSignatures = List(seen.length()) { seen.getString(it) },
            best = decodeEvaluation(o.getJSONObject("best")),
            candidateByMarket = decodeMetricsMap(o.optJSONObject("candidate_by_market")),
            productionByMarket = decodeMetricsMap(o.optJSONObject("production_by_market")),
            champion = if (o.isNull("champion")) null else decodeModel(o.getJSONObject("champion")),
            savedAt = o.optLong("saved_at"),
        )
    }

    private fun encodeEvaluation(e: HistoricalCorpusTrainer.CandidateEvaluation) = JSONObject()
        .put("hyper", encodeHyper(e.hyperParameters))
        .put("folds_run", e.foldsRun)
        .put("folds_won", e.foldsWon)
        .put("candidate", encodeMetrics(e.candidate))
        .put("production", encodeMetrics(e.production))
        .put("score", e.score)
        .put("robust", e.robust)

    private fun decodeEvaluation(o: JSONObject) = HistoricalCorpusTrainer.CandidateEvaluation(
        hyperParameters = decodeHyper(o.getJSONObject("hyper")),
        foldsRun = o.getInt("folds_run"),
        foldsWon = o.getInt("folds_won"),
        candidate = decodeMetrics(o.getJSONObject("candidate")),
        production = decodeMetrics(o.getJSONObject("production")),
        score = o.getDouble("score"),
        robust = o.getBoolean("robust"),
    )

    private fun encodeMetrics(m: HistoricalCorpusTrainer.Metrics) = JSONObject()
        .put("labels", m.labels)
        .put("accuracy", m.accuracy)
        .put("brier", m.brier)
        .put("take_samples", m.takeSamples)
        .put("take_precision", m.takePrecision)
        .put("reject_samples", m.rejectSamples)
        .put("reject_precision", m.rejectPrecision)
        .put("take_net", m.takeAverageNetReturn)

    private fun decodeMetrics(o: JSONObject) = HistoricalCorpusTrainer.Metrics(
        labels = o.optLong("labels"),
        accuracy = o.optDouble("accuracy"),
        brier = o.optDouble("brier", 1.0),
        takeSamples = o.optLong("take_samples"),
        takePrecision = o.optDouble("take_precision"),
        rejectSamples = o.optLong("reject_samples"),
        rejectPrecision = o.optDouble("reject_precision"),
        takeAverageNetReturn = o.optDouble("take_net"),
    )

    private fun encodeMetricsMap(map: Map<MarketIndex, HistoricalCorpusTrainer.Metrics>) = JSONObject().apply {
        map.forEach { (market, metrics) -> put(market.name, encodeMetrics(metrics)) }
    }

    private fun decodeMetricsMap(o: JSONObject?): Map<MarketIndex, HistoricalCorpusTrainer.Metrics> {
        if (o == null) return emptyMap()
        return MarketIndex.entries.mapNotNull { market -> o.optJSONObject(market.name)?.let { market to decodeMetrics(it) } }.toMap()
    }

    private fun encodeHyper(h: NumericalMetaBrain.HyperParameters) = JSONObject()
        .put("learning_rate", h.learningRate).put("l2", h.l2)
        .put("take", h.takeThreshold).put("reject", h.rejectThreshold)

    private fun decodeHyper(o: JSONObject) = NumericalMetaBrain.HyperParameters(
        learningRate = o.getDouble("learning_rate"),
        l2 = o.getDouble("l2"),
        takeThreshold = o.getDouble("take"),
        rejectThreshold = o.getDouble("reject"),
    ).sanitized()

    private fun encodeModel(m: NumericalMetaBrain.ModelState) = JSONObject()
        .put("weights", JSONArray().apply { m.weights.forEach(::put) })
        .put("bias", m.bias)
        .put("samples", m.samplesLearned)
        .put("version", m.modelVersion)
        .put("mode", m.mode.name)
        .put("hyper", encodeHyper(m.hyperParameters))

    private fun decodeModel(o: JSONObject): NumericalMetaBrain.ModelState {
        val a = o.getJSONArray("weights")
        require(a.length() in 1..NumericalMetaBrain.FEATURE_COUNT)
        return NumericalMetaBrain.ModelState(
            weights = DoubleArray(a.length()) { a.getDouble(it) },
            bias = o.getDouble("bias"),
            samplesLearned = o.getLong("samples"),
            modelVersion = o.getLong("version"),
            mode = NumericalMetaBrain.Mode.valueOf(o.getString("mode")),
            hyperParameters = decodeHyper(o.getJSONObject("hyper")),
        )
    }

    private fun modelFingerprint(m: NumericalMetaBrain.ModelState): String {
        val raw = buildString {
            append(m.bias).append('|').append(m.samplesLearned).append('|').append(m.modelVersion).append('|').append(m.mode.name)
            val h = m.hyperParameters
            append('|').append(h.learningRate).append('|').append(h.l2).append('|').append(h.takeThreshold).append('|').append(h.rejectThreshold)
            m.weights.forEach { append('|').append(java.lang.Double.doubleToRawLongBits(it)) }
        }
        return sha256(raw)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CHECKPOINT_SCHEMA = 1
        const val ALGORITHM_SCHEMA = 1
    }
}
