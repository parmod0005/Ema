package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import kotlin.math.roundToInt

/** Reconstructs a Features object whose vector() is numerically identical to an archived normalized vector. */
object ArchivedFeatureVectorAdapter {
    fun toFeatures(vector: DoubleArray): NumericalMetaBrain.Features {
        require(vector.size == NumericalMetaBrain.FEATURE_COUNT) { "Archived feature vector must contain ${NumericalMetaBrain.FEATURE_COUNT} values" }
        require(vector.all(Double::isFinite)) { "Archived feature vector contains non-finite values" }
        require(kotlin.math.abs(vector[0] - 1.0) <= 1e-9) { "Archived feature vector intercept is invalid" }

        val engineOrdinal = (vector[1] * 2.0).roundToInt().coerceIn(0, EngineId.entries.lastIndex)
        val marketOrdinal = vector[2].roundToInt().coerceIn(0, MarketIndex.entries.lastIndex)
        val side = if (vector[3] >= 0.0) PositionSide.CE else PositionSide.PE
        val causal = NumericalMetaBrain.CausalExtras(
            premiumReturn1 = vector[21] / 2.0,
            premiumReturn3 = vector[22] * 0.75,
            premiumReturn5 = vector[23],
            premiumReturn15 = vector[24] * 1.50,
            emaSpread = vector[25] / 4.0,
            emaSlope = vector[26] * 0.15,
            zlemaSpread = vector[27] / 4.0,
            rsi = vector[28] * 50.0 + 50.0,
            macdHistogram = vector[29] / 5.0,
            atrRatio = vector[30] * 2.0 + 1.0,
            bbPosition = vector[31] * 2.0,
            bbWidth = vector[32],
            bodyRatio = vector[33],
            wickSkew = vector[34],
            volumeAcceleration = vector[35] * 3.0,
            oiAcceleration = vector[36] * 3.0,
            spotReturn3 = vector[37] * 0.05,
            optionSpotRelative = vector[38] / 2.0,
            moneynessSteps = vector[39] * 8.0,
            daysToExpiry = vector[40] * 30.0,
            realizedVolatility = vector[41] / 4.0,
            momentumPersistence = vector[42],
        )
        return NumericalMetaBrain.Features(
            engine = EngineId.entries[engineOrdinal],
            index = MarketIndex.entries[marketOrdinal],
            side = side,
            engineConfidence = vector[4] * 100.0,
            directionScore = vector[5] * 60.0,
            entryQualityScore = vector[6] * 40.0,
            orderFlow = vector[7],
            relativeActivity = vector[8] * 3.0,
            oiImpulse = vector[9],
            optionFlow = vector[10],
            acceleration = vector[11] * 3.0,
            extensionAtr = vector[12] * 6.0,
            depthImbalance = vector[13],
            micropricePressure = vector[14],
            totalBookPressure = vector[15],
            wallPressure = vector[16],
            depthLevels = vector[17] * 30.0,
            minutesFromOpen = vector[18] * 375.0,
            recentEngineWinRate = vector[19] * 100.0,
            recentEngineProfitFactor = vector[20] * 3.0,
            causal = causal,
        )
    }

    fun normalizeLegacy(vector: DoubleArray): DoubleArray {
        require(vector.size == NumericalMetaBrain.LEGACY_FEATURE_COUNT || vector.size == NumericalMetaBrain.FEATURE_COUNT) {
            "Archived feature vector must be legacy-${NumericalMetaBrain.LEGACY_FEATURE_COUNT} or current-${NumericalMetaBrain.FEATURE_COUNT}"
        }
        return if (vector.size == NumericalMetaBrain.FEATURE_COUNT) vector.copyOf()
        else DoubleArray(NumericalMetaBrain.FEATURE_COUNT).also { vector.copyInto(it) }
    }
}
