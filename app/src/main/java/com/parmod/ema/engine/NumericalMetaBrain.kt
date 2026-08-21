package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import kotlin.math.exp
import kotlin.math.min

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
            // Probability cutoffs are policy parameters, not a statement that profitable
            // trades must have >50% win probability. Historical research calibrates these
            // cutoffs on development-only cost-adjusted outcomes. Live/manual profiles can
            // still remain conservative at their configured values.
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
            depthImalanceSafe(depthImbalance),
            micropricePressure.coerceIn(-1.0, 1.0),
            totalBookPressure.coerceIn(-1.0, 1.0),
            wallPressure.coerceIn(-1.0, 1.0),
            (depthLevels / 30.0).coerceIn(0.0, 1.0),
            (minutesFromOpen / 375.0).coerceIn(0.0, 1.2),
            (recentEngineWinRate / 100.0).coerceIn(0.0, 1.0),
            min(recentEngineProfitFactor, 3.0) / 3.0,
        )

        private fun depthImalanceSafe(value: Double): Double = value.coerceIn(-1.0, 1.0)
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
    var mode: Mode = Mode.SHADOW
        private set

    fun setMode(value: Mode) { mode = value }

    fun configure(value: HyperParameters, bumpVersion: Boolean = true) {
        hyperParameters = value.sanitized()
        if (bumpVersion) version++
    }

    fun currentHyperParameters(): HyperParameters = hyperParameters

    fun loadHistoricalPrior(priorWeights: DoubleArray, priorBias: Double, priorSamples: Long) {
        require(priorWeights.size == FEATURE_COUNT)
        priorWeights.copyInto(weights)
        bias = priorBias
        learned = priorSamples.coerceAtLeast(0L)
        version++
    }

    fun restore(state: ModelState) {
        require(state.weights.size == FEATURE_COUNT)
        state.weights.copyInto(weights)
        bias = state.bias.coerceIn(-8.0, 8.0)
        learned = state.samplesLearned.coerceAtLeast(0L)
        version = state.modelVersion.coerceAtLeast(1L)
        mode = state.mode
        hyperParameters = state.hyperParameters.sanitized()
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
        val error = (y - p) * sampleWeight.coerceIn(0.1, 5.0)
        for (i in weights.indices) {
            weights[i] += hyperParameters.learningRate * (error * x[i] - hyperParameters.l2 * weights[i])
            weights[i] = weights[i].coerceIn(-8.0, 8.0)
        }
        bias = (bias + hyperParameters.learningRate * error).coerceIn(-8.0, 8.0)
        learned++
        if (learned % 100L == 0L) version++
    }

    companion object {
        const val FEATURE_COUNT = 21
        const val DEFAULT_LEARNING_RATE = 0.015
        const val DEFAULT_L2 = 0.0005
        const val DEFAULT_TAKE_THRESHOLD = 0.66
        const val DEFAULT_REJECT_THRESHOLD = 0.42

        private fun sigmoid(value: Double): Double = when {
            value >= 35.0 -> 1.0
            value <= -35.0 -> 0.0
            else -> 1.0 / (1.0 + exp(-value))
        }
    }
}
