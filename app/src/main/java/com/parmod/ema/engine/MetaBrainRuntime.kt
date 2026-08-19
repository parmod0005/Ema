package com.parmod.ema.engine

import android.content.Context
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.max
import kotlin.math.pow

/**
 * Persistent on-device Numerical Meta Brain runtime.
 *
 * Architecture:
 *  - PRODUCTION: frozen model used for reference and, only after explicit validated promotion,
 *    optionally as an execution gate.
 *  - CANDIDATE: learns continuously from delayed live outcomes.
 *  - VALIDATOR: scores both models on the exact same unseen labels BEFORE candidate learning.
 *
 * All weights, model versions and validation statistics are persisted in SharedPreferences.
 */
object MetaBrainRuntime {
    private const val HISTORICAL_PRIOR_SAMPLES = 71L
    private const val HISTORICAL_PRIOR_BIAS = 0.2001902471
    private val HISTORICAL_PRIOR_WEIGHTS = doubleArrayOf(
        -0.0001358893, -0.1465515448, 0.0, -0.3469553189, -0.1191122290,
        -0.1191122290, 0.0036725290, 0.0, -0.0000452964, 0.0,
        0.0, 0.0, -0.0000271779, 0.0, 0.0,
        0.0, 0.0, 0.0, -0.2276839730, -0.0000679447,
        -0.0000452964,
    )

    data class Status(
        val engine: EngineId,
        val probability: Int,
        val decision: NumericalMetaBrain.Decision,
        val samples: Long,
        val modelVersion: Long,
        val lastUpdated: Long,
    )

    data class ValidationStats(
        val labels: Long = 0,
        val productionCorrect: Long = 0,
        val candidateCorrect: Long = 0,
        val productionBrierSum: Double = 0.0,
        val candidateBrierSum: Double = 0.0,
        val candidateTake: Long = 0,
        val candidateTakeWins: Long = 0,
        val candidateReject: Long = 0,
        val candidateRejectLosses: Long = 0,
    ) {
        val productionAccuracy: Double get() = if (labels == 0L) 0.0 else productionCorrect.toDouble() / labels
        val candidateAccuracy: Double get() = if (labels == 0L) 0.0 else candidateCorrect.toDouble() / labels
        val productionBrier: Double get() = if (labels == 0L) 1.0 else productionBrierSum / labels
        val candidateBrier: Double get() = if (labels == 0L) 1.0 else candidateBrierSum / labels
        val takePrecision: Double get() = if (candidateTake == 0L) 0.0 else candidateTakeWins.toDouble() / candidateTake
        val rejectPrecision: Double get() = if (candidateReject == 0L) 0.0 else candidateRejectLosses.toDouble() / candidateReject
    }

    data class LabReport(
        val initialized: Boolean,
        val persistent: Boolean,
        val gateEnabled: Boolean,
        val productionVersion: Long,
        val candidateVersion: Long,
        val productionSamples: Long,
        val candidateSamples: Long,
        val pendingLabels: Int,
        val validation: ValidationStats,
        val eligibleForPromotion: Boolean,
        val promotionReason: String,
        val lastSavedAt: Long,
        val lastPromotedAt: Long,
        val rollbackAvailable: Boolean,
    )

    private data class Pending(
        val features: NumericalMetaBrain.Features,
        val productionPrediction: NumericalMetaBrain.Prediction,
        val candidatePrediction: NumericalMetaBrain.Prediction,
        val side: PositionSide,
        val entrySpot: Double,
        val createdAt: Long,
        var bestDirectionalReturn: Double = 0.0,
        var worstDirectionalReturn: Double = 0.0,
    )

    private val production = newHistoricalBrain()
    private val candidate = newHistoricalBrain()
    private var rollbackState: NumericalMetaBrain.ModelState? = null
    private var validation = ValidationStats()
    private val pending = linkedMapOf<String, Pending>()
    private val lastRegistered = mutableMapOf<String, Long>()
    private val statusByEngine = mutableMapOf<EngineId, Status>()
    private var appContext: Context? = null
    private var initialized = false
    private var gateEnabled = false
    private var lastSavedAt = 0L
    private var lastPromotedAt = 0L
    private var labelsSinceSave = 0

