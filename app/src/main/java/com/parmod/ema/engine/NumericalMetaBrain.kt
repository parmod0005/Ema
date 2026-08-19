package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Local numerical meta-model used for fast on-device inference and online learning. */
class NumericalMetaBrain(
    private val learningRate: Double = 0.015,
    private val l2: Double = 0.0005,
) {
    enum class Mode { SHADOW, GATE }
    enum class Decision { TAKE, CAUTION, REJECT }

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
            depthImbalance.coerceIn(-1.0, 1.0),
            micropricePressure.coerceIn(-1.0, 1.0),
            totalBookPressure.coerceIn(-1.0, 1.0),
            wallPressure.coerceIn(-1.0, 1.0),
            (depthLevels / 30.0).coerceIn(0.0, 1.0),
            (minutesFromOpen / 375.0).coerceIn(0.0, 1.2),
            (recentEngineWinRate / 100.0).coerceIn(0.0, 1.0),
            min(recentEngineProfitFactor, 3.0) / 3.0,
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
    )

    private val weights = DoubleArray(FEATURE_COUNT)
    private var bias = -0.05
    private var learned = 0L
    private var version = 1L
    var mode: Mode = Mode.SHADOW
        private set

    fun setMode(value: Mode) { mode = value }

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
    }

    fun snapshot(): ModelState = ModelState(weights.copyOf(), bias, learned, version, mode)

    fun predict(features: Features): Prediction {
        val x = features.vector()
        var z = bias
        for (i in x.indices) z += weights[i] * x[i]
        val p = sigmoid(z)
        val decision = when {
            p >= TAKE_THRESHOLD -> Decision.TAKE
            p <= REJECT_THRESHOLD -> Decision.REJECT
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
            weights[i] += learningRate * (error * x[i] - l2 * weights[i])
            weights[i] = weights[i].coerceIn(-8.0, 8.0)
        }
        bias = (bias + learningRate * error).coerceIn(-8.0, 8.0)
        learned++
        if (learned % 100L == 0L) version++
    }

    companion object {
        const val FEATURE_COUNT = 21
        const val TAKE_THRESHOLD = 0.66
        const val REJECT_THRESHOLD = 0.42

        private fun sigmoid(value: Double): Double = when {
            value >= 35.0 -> 1.0
            value <= -35.0 -> 0.0
            else -> 1.0 / (1.0 + exp(-value))
        }
    }
}
