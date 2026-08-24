package com.parmod.ema.training

import com.parmod.ema.engine.NumericalMetaBrain
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ArchivedFeatureVectorAdapterTest {
    @Test
    fun round_trip_preserves_normalized_vector() {
        val features = NumericalMetaBrain.Features(
            engine = EngineId.ENGINE_2_AVWAP_LIQUIDITY,
            index = MarketIndex.SENSEX,
            side = PositionSide.PE,
            engineConfidence = 78.0,
            directionScore = 44.0,
            entryQualityScore = 31.0,
            orderFlow = -0.36,
            relativeActivity = 2.1,
            oiImpulse = 0.23,
            optionFlow = -0.14,
            acceleration = 1.1,
            extensionAtr = 2.4,
            depthImbalance = -0.28,
            micropricePressure = 0.19,
            totalBookPressure = -0.11,
            wallPressure = 0.31,
            depthLevels = 30.0,
            minutesFromOpen = 133.0,
            recentEngineWinRate = 57.0,
            recentEngineProfitFactor = 1.42,
            causal = NumericalMetaBrain.CausalExtras(
                premiumReturn1 = .04, premiumReturn3 = -.06, premiumReturn5 = .12, premiumReturn15 = .18,
                emaSpread = .03, emaSlope = -.02, zlemaSpread = .04, rsi = 62.0, macdHistogram = .015,
                atrRatio = 1.4, bbPosition = -.7, bbWidth = .16, bodyRatio = .62, wickSkew = -.22,
                volumeAcceleration = .9, oiAcceleration = -.6, spotReturn3 = .012, optionSpotRelative = .08,
                moneynessSteps = -2.0, daysToExpiry = 4.0, realizedVolatility = .045, momentumPersistence = .4,
            ),
        )
        val archived = features.vector()
        val replayed = ArchivedFeatureVectorAdapter.toFeatures(archived).vector()
        assertArrayEquals(archived, replayed, 1e-12)
    }

    @Test
    fun legacy_vector_is_zero_padded_only() {
        val legacy = DoubleArray(NumericalMetaBrain.LEGACY_FEATURE_COUNT) { it.toDouble() / 100.0 }
        val migrated = ArchivedFeatureVectorAdapter.normalizeLegacy(legacy)
        assertArrayEquals(legacy, migrated.copyOfRange(0, legacy.size), 0.0)
        assertArrayEquals(DoubleArray(NumericalMetaBrain.FEATURE_COUNT - legacy.size), migrated.copyOfRange(legacy.size, migrated.size), 0.0)
    }
}
