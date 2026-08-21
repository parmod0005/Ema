package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/** Local numerical meta-model used for fast on-device inference and online learning. */
class NumericalMetaBrain(
    learningRate: Double = DEFAULT_LEARNING_RATE,
    l2: Double = DEFAULT_L2,
    takeThreshold: Double = DEFAULT_TAKE_THRESHOLD,
    rejectThreshold: Double = DEFAULT_REJECT_THRESHOLD,
) {
    enum class Mode { SHADOW, GATE }
    enum class Decision { TAKE, CAUTION, REJECT }

    data class HyperParameters(
        val learningRate: Double = DEFAULT_LEARNING_RATE,
        val l2: Double = DEFAULT_L2,
        val takeThreshold: Double = DEFAULT_TAKE_THRESHOLD,
        val rejectThreshold: Double = DEFAULT_REJECT_THRESHOLD,
    ) {
        fun sanitized(): HyperParameters {
            val take = takeThreshold.coerceIn(0.25, 0.90)
            val reject = rejectThreshold.coerceIn(0.05, 0.60).coerceAtMost(take - 0.05)
            return copy(
                learningRate = learningRate.coerceIn(0.001, 0.10),
                l2 = l2.coerceIn(0.0, 0.02),
                takeThreshold = take,
                rejectThreshold = reject,
            )
        }
    }

    data class CausalExtras(
        val premiumReturn1: Double = 0.0,
        val premiumReturn3: Double = 0.0,
        val premiumReturn5: Double = 0.0,
        val premiumReturn15: Double = 0.0,
        val emaSpread: Double = 0.0,
        val emaSlope: Double = 0.0,
        val zlemaSpread: Double = 0.0,
        val rsi: Double = 50.0,
        val macdHistogram: Double = 0.0,
        val atrRatio: Double = 1.0,
        val bbPosition: Double = 0.0,
        val bbWidth: Double = 0.0,
        val bodyRatio: Double = 0.0,
        val wickSkew: Double = 0.0,
        val volumeAcceleration: Double = 0.0,
        val oiAcceleration: Double = 0.0,
        val spotReturn3: Double = 0.0,
        val optionSpotRelative: Double = 0.0,
        val moneynessSteps: Double = 0.0,
        val daysToExpiry: Double = 0.0,
        val realizedVolatility: Double = 0.0,
        val momentumPersistence: Double = 0.0,
    )

    data class Features(
        val engine: EngineId,
        val index: MarketIndex,
        val side: PositionSide,
        val engineConfidence: Double,
        val directionScore: Double,
        val entryQualityScore: Double,
        val orderFlow: Double,
        val relativeActivity: Double,
        val oiImpulse: Double,
        val optionFlow: Double,
        val acceleration: Double,
        val extensionAtr: Double,
        val depthImbalance: Double,
        val micropricePressure: Double,
        val totalBookPressure: Double,
        val wallPressure: Double,
        val depthLevels: Double,
        val minutesFromOpen: Double,
        val recentEngineWinRate: Double,
        val recentEngineProfitFactor: Double,
        val causal: CausalExtras = consumeThreadCausalExtras(),
    ) {
        fun vector(): DoubleArray = doubleArrayOf(
            1.0,
            engine.ordinal / 2.0,
            index.ordinal.toDouble(),
            if (side == PositionSide.CE) 1.0 else -1.0,
            engineConfidence / 100.0,
            directionScore / 60.0,
            entryQualityScore / 40.0,
            orderFlow.coerceIn(-1.0, 1.0),
            (relativeActivity / 3.0).coerceIn(0.0, 2.0),
            oiImpulse.coerceIn(-1.0, 1.0),
            optionFlow.coerceIn(-1.0, 1.0),
            acceleration.coerceIn(-3.0, 3.0) / 3.0,
            (extensionAtr / 6.0).coerceIn(0.0, 2.0),
            depthImbalance.coerceIn(-1.0, 1.0),
            micropricePressure.coerceIn(-1.0, 1.0),
            totalBookPressure.coerceIn(-1.0, 1.0),
            wallPressure.coerceIn(-1.0, 1.0),
            (depthLevels / 30.0).coerceIn(0.0, 1.0),
            (minutesFromOpen / 375.0).coerceIn(0.0, 1.2),
            (recentEngineWinRate / 100.0).coerceIn(0.0, 1.0),
            min(recentEngineProfitFactor, 3.0) / 3.0,
            causal.premiumReturn1.coerceIn(-0.50, 0.50) * 2.0,
            causal.premiumReturn3.coerceIn(-0.75, 0.75) / 0.75,
            causal.premiumReturn5.coerceIn(-1.00, 1.00),
            causal.premiumReturn15.coerceIn(-1.50, 1.50) / 1.50,
            causal.emaSpread.coerceIn(-0.25, 0.25) * 4.0,
            causal.emaSlope.coerceIn(-0.15, 0.15) / 0.15,
            causal.zlemaSpread.coerceIn(-0.25, 0.25) * 4.0,
            ((causal.rsi - 50.0) / 50.0).coerceIn(-1.0, 1.0),
            causal.macdHistogram.coerceIn(-0.20, 0.20) * 5.0,
            ((causal.atrRatio - 1.0) / 2.0).coerceIn(-1.0, 1.0),
            causal.bbPosition.coerceIn(-2.0, 2.0) / 2.0,
            causal.bbWidth.coerceIn(0.0, 1.0),
            causal.bodyRatio.coerceIn(0.0, 1.0),
            causal.wickSkew.coerceIn(-1.0, 1.0),
            causal.volumeAcceleration.coerceIn(-3.0, 3.0) / 3.0,
            causal.oiAcceleration.coerceIn(-3.0, 3.0) / 3.0,
            causal.spotReturn3.coerceIn(-0.05, 0.05) / 0.05,
            causal.optionSpotRelative.coerceIn(-0.50, 0.50) * 2.0,
            (causal.moneynessSteps / 8.0).coerceIn(-1.5, 1.5),
            (causal.daysToExpiry / 30.0).coerceIn(0.0, 2.0),
            causal.realizedVolatility.coerceIn(0.0, 0.25) * 4.0,
            causal.momentumPersistence.coerceIn(-1.0, 1.0),
        )
    }

    data class Prediction(
        val probabilitySuccess: Double,
        val confidence: Int,
        val decision: Decision,
        val modelVersion: Long,
        val samplesLearned: Long,
        val mode: Mode,
    )

    data class ModelState(
        val weights: DoubleArray,
        val bias: Double,
        val samplesLearned: Long,
        val modelVersion: Long,
        val mode: Mode,
        val hyperParameters: HyperParameters = HyperParameters(),
    )

    private val weights = DoubleArray(FEATURE_COUNT)
    private var bias = -0.05
    private var learned = 0L
    private var version = 1L
    private var hyperParameters = HyperParameters(learningRate, l2, takeThreshold, rejectThreshold).sanitized()
    private var sessionSuccesses = 0L
    private var sessionFailures = 0L

    var mode: Mode = Mode.SHADOW
        private set

    fun setMode(value: Mode) { mode = value }

    fun configure(value: HyperParameters, bumpVersion: Boolean = true) {
        hyperParameters = value.sanitized()
        if (bumpVersion) version++
    }

    fun currentHyperParameters(): HyperParameters = hyperParameters

    fun loadHistoricalPrior(priorWeights: DoubleArray, priorBias: Double, priorSamples: Long) {
        require(priorWeights.isNotEmpty() && priorWeights.size <= FEATURE_COUNT)
        weights.fill(0.0)
        priorWeights.copyInto(weights, endIndex = priorWeights.size)
        bias = priorBias
        learned = priorSamples.coerceAtLeast(0L)
        version++
        resetBalanceCounters()
    }

    fun restore(state: ModelState) {
        require(state.weights.isNotEmpty() && state.weights.size <= FEATURE_COUNT)
        weights.fill(0.0)
        state.weights.copyInto(weights, endIndex = state.weights.size)
        bias = state.bias.coerceIn(-8.0, 8.0)
        learned = state.samplesLearned.coerceAtLeast(0L)
        version = state.modelVersion.coerceAtLeast(1L)
        mode = state.mode
        hyperParameters = state.hyperParameters.sanitized()
        resetBalanceCounters()
    }

    fun snapshot(): ModelState = ModelState(weights.copyOf(), bias, learned, version, mode, hyperParameters)

    fun predict(features: Features): Prediction {
        val x = features.vector()
        var z = bias
        for (i in x.indices) z += weights[i] * x[i]
        val p = sigmoid(z)
        val decision = when {
            p >= hyperParameters.takeThreshold -> Decision.TAKE
            p <= hyperParameters.rejectThreshold -> Decision.REJECT
            else -> Decision.CAUTION
        }
        return Prediction(p, (p * 100.0).toInt().coerceIn(0, 100), decision, version, learned, mode)
    }

    fun learn(features: Features, success: Boolean, sampleWeight: Double = 1.0) {
        val x = features.vector()
        val p = predict(features).probabilitySuccess
        val y = if (success) 1.0 else 0.0
        val classWeight = adaptiveClassWeight(success)
        val effectiveWeight = (sampleWeight.coerceIn(0.1, 5.0) * classWeight).coerceIn(0.1, 5.0)
        val error = (y - p) * effectiveWeight
        for (i in weights.indices) {
            weights[i] += hyperParameters.learningRate * (error * x[i] - hyperParameters.l2 * weights[i])
            weights[i] = weights[i].coerceIn(-8.0, 8.0)
        }
        bias = (bias + hyperParameters.learningRate * error).coerceIn(-8.0, 8.0)
        if (success) sessionSuccesses++ else sessionFailures++
        learned++
        if (learned % 100L == 0L) version++
    }

    private fun adaptiveClassWeight(success: Boolean): Double {
        val p = sessionSuccesses + BALANCE_SMOOTHING
        val n = sessionFailures + BALANCE_SMOOTHING
        val total = p + n
        val inverse = if (success) total.toDouble() / (2.0 * p) else total.toDouble() / (2.0 * n)
        return sqrt(inverse).coerceIn(MIN_CLASS_WEIGHT, MAX_CLASS_WEIGHT)
    }

    private fun resetBalanceCounters() {
        sessionSuccesses = 0L
        sessionFailures = 0L
    }

    companion object {
        const val LEGACY_FEATURE_COUNT = 21
        const val FEATURE_COUNT = 43
        const val FEATURE_SCHEMA_VERSION = 2
        const val DEFAULT_LEARNING_RATE = 0.015
        const val DEFAULT_L2 = 0.0005
        const val DEFAULT_TAKE_THRESHOLD = 0.66
        const val DEFAULT_REJECT_THRESHOLD = 0.42

        private val threadCausal = ThreadLocal<CausalExtras?>()

        /** Set only for one synchronous historical sample; Features consumes and clears it. */
        fun setThreadCausalExtras(value: CausalExtras) { threadCausal.set(value) }
        fun clearThreadCausalExtras() { threadCausal.remove() }
        private fun consumeThreadCausalExtras(): CausalExtras {
            val value = threadCausal.get() ?: CausalExtras()
            threadCausal.remove()
            return value
        }

        private const val BALANCE_SMOOTHING = 12L
        private const val MIN_CLASS_WEIGHT = 0.70
        private const val MAX_CLASS_WEIGHT = 1.80

        private fun sigmoid(value: Double): Double = when {
            value >= 35.0 -> 1.0
            value <= -35.0 -> 0.0
            else -> 1.0 / (1.0 + exp(-value))
        }
    }
}
