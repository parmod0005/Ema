package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericalMetaBrainTest {
    private fun features() = NumericalMetaBrain.Features(
        engine = EngineId.ENGINE_2_AVWAP_LIQUIDITY,
        index = MarketIndex.NIFTY,
        side = PositionSide.CE,
        engineConfidence = 82.0,
        directionScore = 48.0,
        entryQualityScore = 31.0,
        orderFlow = 0.35,
        relativeActivity = 1.8,
        oiImpulse = 0.4,
        optionFlow = 0.3,
        acceleration = 0.5,
        extensionAtr = 1.6,
        depthImbalance = 0.45,
        micropricePressure = 0.3,
        totalBookPressure = 0.25,
        wallPressure = 0.2,
        depthLevels = 30.0,
        minutesFromOpen = 80.0,
        recentEngineWinRate = 55.0,
        recentEngineProfitFactor = 1.25,
    )

    @Test fun positive_outcomes_raise_probability() {
        val brain = NumericalMetaBrain(learningRate = 0.05)
        val f = features()
        val before = brain.predict(f).probabilitySuccess
        repeat(80) { brain.learn(f, true) }
        val after = brain.predict(f).probabilitySuccess
        assertTrue(after > before)
    }

    @Test fun negative_outcomes_lower_probability() {
        val brain = NumericalMetaBrain(learningRate = 0.05)
        val f = features()
        val before = brain.predict(f).probabilitySuccess
        repeat(80) { brain.learn(f, false) }
        val after = brain.predict(f).probabilitySuccess
        assertTrue(after < before)
    }
}