    private fun newHistoricalBrain() = NumericalMetaBrain().apply {
        loadHistoricalPrior(HISTORICAL_PRIOR_WEIGHTS, HISTORICAL_PRIOR_BIAS, HISTORICAL_PRIOR_SAMPLES)
        setMode(NumericalMetaBrain.Mode.SHADOW)
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val prefs = prefs() ?: return
        readModel(prefs.getString(KEY_PRODUCTION, null))?.let { production.restore(it) }
        readModel(prefs.getString(KEY_CANDIDATE, null))?.let { candidate.restore(it) }
        readModel(prefs.getString(KEY_ROLLBACK, null))?.let { rollbackState = it }
        validation = ValidationStats(
            labels = prefs.getLong("v_labels", 0L),
            productionCorrect = prefs.getLong("v_pc", 0L),
            candidateCorrect = prefs.getLong("v_cc", 0L),
            productionBrierSum = prefs.getString("v_pb", null)?.toDoubleOrNull() ?: 0.0,
            candidateBrierSum = prefs.getString("v_cb", null)?.toDoubleOrNull() ?: 0.0,
            candidateTake = prefs.getLong("v_take", 0L),
            candidateTakeWins = prefs.getLong("v_take_w", 0L),
            candidateReject = prefs.getLong("v_reject", 0L),
            candidateRejectLosses = prefs.getLong("v_reject_l", 0L),
        )
        gateEnabled = prefs.getBoolean(KEY_GATE, false)
        lastSavedAt = prefs.getLong(KEY_LAST_SAVE, 0L)
        lastPromotedAt = prefs.getLong(KEY_LAST_PROMOTE, 0L)
        production.setMode(if (gateEnabled) NumericalMetaBrain.Mode.GATE else NumericalMetaBrain.Mode.SHADOW)
        candidate.setMode(NumericalMetaBrain.Mode.SHADOW)
        initialized = true
    }

    @Synchronized
    fun resetSession() {
        pending.clear()
        lastRegistered.clear()
    }

