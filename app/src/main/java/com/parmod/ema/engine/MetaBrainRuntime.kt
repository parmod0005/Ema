package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Process-local runtime for the Numerical Meta Brain.
 *
 * The production model runs in SHADOW mode in this build: it scores candidates and learns from
 * delayed live outcomes, but it does not override E1/E2/E3 actions. The starting prior was fitted
 * offline from the historical V8 NIFTY option-trade ledger (71 real option outcomes). Features that
 * do not exist in that historical ledger start neutral and are learned from live data.
 */
object MetaBrainRuntime {
    data class Status(
        val engine: EngineId,
        val probability: Int,
        val decision: NumericalMetaBrain.Decision,
        val samples: Long,
        val modelVersion: Long,
        val lastUpdated: Long,
    )

    private data class Pending(
        val key: String,
        val features: NumericalMetaBrain.Features,
        val side: PositionSide,
        val entrySpot: Double,
        val createdAt: Long,
        var bestDirectionalReturn: Double = 0.0,
        var worstDirectionalReturn: Double = 0.0,
    )

    private val brain = NumericalMetaBrain().apply {
        loadHistoricalPrior(HISTORICAL_PRIOR_WEIGHTS, HISTORICAL_PRIOR_BIAS, HISTORICAL_PRIOR_SAMPLES)
        setMode(NumericalMetaBrain.Mode.SHADOW)
    }
    private val pending = linkedMapOf<String, Pending>()
    private val lastRegistered = mutableMapOf<String, Long>()
    private val statusByEngine = mutableMapOf<EngineId, Status>()

    @Synchronized
    fun resetSession() {
        pending.clear()
        lastRegistered.clear()
    }

    /** Called on every accepted underlying tick. */
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
                val weight = when {
                    successBarrier || failureBarrier -> 1.25
                    else -> 0.75
                }
                brain.learn(p.features, success, weight)
                iterator.remove()
            }
        }
    }

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
        val minutes = minutesFromOpen(timestamp)
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
            minutesFromOpen = minutes,
            recentEngineWinRate = 50.0,
            recentEngineProfitFactor = 1.0,
        )
        val prediction = brain.predict(features)
        statusByEngine[engine] = Status(
            engine,
            prediction.confidence,
            prediction.decision,
            prediction.samplesLearned,
            prediction.modelVersion,
            timestamp,
        )

        // Learn from BUY signals and strong rejected/waiting candidates too. Dedupe prevents a
        // 250 ms signal loop from creating hundreds of copies of the same market state.
        if (raw.confidence >= CANDIDATE_CONFIDENCE && spot > 0.0) {
            val dedupeKey = "${engine.name}:${side.name}"
            val last = lastRegistered[dedupeKey] ?: 0L
            if (timestamp - last >= CANDIDATE_DEDUPE_MS) {
                lastRegistered[dedupeKey] = timestamp
                val id = "$dedupeKey:$timestamp"
                pending[id] = Pending(id, features, side, spot, timestamp)
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            }
        }

        val decisionText = when (prediction.decision) {
            NumericalMetaBrain.Decision.TAKE -> "TAKE"
            NumericalMetaBrain.Decision.CAUTION -> "CAUTION"
            NumericalMetaBrain.Decision.REJECT -> "REJECT"
        }
        val metaReason = "META SHADOW ${prediction.confidence}% $decisionText · learned ${prediction.samplesLearned} · v${prediction.modelVersion}"
        return raw.copy(
            reasons = (raw.reasons + metaReason).takeLast(10),
            setup = "${raw.setup} · AI ${prediction.confidence}%",
        )
    }

    @Synchronized fun status(engine: EngineId): Status? = statusByEngine[engine]

    private fun minutesFromOpen(timestamp: Long): Double {
        // Timestamp is epoch millis; trading day length normalization only needs local intraday phase.
        val totalMinutesUtc = (timestamp / 60_000L) % (24L * 60L)
        // IST = UTC+330 minutes. Normalize to 09:15 IST.
        val ist = (totalMinutesUtc + 330L) % (24L * 60L)
        return (ist - (9L * 60L + 15L)).coerceAtLeast(0L).toDouble()
    }

    private const val HISTORICAL_PRIOR_SAMPLES = 71L
    private const val HISTORICAL_PRIOR_BIAS = 0.2001902471
    private val HISTORICAL_PRIOR_WEIGHTS = doubleArrayOf(
        -0.0001358893, -0.1465515448, 0.0, -0.3469553189, -0.1191122290,
        -0.1191122290, 0.0036725290, 0.0, -0.0000452964, 0.0,
        0.0, 0.0, -0.0000271779, 0.0, 0.0,
        0.0, 0.0, 0.0, -0.2276839730, -0.0000679447,
        -0.0000452964,
    )

    private const val SENSEX_SPOT_CUTOFF = 50_000.0
    private const val CANDIDATE_CONFIDENCE = 70
    private const val CANDIDATE_DEDUPE_MS = 60_000L
    private const val LABEL_HORIZON_MS = 5 * 60_000L
    private const val SUCCESS_RETURN = 0.0012
    private const val FAILURE_RETURN = 0.0008
    private const val TIMEOUT_SUCCESS_RETURN = 0.00045
    private const val MAX_PENDING = 256
}
