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
 * Persistent VARDHANI numerical Meta Brain.
 *
 * V4 makes market identity explicit throughout live training. NIFTY spot updates can
 * only resolve NIFTY pending labels and SENSEX updates can only resolve SENSEX labels.
 * Dedupe/status/validation are market-scoped while Production/Candidate remain one
 * shared numerical model with MarketIndex encoded in the feature vector.
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

    enum class CandidateProfile(val title: String, val hyper: NumericalMetaBrain.HyperParameters) {
        BALANCED("Balanced", NumericalMetaBrain.HyperParameters(0.015, 0.0005, 0.66, 0.42)),
        FAST_ADAPT("Fast Adapt", NumericalMetaBrain.HyperParameters(0.030, 0.0004, 0.64, 0.43)),
        CONSERVATIVE("Conservative", NumericalMetaBrain.HyperParameters(0.010, 0.0010, 0.70, 0.38)),
        STRICT_FILTER("Strict Filter", NumericalMetaBrain.HyperParameters(0.012, 0.0008, 0.74, 0.36)),
        LOW_REG("Low Regularization", NumericalMetaBrain.HyperParameters(0.020, 0.00015, 0.67, 0.41)),
        HIGH_REG("High Regularization", NumericalMetaBrain.HyperParameters(0.018, 0.0020, 0.68, 0.40)),
    }

    data class Status(
        val engine: EngineId,
        val probability: Int,
        val decision: NumericalMetaBrain.Decision,
        val samples: Long,
        val modelVersion: Long,
        val lastUpdated: Long,
        val market: MarketIndex = MarketIndex.NIFTY,
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

    data class CandidateResult(
        val finishedAt: Long,
        val profile: CandidateProfile,
        val labels: Long,
        val candidateAccuracy: Double,
        val productionAccuracy: Double,
        val candidateBrier: Double,
        val productionBrier: Double,
        val takePrecision: Double,
        val rejectPrecision: Double,
        val passed: Boolean,
        val score: Double,
        val hyperParameters: NumericalMetaBrain.HyperParameters = profile.hyper,
        val adaptiveGeneration: Int = 0,
        val adaptive: Boolean = false,
        val niftyLabels: Long = 0L,
        val sensexLabels: Long = 0L,
    ) {
        val displayName: String
            get() = if (adaptive) "Adaptive G$adaptiveGeneration · ${profile.title}" else profile.title
    }

    data class LabReport(
        val initialized: Boolean,
        val persistent: Boolean,
        val gateEnabled: Boolean,
        val autoSearchEnabled: Boolean,
        val productionVersion: Long,
        val candidateVersion: Long,
        val productionSamples: Long,
        val candidateSamples: Long,
        val candidateProfile: CandidateProfile,
        val candidateHyperParameters: NumericalMetaBrain.HyperParameters,
        val pendingLabels: Int,
        val validation: ValidationStats,
        val eligibleForPromotion: Boolean,
        val promotionReason: String,
        val lastSavedAt: Long,
        val lastPromotedAt: Long,
        val rollbackAvailable: Boolean,
        val candidateHistory: List<CandidateResult>,
        val candidateName: String,
        val candidateAdaptive: Boolean,
        val candidateGeneration: Int,
        val bestArchivedScore: Double?,
        val candidateOrigin: String,
        val validationByMarket: Map<MarketIndex, ValidationStats> = emptyMap(),
        val pendingByMarket: Map<MarketIndex, Int> = emptyMap(),
    )

    private data class Pending(
        val index: MarketIndex,
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
    private val validationByMarket = mutableMapOf(
        MarketIndex.NIFTY to ValidationStats(),
        MarketIndex.SENSEX to ValidationStats(),
    )
    private val pending = linkedMapOf<String, Pending>()
    private val lastRegistered = mutableMapOf<String, Long>()
    private val statusByMarketEngine = mutableMapOf<Pair<MarketIndex, EngineId>, Status>()
    private val candidateHistory = mutableListOf<CandidateResult>()
    private var activeProfile = CandidateProfile.BALANCED
    private var activeHyper = CandidateProfile.BALANCED.hyper
    private var activeAdaptive = false
    private var activeAdaptiveGeneration = 0
    private var nextMutationIndex = 0
    private var candidateOrigin = ""
    private var autoSearchEnabled = false
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
        validation = readValidation(prefs, "v")
        validationByMarket[MarketIndex.NIFTY] = readValidation(prefs, "v_nifty")
        validationByMarket[MarketIndex.SENSEX] = readValidation(prefs, "v_sensex")
        activeProfile = runCatching { CandidateProfile.valueOf(prefs.getString(KEY_PROFILE, null) ?: "BALANCED") }
            .getOrDefault(CandidateProfile.BALANCED)
        activeAdaptive = prefs.getBoolean(KEY_ACTIVE_ADAPTIVE, false)
        activeAdaptiveGeneration = prefs.getInt(KEY_ACTIVE_GENERATION, 0).coerceAtLeast(0)
        nextMutationIndex = prefs.getInt(KEY_MUTATION_CURSOR, 0).coerceAtLeast(0)
        activeHyper = readHyper(prefs.getString(KEY_ACTIVE_HYPER, null)) ?: candidate.snapshot().hyperParameters
        activeHyper = AdaptiveCandidateSearch.bounded(activeHyper)
        candidateOrigin = prefs.getString(KEY_CANDIDATE_ORIGIN, "").orEmpty()
        autoSearchEnabled = prefs.getBoolean(KEY_AUTO_SEARCH, false)
        candidateHistory.clear()
        candidateHistory.addAll(readHistory(prefs.getString(KEY_HISTORY, null)))
        gateEnabled = prefs.getBoolean(KEY_GATE, false)
        lastSavedAt = prefs.getLong(KEY_LAST_SAVE, 0L)
        lastPromotedAt = prefs.getLong(KEY_LAST_PROMOTE, 0L)
        production.setMode(if (gateEnabled) NumericalMetaBrain.Mode.GATE else NumericalMetaBrain.Mode.SHADOW)
        candidate.setMode(NumericalMetaBrain.Mode.SHADOW)
        initialized = true
    }

    /** Clears all transient live labels. Model weights and validation remain intact. */
    @Synchronized
    fun resetSession() {
        pending.clear()
        lastRegistered.clear()
    }

    /** Clears only one market's transient state, preserving the other market. */
    @Synchronized
    fun resetSession(index: MarketIndex) {
        pending.entries.removeAll { it.value.index == index }
        val prefix = "${index.name}:"
        lastRegistered.keys.removeAll { it.startsWith(prefix) }
    }

    /** Legacy single-market overload retained for existing UI code. */
    @Synchronized
    fun observeSpot(price: Double, timestamp: Long) = observeSpot(inferMarket(price), price, timestamp)

    /** Resolve only labels belonging to the supplied market. */
    @Synchronized
    fun observeSpot(index: MarketIndex, price: Double, timestamp: Long) {
        if (price <= 0.0 || timestamp <= 0L) return
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val p = entry.value
            if (p.index != index || timestamp < p.createdAt) continue
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
                scoreUnseenLabel(index, p.productionPrediction, p.candidatePrediction, success)
                candidate.learn(p.features, success, if (successBarrier || failureBarrier) 1.25 else 0.75)
                labelsSinceSave++
                iterator.remove()
            }
        }
        if (labelsSinceSave >= AUTO_SAVE_EVERY_LABELS) saveLocked()
        maybeAutoRotateCandidate()
    }

    private fun scoreUnseenLabel(
        index: MarketIndex,
        prod: NumericalMetaBrain.Prediction,
        cand: NumericalMetaBrain.Prediction,
        success: Boolean,
    ) {
        validation = addValidation(validation, prod, cand, success)
        validationByMarket[index] = addValidation(validationByMarket[index] ?: ValidationStats(), prod, cand, success)
    }

    private fun addValidation(
        current: ValidationStats,
        prod: NumericalMetaBrain.Prediction,
        cand: NumericalMetaBrain.Prediction,
        success: Boolean,
    ): ValidationStats {
        val y = if (success) 1.0 else 0.0
        val pc = predictedClass(prod.probabilitySuccess) == success
        val cc = predictedClass(cand.probabilitySuccess) == success
        val candTake = cand.decision == NumericalMetaBrain.Decision.TAKE
        val candReject = cand.decision == NumericalMetaBrain.Decision.REJECT
        return current.copy(
            labels = current.labels + 1,
            productionCorrect = current.productionCorrect + if (pc) 1 else 0,
            candidateCorrect = current.candidateCorrect + if (cc) 1 else 0,
            productionBrierSum = current.productionBrierSum + (prod.probabilitySuccess - y).pow(2),
            candidateBrierSum = current.candidateBrierSum + (cand.probabilitySuccess - y).pow(2),
            candidateTake = current.candidateTake + if (candTake) 1 else 0,
            candidateTakeWins = current.candidateTakeWins + if (candTake && success) 1 else 0,
            candidateReject = current.candidateReject + if (candReject) 1 else 0,
            candidateRejectLosses = current.candidateRejectLosses + if (candReject && !success) 1 else 0,
        )
    }

    private fun predictedClass(p: Double): Boolean = p >= 0.50

    /** Explicit-market entry point used by dual-market live research. */
    @Synchronized
    fun decorate(
        index: MarketIndex,
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
        if (spot <= 0.0 || timestamp <= 0L) return raw
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
        statusByMarketEngine[index to engine] = Status(
            engine = engine,
            probability = candPrediction.confidence,
            decision = candPrediction.decision,
            samples = candPrediction.samplesLearned,
            modelVersion = candPrediction.modelVersion,
            lastUpdated = timestamp,
            market = index,
        )

        if (raw.confidence >= CANDIDATE_CONFIDENCE) {
            val dedupeKey = "${index.name}:${engine.name}:${side.name}"
            val last = lastRegistered[dedupeKey] ?: 0L
            if (timestamp - last >= CANDIDATE_DEDUPE_MS) {
                lastRegistered[dedupeKey] = timestamp
                pending["$dedupeKey:$timestamp"] = Pending(
                    index, features, prodPrediction, candPrediction, side, spot, timestamp,
                )
                trimPending(index)
            }
        }

        val metaReason = "META ${if (gateEnabled) "GATE" else "SHADOW"} ${index.name} ${candPrediction.confidence}% ${candPrediction.decision.name} · ${candidateName()} · learned ${candPrediction.samplesLearned}"
        var decorated = raw.copy(
            reasons = (raw.reasons + metaReason).takeLast(10),
            setup = "${raw.setup} · AI ${candPrediction.confidence}%",
        )
        val productionReject = gateEnabled && raw.confidence >= CANDIDATE_CONFIDENCE && prodPrediction.decision == NumericalMetaBrain.Decision.REJECT
        if (productionReject) {
            decorated = decorated.copy(
                action = SignalAction.WAIT,
                confidence = 0,
                trend = TrendDirection.NEUTRAL,
                entry = null,
                stopLoss = null,
                target = null,
                setup = "${raw.setup} · AI GATE REJECT ${prodPrediction.confidence}%",
                reasons = (decorated.reasons + "Production meta-model rejected ${index.name} candidate; downstream re-arm blocked").takeLast(10),
            )
        }
        return decorated
    }

    /** Legacy overload. New research code must pass MarketIndex explicitly. */
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
    ): SignalSnapshot = decorate(
        index = inferMarket(spot), engine = engine, raw = raw, spot = spot, timestamp = timestamp,
        directionScore = directionScore, entryQualityScore = entryQualityScore, orderFlow = orderFlow,
        relativeActivity = relativeActivity, oiImpulse = oiImpulse, optionFlow = optionFlow,
        acceleration = acceleration, extensionAtr = extensionAtr, depthImbalance = depthImbalance,
        micropricePressure = micropricePressure, totalBookPressure = totalBookPressure,
        wallPressure = wallPressure, depthLevels = depthLevels,
    )

    private fun trimPending(index: MarketIndex) {
        while (pending.values.count { it.index == index } > MAX_PENDING_PER_MARKET) {
            val key = pending.entries.firstOrNull { it.value.index == index }?.key ?: break
            pending.remove(key)
        }
        while (pending.size > MAX_PENDING_TOTAL) pending.remove(pending.keys.first())
    }

    @Synchronized
    fun status(engine: EngineId): Status? = statusByMarketEngine.values
        .filter { it.engine == engine }
        .maxByOrNull { it.lastUpdated }

    @Synchronized fun status(index: MarketIndex, engine: EngineId): Status? = statusByMarketEngine[index to engine]
    @Synchronized fun availableProfiles(): List<CandidateProfile> = CandidateProfile.entries.toList()
    @Synchronized fun productionSnapshotForResearch(): NumericalMetaBrain.ModelState = production.snapshot()

    @Synchronized
    fun installHistoricalCandidate(state: NumericalMetaBrain.ModelState, label: String): Pair<Boolean, String> {
        if (state.weights.size != NumericalMetaBrain.FEATURE_COUNT) return false to "Historical model feature count mismatch"
        activeHyper = AdaptiveCandidateSearch.bounded(state.hyperParameters)
        activeAdaptive = false
        activeAdaptiveGeneration = 0
        candidateOrigin = label.trim().take(120).ifBlank { "Historical WF Champion" }
        candidate.restore(state.copy(mode = NumericalMetaBrain.Mode.SHADOW, hyperParameters = activeHyper))
        resetLiveValidation()
        autoSearchEnabled = false
        saveLocked()
        return true to "$candidateOrigin installed as Candidate only · collect fresh NIFTY + SENSEX unseen labels before promotion"
    }

    @Synchronized
    fun report(): LabReport {
        val (eligible, reason) = promotionEligibility()
        val ps = production.snapshot()
        val cs = candidate.snapshot()
        val byMarket = MarketIndex.entries.associateWith { validationByMarket[it] ?: ValidationStats() }
        val pendingCounts = MarketIndex.entries.associateWith { market -> pending.values.count { it.index == market } }
        return LabReport(
            initialized = initialized,
            persistent = appContext != null,
            gateEnabled = gateEnabled,
            autoSearchEnabled = autoSearchEnabled,
            productionVersion = ps.modelVersion,
            candidateVersion = cs.modelVersion,
            productionSamples = ps.samplesLearned,
            candidateSamples = cs.samplesLearned,
            candidateProfile = activeProfile,
            candidateHyperParameters = cs.hyperParameters,
            pendingLabels = pending.size,
            validation = validation,
            eligibleForPromotion = eligible,
            promotionReason = reason,
            lastSavedAt = lastSavedAt,
            lastPromotedAt = lastPromotedAt,
            rollbackAvailable = rollbackState != null,
            candidateHistory = candidateHistory.sortedByDescending { it.score }.take(MAX_HISTORY),
            candidateName = candidateName(),
            candidateAdaptive = activeAdaptive,
            candidateGeneration = activeAdaptiveGeneration,
            bestArchivedScore = robustParent()?.score,
            candidateOrigin = candidateOrigin,
            validationByMarket = byMarket,
            pendingByMarket = pendingCounts,
        )
    }

    @Synchronized fun forceSave(): Boolean = saveLocked()

    @Synchronized
    fun setAutoSearchEnabled(enabled: Boolean): Pair<Boolean, String> {
        autoSearchEnabled = enabled
        saveLocked()
        return true to if (enabled) {
            "Adaptive candidate search enabled · evaluation waits for balanced NIFTY + SENSEX live evidence"
        } else {
            "Adaptive candidate search disabled"
        }
    }

    @Synchronized
    fun startCandidate(profile: CandidateProfile, archiveCurrent: Boolean = true): Pair<Boolean, String> {
        if (archiveCurrent && validation.labels > 0) archiveCurrentCandidate()
        activeProfile = profile
        activeHyper = AdaptiveCandidateSearch.bounded(profile.hyper)
        activeAdaptive = false
        activeAdaptiveGeneration = 0
        candidateOrigin = ""
        resetCandidateFromProduction(activeHyper)
        saveLocked()
        return true to "Started ${profile.title} seed candidate from frozen production baseline"
    }

    @Synchronized
    fun startNextCandidate(): Pair<Boolean, String> {
        val profiles = CandidateProfile.entries
        val next = profiles[(activeProfile.ordinal + 1) % profiles.size]
        return startCandidate(next, archiveCurrent = true)
    }

    @Synchronized fun evolveBestCandidate(): Pair<Boolean, String> = startAdaptiveCandidate(archiveCurrent = true)

    @Synchronized
    fun resetCandidateLearning() {
        candidateOrigin = ""
        resetCandidateFromProduction(activeHyper)
        saveLocked()
    }

    @Synchronized
    fun clearCandidateHistory() {
        candidateHistory.clear()
        saveLocked()
    }

    private fun resetCandidateFromProduction(hyper: NumericalMetaBrain.HyperParameters) {
        activeHyper = AdaptiveCandidateSearch.bounded(hyper)
        candidate.restore(
            production.snapshot().copy(
                mode = NumericalMetaBrain.Mode.SHADOW,
                hyperParameters = activeHyper,
            ),
        )
        resetLiveValidation()
    }

    private fun resetLiveValidation() {
        validation = ValidationStats()
        validationByMarket[MarketIndex.NIFTY] = ValidationStats()
        validationByMarket[MarketIndex.SENSEX] = ValidationStats()
        pending.clear()
        lastRegistered.clear()
        labelsSinceSave = 0
    }

    private fun startAdaptiveCandidate(archiveCurrent: Boolean): Pair<Boolean, String> {
        if (archiveCurrent && validation.labels > 0) archiveCurrentCandidate()
        val parent = robustParent()
        val parentHyper = parent?.hyperParameters ?: activeHyper
        val parentProfile = parent?.profile ?: activeProfile
        val nextGeneration = ((parent?.adaptiveGeneration ?: activeAdaptiveGeneration) + 1).coerceAtLeast(1)
        val seen = candidateHistory.map { AdaptiveCandidateSearch.signature(it.hyperParameters) }.toSet()
        val generated = AdaptiveCandidateSearch.next(parentHyper, nextGeneration, nextMutationIndex, seen)
        activeProfile = parentProfile
        activeHyper = generated.hyperParameters
        activeAdaptive = true
        activeAdaptiveGeneration = generated.generation
        nextMutationIndex = generated.mutationIndex + 1
        candidateOrigin = ""
        resetCandidateFromProduction(activeHyper)
        saveLocked()
        val source = parent?.displayName ?: "${parentProfile.title} seed"
        return true to "Started ${candidateName()} around best dual-market parent $source · Production remains frozen"
    }

    private fun robustParent(): CandidateResult? {
        val balanced = candidateHistory.filter {
            it.labels >= MIN_VALIDATION_LABELS &&
                it.niftyLabels >= MIN_MARKET_VALIDATION_LABELS &&
                it.sensexLabels >= MIN_MARKET_VALIDATION_LABELS
        }
        val validated = candidateHistory.filter { it.labels >= MIN_VALIDATION_LABELS }
        return (balanced.ifEmpty { validated.ifEmpty { candidateHistory } }).maxByOrNull { it.score }
    }

    private fun candidateName(): String = when {
        candidateOrigin.isNotBlank() -> candidateOrigin
        activeAdaptive -> "Adaptive G$activeAdaptiveGeneration · ${activeProfile.title}"
        else -> activeProfile.title
    }

    private fun archiveCurrentCandidate() {
        if (validation.labels <= 0) return
        val (passed, _) = promotionEligibility()
        val nifty = validationByMarket[MarketIndex.NIFTY] ?: ValidationStats()
        val sensex = validationByMarket[MarketIndex.SENSEX] ?: ValidationStats()
        val balancePenalty = if (validation.labels <= 0) 0.0 else
            kotlin.math.abs(nifty.labels - sensex.labels).toDouble() / validation.labels * 0.08
        val score = (validation.candidateAccuracy - validation.productionAccuracy) +
            (validation.productionBrier - validation.candidateBrier) +
            0.20 * (validation.takePrecision - 0.50) +
            0.10 * (validation.rejectPrecision - 0.50) - balancePenalty
        candidateHistory += CandidateResult(
            finishedAt = System.currentTimeMillis(),
            profile = activeProfile,
            labels = validation.labels,
            candidateAccuracy = validation.candidateAccuracy,
            productionAccuracy = validation.productionAccuracy,
            candidateBrier = validation.candidateBrier,
            productionBrier = validation.productionBrier,
            takePrecision = validation.takePrecision,
            rejectPrecision = validation.rejectPrecision,
            passed = passed,
            score = score,
            hyperParameters = candidate.snapshot().hyperParameters,
            adaptiveGeneration = activeAdaptiveGeneration,
            adaptive = activeAdaptive,
            niftyLabels = nifty.labels,
            sensexLabels = sensex.labels,
        )
        while (candidateHistory.size > MAX_HISTORY) candidateHistory.removeAt(0)
    }

    private fun maybeAutoRotateCandidate() {
        if (!autoSearchEnabled || !enoughBalancedEvidenceForEvaluation()) return
        val (eligible, _) = promotionEligibility()
        if (eligible) {
            autoSearchEnabled = false
            saveLocked()
        } else {
            startAdaptiveCandidate(archiveCurrent = true)
        }
    }

    private fun enoughBalancedEvidenceForEvaluation(): Boolean {
        if (validation.labels < AUTO_EVALUATE_LABELS) return false
        return MarketIndex.entries.all { (validationByMarket[it]?.labels ?: 0L) >= MIN_MARKET_VALIDATION_LABELS }
    }

    @Synchronized
    fun promoteCandidate(): Pair<Boolean, String> {
        val (eligible, reason) = promotionEligibility()
        if (!eligible) return false to reason
        archiveCurrentCandidate()
        rollbackState = production.snapshot()
        production.restore(candidate.snapshot().copy(mode = if (gateEnabled) NumericalMetaBrain.Mode.GATE else NumericalMetaBrain.Mode.SHADOW))
        lastPromotedAt = System.currentTimeMillis()
        resetLiveValidation()
        autoSearchEnabled = false
        saveLocked()
        return true to "${candidateName()} promoted to frozen production model after balanced NIFTY + SENSEX validation"
    }

    @Synchronized
    fun rollbackProduction(): Boolean {
        val rollback = rollbackState ?: return false
        production.restore(rollback.copy(mode = NumericalMetaBrain.Mode.SHADOW))
        candidateOrigin = ""
        resetCandidateFromProduction(activeHyper)
        rollbackState = null
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
        if (validation.labels < MIN_VALIDATION_LABELS) {
            return false to "Need ${MIN_VALIDATION_LABELS - validation.labels} more total unseen live labels"
        }
        for (market in MarketIndex.entries) {
            val m = validationByMarket[market] ?: ValidationStats()
            if (m.labels < MIN_MARKET_VALIDATION_LABELS) {
                return false to "${market.name} needs ${MIN_MARKET_VALIDATION_LABELS - m.labels} more unseen labels"
            }
            if (m.candidateTake < MIN_MARKET_ACTION_SAMPLES) {
                return false to "${market.name} needs ${MIN_MARKET_ACTION_SAMPLES - m.candidateTake} more TAKE decisions"
            }
            if (m.candidateReject < MIN_MARKET_ACTION_SAMPLES) {
                return false to "${market.name} needs ${MIN_MARKET_ACTION_SAMPLES - m.candidateReject} more REJECT decisions"
            }
            if (m.takePrecision < MIN_MARKET_ACTION_PRECISION) {
                return false to "${market.name} TAKE precision ${pct(m.takePrecision)} below ${pct(MIN_MARKET_ACTION_PRECISION)}"
            }
            if (m.rejectPrecision < MIN_MARKET_ACTION_PRECISION) {
                return false to "${market.name} REJECT precision ${pct(m.rejectPrecision)} below ${pct(MIN_MARKET_ACTION_PRECISION)}"
            }
            val marketAccGain = m.candidateAccuracy - m.productionAccuracy
            val marketBrierGain = m.productionBrier - m.candidateBrier
            if (marketAccGain < -MAX_MARKET_ACCURACY_REGRESSION && marketBrierGain < -MAX_MARKET_BRIER_REGRESSION) {
                return false to "${market.name} candidate materially regresses Production"
            }
        }
        if (validation.candidateTake < MIN_ACTION_SAMPLES) {
            return false to "Need ${MIN_ACTION_SAMPLES - validation.candidateTake} more TAKE decisions"
        }
        if (validation.candidateReject < MIN_ACTION_SAMPLES) {
            return false to "Need ${MIN_ACTION_SAMPLES - validation.candidateReject} more REJECT decisions"
        }
        val accuracyGain = validation.candidateAccuracy - validation.productionAccuracy
        val brierGain = validation.productionBrier - validation.candidateBrier
        if (accuracyGain < MIN_ACCURACY_GAIN && brierGain < MIN_BRIER_GAIN) {
            return false to "Candidate has not beaten Production accuracy/calibration"
        }
        if (validation.takePrecision < MIN_TAKE_PRECISION) {
            return false to "TAKE precision ${pct(validation.takePrecision)} below ${pct(MIN_TAKE_PRECISION)}"
        }
        if (validation.rejectPrecision < MIN_REJECT_PRECISION) {
            return false to "REJECT precision ${pct(validation.rejectPrecision)} below ${pct(MIN_REJECT_PRECISION)}"
        }
        return true to "PASS · balanced NIFTY + SENSEX Candidate beats frozen Production on unseen live labels"
    }

    private fun saveLocked(): Boolean {
        val prefs = prefs() ?: return false
        val now = System.currentTimeMillis()
        val edit = prefs.edit()
            .putString(KEY_PRODUCTION, writeModel(production.snapshot()))
            .putString(KEY_CANDIDATE, writeModel(candidate.snapshot()))
            .putString(KEY_ROLLBACK, rollbackState?.let(::writeModel))
            .putString(KEY_PROFILE, activeProfile.name)
            .putString(KEY_ACTIVE_HYPER, writeHyper(activeHyper))
            .putBoolean(KEY_ACTIVE_ADAPTIVE, activeAdaptive)
            .putInt(KEY_ACTIVE_GENERATION, activeAdaptiveGeneration)
            .putInt(KEY_MUTATION_CURSOR, nextMutationIndex)
            .putString(KEY_CANDIDATE_ORIGIN, candidateOrigin)
            .putString(KEY_HISTORY, writeHistory(candidateHistory))
            .putBoolean(KEY_AUTO_SEARCH, autoSearchEnabled)
            .putBoolean(KEY_GATE, gateEnabled)
            .putLong(KEY_LAST_SAVE, now)
            .putLong(KEY_LAST_PROMOTE, lastPromotedAt)
        writeValidation(edit, "v", validation)
        writeValidation(edit, "v_nifty", validationByMarket[MarketIndex.NIFTY] ?: ValidationStats())
        writeValidation(edit, "v_sensex", validationByMarket[MarketIndex.SENSEX] ?: ValidationStats())
        edit.apply()
        lastSavedAt = now
        labelsSinceSave = 0
        return true
    }

    private fun readValidation(prefs: android.content.SharedPreferences, prefix: String): ValidationStats = ValidationStats(
        labels = prefs.getLong("${prefix}_labels", 0L),
        productionCorrect = prefs.getLong("${prefix}_pc", 0L),
        candidateCorrect = prefs.getLong("${prefix}_cc", 0L),
        productionBrierSum = prefs.getString("${prefix}_pb", null)?.toDoubleOrNull() ?: 0.0,
        candidateBrierSum = prefs.getString("${prefix}_cb", null)?.toDoubleOrNull() ?: 0.0,
        candidateTake = prefs.getLong("${prefix}_take", 0L),
        candidateTakeWins = prefs.getLong("${prefix}_take_w", 0L),
        candidateReject = prefs.getLong("${prefix}_reject", 0L),
        candidateRejectLosses = prefs.getLong("${prefix}_reject_l", 0L),
    )

    private fun writeValidation(
        edit: android.content.SharedPreferences.Editor,
        prefix: String,
        v: ValidationStats,
    ) {
        edit.putLong("${prefix}_labels", v.labels)
            .putLong("${prefix}_pc", v.productionCorrect)
            .putLong("${prefix}_cc", v.candidateCorrect)
            .putString("${prefix}_pb", v.productionBrierSum.toString())
            .putString("${prefix}_cb", v.candidateBrierSum.toString())
            .putLong("${prefix}_take", v.candidateTake)
            .putLong("${prefix}_take_w", v.candidateTakeWins)
            .putLong("${prefix}_reject", v.candidateReject)
            .putLong("${prefix}_reject_l", v.candidateRejectLosses)
    }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writeModel(s: NumericalMetaBrain.ModelState): String = listOf(
        s.bias, s.samplesLearned, s.modelVersion, s.mode.name,
        s.hyperParameters.learningRate, s.hyperParameters.l2,
        s.hyperParameters.takeThreshold, s.hyperParameters.rejectThreshold,
        s.weights.joinToString(","),
    ).joinToString("|")

    private fun readModel(raw: String?): NumericalMetaBrain.ModelState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val p = raw.split("|")
            if (p.size >= 9) {
                val weights = p[8].split(',').map(String::toDouble).toDoubleArray()
                if (weights.size != NumericalMetaBrain.FEATURE_COUNT) return null
                NumericalMetaBrain.ModelState(
                    weights = weights,
                    bias = p[0].toDouble(),
                    samplesLearned = p[1].toLong(),
                    modelVersion = p[2].toLong(),
                    mode = NumericalMetaBrain.Mode.valueOf(p[3]),
                    hyperParameters = NumericalMetaBrain.HyperParameters(
                        p[4].toDouble(), p[5].toDouble(), p[6].toDouble(), p[7].toDouble(),
                    ),
                )
            } else {
                val old = raw.split("|", limit = 5)
                val weights = old[4].split(',').map(String::toDouble).toDoubleArray()
                if (weights.size != NumericalMetaBrain.FEATURE_COUNT) return null
                NumericalMetaBrain.ModelState(
                    weights, old[0].toDouble(), old[1].toLong(), old[2].toLong(),
                    NumericalMetaBrain.Mode.valueOf(old[3]),
                )
            }
        }.getOrNull()
    }

    private fun writeHyper(h: NumericalMetaBrain.HyperParameters): String =
        listOf(h.learningRate, h.l2, h.takeThreshold, h.rejectThreshold).joinToString(",")

    private fun readHyper(raw: String?): NumericalMetaBrain.HyperParameters? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val p = raw.split(',')
            if (p.size != 4) return null
            AdaptiveCandidateSearch.bounded(
                NumericalMetaBrain.HyperParameters(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble()),
            )
        }.getOrNull()
    }

    private fun writeHistory(items: List<CandidateResult>): String =
        items.takeLast(MAX_HISTORY).joinToString(";") { r ->
            listOf(
                r.finishedAt, r.profile.name, r.labels,
                r.candidateAccuracy, r.productionAccuracy, r.candidateBrier, r.productionBrier,
                r.takePrecision, r.rejectPrecision, r.passed, r.score,
                r.hyperParameters.learningRate, r.hyperParameters.l2,
                r.hyperParameters.takeThreshold, r.hyperParameters.rejectThreshold,
                r.adaptiveGeneration, r.adaptive, r.niftyLabels, r.sensexLabels,
            ).joinToString(",")
        }

    private fun readHistory(raw: String?): List<CandidateResult> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { row ->
            runCatching {
                val p = row.split(',')
                val profile = CandidateProfile.valueOf(p[1])
                val hyper = if (p.size >= 17) {
                    AdaptiveCandidateSearch.bounded(
                        NumericalMetaBrain.HyperParameters(
                            p[11].toDouble(), p[12].toDouble(), p[13].toDouble(), p[14].toDouble(),
                        ),
                    )
                } else profile.hyper
                CandidateResult(
                    finishedAt = p[0].toLong(),
                    profile = profile,
                    labels = p[2].toLong(),
                    candidateAccuracy = p[3].toDouble(),
                    productionAccuracy = p[4].toDouble(),
                    candidateBrier = p[5].toDouble(),
                    productionBrier = p[6].toDouble(),
                    takePrecision = p[7].toDouble(),
                    rejectPrecision = p[8].toDouble(),
                    passed = p[9].toBoolean(),
                    score = p[10].toDouble(),
                    hyperParameters = hyper,
                    adaptiveGeneration = if (p.size >= 17) p[15].toInt() else 0,
                    adaptive = if (p.size >= 17) p[16].toBoolean() else false,
                    niftyLabels = if (p.size >= 19) p[17].toLong() else 0L,
                    sensexLabels = if (p.size >= 19) p[18].toLong() else 0L,
                )
            }.getOrNull()
        }
    }

    private fun minutesFromOpen(timestamp: Long): Double {
        val totalMinutesUtc = (timestamp / 60_000L) % (24L * 60L)
        val ist = (totalMinutesUtc + 330L) % (24L * 60L)
        return (ist - (9L * 60L + 15L)).coerceAtLeast(0L).toDouble()
    }

    private fun inferMarket(spot: Double): MarketIndex =
        if (spot >= SENSEX_SPOT_CUTOFF) MarketIndex.SENSEX else MarketIndex.NIFTY

    private fun pct(v: Double) = "%.1f%%".format(v * 100.0)

    private const val PREFS = "vardhani_meta_brain_v3"
    private const val KEY_PRODUCTION = "production"
    private const val KEY_CANDIDATE = "candidate"
    private const val KEY_ROLLBACK = "rollback"
    private const val KEY_GATE = "gate"
    private const val KEY_PROFILE = "candidate_profile"
    private const val KEY_ACTIVE_HYPER = "active_candidate_hyper"
    private const val KEY_ACTIVE_ADAPTIVE = "active_candidate_adaptive"
    private const val KEY_ACTIVE_GENERATION = "active_candidate_generation"
    private const val KEY_MUTATION_CURSOR = "adaptive_mutation_cursor"
    private const val KEY_CANDIDATE_ORIGIN = "candidate_origin"
    private const val KEY_HISTORY = "candidate_history"
    private const val KEY_AUTO_SEARCH = "auto_candidate_search"
    private const val KEY_LAST_SAVE = "last_save"
    private const val KEY_LAST_PROMOTE = "last_promote"
    private const val SENSEX_SPOT_CUTOFF = 50_000.0
    private const val CANDIDATE_CONFIDENCE = 70
    private const val CANDIDATE_DEDUPE_MS = 60_000L
    private const val LABEL_HORIZON_MS = 5 * 60_000L
    private const val SUCCESS_RETURN = 0.0012
    private const val FAILURE_RETURN = 0.0008
    private const val TIMEOUT_SUCCESS_RETURN = 0.00045
    private const val MAX_PENDING_PER_MARKET = 256
    private const val MAX_PENDING_TOTAL = 512
    private const val AUTO_SAVE_EVERY_LABELS = 5
    private const val AUTO_EVALUATE_LABELS = 150L
    private const val MAX_HISTORY = 24
    private const val MIN_VALIDATION_LABELS = 150L
    private const val MIN_MARKET_VALIDATION_LABELS = 50L
    private const val MIN_ACTION_SAMPLES = 20L
    private const val MIN_MARKET_ACTION_SAMPLES = 5L
    private const val MIN_ACCURACY_GAIN = 0.015
    private const val MIN_BRIER_GAIN = 0.005
    private const val MIN_TAKE_PRECISION = 0.55
    private const val MIN_REJECT_PRECISION = 0.55
    private const val MIN_MARKET_ACTION_PRECISION = 0.50
    private const val MAX_MARKET_ACCURACY_REGRESSION = 0.01
    private const val MAX_MARKET_BRIER_REGRESSION = 0.003
}