    @Synchronized
    fun observeSpot(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val sign = if (p.side == PositionSide.CE) 1.0 else -1.0
            val directional = sign * (price - p.entrySpot) / max(p.entrySpot, 1.0)
            p.bestDirectionalReturn = max(p.bestDirectionalReturn, directional)
            p.worstDirectionalReturn = minOf(p.worstDirectionalReturn, directional)
            val age = timestamp - p.createdAt
            val successBarrier = p.bestDirectionalReturn >= SUCCESS_RETURN
            val failureBarrier = p.worstDirectionalReturn <= -FAILURE_RETURN
            val timedOut = age >= LABEL_HORIZON_MS
            if (successBarrier || failureBarrier || timedOut) {
                val success = when {
                    successBarrier -> true
                    failureBarrier -> false
                    else -> directional >= TIMEOUT_SUCCESS_RETURN
                }
                scoreUnseenLabel(p.productionPrediction, p.candidatePrediction, success)
                val weight = if (successBarrier || failureBarrier) 1.25 else 0.75
                candidate.learn(p.features, success, weight)
                labelsSinceSave++
                iterator.remove()
                if (labelsSinceSave >= AUTO_SAVE_EVERY_LABELS) saveLocked()
            }
        }
    }

    private fun scoreUnseenLabel(
        productionPrediction: NumericalMetaBrain.Prediction,
        candidatePrediction: NumericalMetaBrain.Prediction,
        success: Boolean,
    ) {
        val y = if (success) 1.0 else 0.0
        val pc = predictedClass(productionPrediction.probabilitySuccess) == success
        val cc = predictedClass(candidatePrediction.probabilitySuccess) == success
        val candTake = candidatePrediction.decision == NumericalMetaBrain.Decision.TAKE
        val candReject = candidatePrediction.decision == NumericalMetaBrain.Decision.REJECT
        validation = validation.copy(
            labels = validation.labels + 1,
            productionCorrect = validation.productionCorrect + if (pc) 1 else 0,
            candidateCorrect = validation.candidateCorrect + if (cc) 1 else 0,
            productionBrierSum = validation.productionBrierSum + (productionPrediction.probabilitySuccess - y).pow(2),
            candidateBrierSum = validation.candidateBrierSum + (candidatePrediction.probabilitySuccess - y).pow(2),
            candidateTake = validation.candidateTake + if (candTake) 1 else 0,
            candidateTakeWins = validation.candidateTakeWins + if (candTake && success) 1 else 0,
            candidateReject = validation.candidateReject + if (candReject) 1 else 0,
            candidateRejectLosses = validation.candidateRejectLosses + if (candReject && !success) 1 else 0,
        )
    }

    private fun predictedClass(p: Double): Boolean = p >= 0.50

    @Synchronized
    fun decorate(
        engine: EngineId,
        raw: SignalSnapshot,
        spot: Double,
        timestamp: Long,
        directionScore: Double,
        entryQualityScore: Double,
        orderFlow: Double = 0.0,
        relativeActivity: Double = 1.0,
        oiImpulse: Double = 0.0,
        optionFlow: Double = 0.0,
        acceleration: Double = 0.0,
        extensionAtr: Double = 0.0,
        depthImbalance: Double = 0.0,
        micropricePressure: Double = 0.0,
        totalBookPressure: Double = 0.0,
        wallPressure: Double = 0.0,
        depthLevels: Double = 0.0,
    ): SignalSnapshot {
        val side = when {
            raw.trend == TrendDirection.BULLISH -> PositionSide.CE
            raw.trend == TrendDirection.BEARISH -> PositionSide.PE
            else -> return raw
        }
        val index = if (spot >= SENSEX_SPOT_CUTOFF) MarketIndex.SENSEX else MarketIndex.NIFTY
        val features = NumericalMetaBrain.Features(
            engine = engine,
            index = index,
            side = side,
            engineConfidence = raw.confidence.toDouble(),
            directionScore = directionScore.coerceIn(0.0, 60.0),
            entryQualityScore = entryQualityScore.coerceIn(0.0, 40.0),
            orderFlow = orderFlow,
            relativeActivity = relativeActivity,
            oiImpulse = oiImpulse,
            optionFlow = optionFlow,
            acceleration = acceleration,
            extensionAtr = extensionAtr,
            depthImbalance = depthImbalance,
            micropricePressure = micropricePressure,
            totalBookPressure = totalBookPressure,
            wallPressure = wallPressure,
            depthLevels = depthLevels,
            minutesFromOpen = minutesFromOpen(timestamp),
            recentEngineWinRate = 50.0,
            recentEngineProfitFactor = 1.0,
        )
        val prodPrediction = production.predict(features)
        val candPrediction = candidate.predict(features)
        statusByEngine[engine] = Status(
            engine = engine,
            probability = candPrediction.confidence,
            decision = candPrediction.decision,
            samples = candPrediction.samplesLearned,
            modelVersion = candPrediction.modelVersion,
            lastUpdated = timestamp,
        )

        if (raw.confidence >= CANDIDATE_CONFIDENCE && spot > 0.0) {
            val dedupeKey = "${engine.name}:${side.name}"
            val last = lastRegistered[dedupeKey] ?: 0L
            if (timestamp - last >= CANDIDATE_DEDUPE_MS) {
                lastRegistered[dedupeKey] = timestamp
                pending["$dedupeKey:$timestamp"] = Pending(features, prodPrediction, candPrediction, side, spot, timestamp)
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            }
        }

        val decisionText = candPrediction.decision.name
        val metaReason = "META ${if (gateEnabled) "GATE" else "SHADOW"} ${candPrediction.confidence}% $decisionText · learned ${candPrediction.samplesLearned} · v${candPrediction.modelVersion}"
        var decorated = raw.copy(
            reasons = (raw.reasons + metaReason).takeLast(10),
            setup = "${raw.setup} · AI ${candPrediction.confidence}%",
        )

        // Gate uses only the frozen PRODUCTION model. Candidate never directly controls orders.
        if (gateEnabled && raw.action != SignalAction.WAIT && prodPrediction.decision == NumericalMetaBrain.Decision.REJECT) {
            decorated = decorated.copy(
                action = SignalAction.WAIT,
                setup = "${raw.setup} · AI GATE REJECT ${prodPrediction.confidence}%",
                reasons = (decorated.reasons + "Production meta-model rejected entry").takeLast(10),
            )
        }
        return decorated
    }

    @Synchronized fun status(engine: EngineId): Status? = statusByEngine[engine]

    @Synchronized
    fun report(): LabReport {
        val (eligible, reason) = promotionEligibility()
        val ps = production.snapshot()
        val cs = candidate.snapshot()
        return LabReport(
            initialized = initialized,
            persistent = appContext != null,
            gateEnabled = gateEnabled,
            productionVersion = ps.modelVersion,
            candidateVersion = cs.modelVersion,
            productionSamples = ps.samplesLearned,
            candidateSamples = cs.samplesLearned,
            pendingLabels = pending.size,
            validation = validation,
            eligibleForPromotion = eligible,
            promotionReason = reason,
            lastSavedAt = lastSavedAt,
            lastPromotedAt = lastPromotedAt,
            rollbackAvailable = rollbackState != null,
        )
    }

    @Synchronized fun forceSave(): Boolean = saveLocked()

    @Synchronized
    fun resetCandidateLearning() {
        candidate.restore(production.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW))
        validation = ValidationStats()
        pending.clear()
        lastRegistered.clear()
        saveLocked()
    }

    @Synchronized
    fun promoteCandidate(): Pair<Boolean, String> {
        val (eligible, reason) = promotionEligibility()
        if (!eligible) return false to reason
        rollbackState = production.snapshot()
        production.restore(candidate.snapshot().copy(mode = if (gateEnabled) NumericalMetaBrain.Mode.GATE else NumericalMetaBrain.Mode.SHADOW))
        lastPromotedAt = System.currentTimeMillis()
        validation = ValidationStats()
        pending.clear()
        lastRegistered.clear()
        saveLocked()
        return true to "Candidate promoted to frozen production model"
    }

    @Synchronized
    fun rollbackProduction(): Boolean {
        val rollback = rollbackState ?: return false
        production.restore(rollback.copy(mode = if (gateEnabled) NumericalMetaBrain.Mode.GATE else NumericalMetaBrain.Mode.SHADOW))
        candidate.restore(production.snapshot().copy(mode = NumericalMetaBrain.Mode.SHADOW))
        rollbackState = null
        validation = ValidationStats()
        gateEnabled = false
        production.setMode(NumericalMetaBrain.Mode.SHADOW)
        saveLocked()
        return true
    }

    @Synchronized
    fun setGateEnabled(enabled: Boolean): Pair<Boolean, String> {
        if (enabled) {
            if (lastPromotedAt <= 0L) return false to "Promote a validated candidate before enabling AI gate"
            gateEnabled = true
            production.setMode(NumericalMetaBrain.Mode.GATE)
        } else {
            gateEnabled = false
            production.setMode(NumericalMetaBrain.Mode.SHADOW)
        }
        saveLocked()
        return true to if (gateEnabled) "Validated production AI gate enabled" else "AI returned to shadow mode"
    }

    private fun promotionEligibility(): Pair<Boolean, String> {
        if (validation.labels < MIN_VALIDATION_LABELS) return false to "Need ${MIN_VALIDATION_LABELS - validation.labels} more unseen labels"
        val accuracyGain = validation.candidateAccuracy - validation.productionAccuracy
        val brierGain = validation.productionBrier - validation.candidateBrier
        if (accuracyGain < MIN_ACCURACY_GAIN && brierGain < MIN_BRIER_GAIN) {
            return false to "Candidate has not beaten production accuracy/calibration"
        }
        if (validation.candidateTake >= MIN_ACTION_SAMPLES && validation.takePrecision < MIN_TAKE_PRECISION) {
            return false to "TAKE precision ${pct(validation.takePrecision)} below ${pct(MIN_TAKE_PRECISION)}"
        }
        if (validation.candidateReject >= MIN_ACTION_SAMPLES && validation.rejectPrecision < MIN_REJECT_PRECISION) {
            return false to "REJECT precision ${pct(validation.rejectPrecision)} below ${pct(MIN_REJECT_PRECISION)}"
        }
        return true to "PASS · candidate beats frozen production on unseen live labels"
    }

    private fun saveLocked(): Boolean {
        val prefs = prefs() ?: return false
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_PRODUCTION, writeModel(production.snapshot()))
            .putString(KEY_CANDIDATE, writeModel(candidate.snapshot()))
            .putString(KEY_ROLLBACK, rollbackState?.let(::writeModel))
            .putBoolean(KEY_GATE, gateEnabled)
            .putLong(KEY_LAST_SAVE, now)
            .putLong(KEY_LAST_PROMOTE, lastPromotedAt)
            .putLong("v_labels", validation.labels)
            .putLong("v_pc", validation.productionCorrect)
            .putLong("v_cc", validation.candidateCorrect)
            .putString("v_pb", validation.productionBrierSum.toString())
            .putString("v_cb", validation.candidateBrierSum.toString())
            .putLong("v_take", validation.candidateTake)
            .putLong("v_take_w", validation.candidateTakeWins)
            .putLong("v_reject", validation.candidateReject)
            .putLong("v_reject_l", validation.candidateRejectLosses)
            .apply()
        lastSavedAt = now
        labelsSinceSave = 0
        return true
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeModel(state: NumericalMetaBrain.ModelState): String = listOf(
        state.bias.toString(),
        state.samplesLearned.toString(),
        state.modelVersion.toString(),
        state.mode.name,
        state.weights.joinToString(","),
    ).joinToString("|")

    private fun readModel(raw: String?): NumericalMetaBrain.ModelState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val p = raw.split("|", limit = 5)
            val weights = p[4].split(',').map(String::toDouble).toDoubleArray()
            if (weights.size != NumericalMetaBrain.FEATURE_COUNT) return null
            NumericalMetaBrain.ModelState(
                weights = weights,
                bias = p[0].toDouble(),
                samplesLearned = p[1].toLong(),
                modelVersion = p[2].toLong(),
                mode = NumericalMetaBrain.Mode.valueOf(p[3]),
            )
        }.getOrNull()
    }

    private fun minutesFromOpen(timestamp: Long): Double {
        val totalMinutesUtc = (timestamp / 60_000L) % (24L * 60L)
        val ist = (totalMinutesUtc + 330L) % (24L * 60L)
        return (ist - (9L * 60L + 15L)).coerceAtLeast(0L).toDouble()
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100.0)

    private const val PREFS = "vardhani_meta_brain_v2"
    private const val KEY_PRODUCTION = "production"
    private const val KEY_CANDIDATE = "candidate"
    private const val KEY_ROLLBACK = "rollback"
    private const val KEY_GATE = "gate"
    private const val KEY_LAST_SAVE = "last_save"
    private const val KEY_LAST_PROMOTE = "last_promote"
    private const val SENSEX_SPOT_CUTOFF = 50_000.0
    private const val CANDIDATE_CONFIDENCE = 70
    private const val CANDIDATE_DEDUPE_MS = 60_000L
    private const val LABEL_HORIZON_MS = 5 * 60_000L
    private const val SUCCESS_RETURN = 0.0012
    private const val FAILURE_RETURN = 0.0008
    private const val TIMEOUT_SUCCESS_RETURN = 0.00045
    private const val MAX_PENDING = 256
    private const val AUTO_SAVE_EVERY_LABELS = 5
    private const val MIN_VALIDATION_LABELS = 100L
    private const val MIN_ACTION_SAMPLES = 15L
    private const val MIN_ACCURACY_GAIN = 0.015
    private const val MIN_BRIER_GAIN = 0.005
    private const val MIN_TAKE_PRECISION = 0.55
    private const val MIN_REJECT_PRECISION = 0.55
}
