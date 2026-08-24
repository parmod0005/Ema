package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaBrainFeatureSchemaMigrationTest {
    @Test
    fun legacy_model_restore_preserves_old_weights_and_zero_pads_new_features() {
        val legacy = DoubleArray(NumericalMetaBrain.LEGACY_FEATURE_COUNT) { (it + 1) / 100.0 }
        val brain = NumericalMetaBrain()
        brain.restore(
            NumericalMetaBrain.ModelState(
                weights = legacy,
                bias = 0.12,
                samplesLearned = 123,
                modelVersion = 7,
                mode = NumericalMetaBrain.Mode.SHADOW,
            ),
        )
        val restored = brain.snapshot()
        assertEquals(NumericalMetaBrain.FEATURE_COUNT, restored.weights.size)
        assertArrayEquals(legacy, restored.weights.copyOfRange(0, legacy.size), 1e-12)
        assertTrue(restored.weights.copyOfRange(legacy.size, restored.weights.size).all { it == 0.0 })
    }

    @Test
    fun scoped_causal_context_is_consumed_once_and_cannot_leak_to_next_sample() {
        NumericalMetaBrain.setThreadCausalExtras(
            NumericalMetaBrain.CausalExtras(premiumReturn1 = 0.10, rsi = 70.0, moneynessSteps = 2.0),
        )
        val first = features().vector()
        val second = features().vector()
        assertTrue(first[21] > 0.0)
        assertTrue(first[28] > 0.0)
        assertTrue(first[39] > 0.0)
        assertEquals(0.0, second[21], 1e-12)
        assertEquals(0.0, second[28], 1e-12)
        assertEquals(0.0, second[39], 1e-12)
    }

    private fun features() = NumericalMetaBrain.Features(
        engine = EngineId.ENGINE_1_TREND,
        index = MarketIndex.NIFTY,
        side = PositionSide.CE,
        engineConfidence = 80.0,
        directionScore = 45.0,
        entryQualityScore = 30.0,
        orderFlow = 0.2,
        relativeActivity = 1.1,
        oiImpulse = 0.1,
        optionFlow = 0.1,
        acceleration = 0.0,
        extensionAtr = 1.0,
        depthImbalance = 0.0,
        micropricePressure = 0.0,
        totalBookPressure = 0.0,
        wallPressure = 0.0,
        depthLevels = 0.0,
        minutesFromOpen = 30.0,
        recentEngineWinRate = 50.0,
        recentEngineProfitFactor = 1.0,
    )
}
