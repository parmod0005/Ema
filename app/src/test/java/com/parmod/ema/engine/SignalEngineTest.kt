package com.parmod.ema.engine

import com.parmod.ema.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalEngineTest {
    private val engine = SignalEngine()

    @Test
    fun bullishTrendProducesCallSignal() {
        val result = engine.evaluate(
            SignalInput(
                spot = 24_500.0,
                ema20 = 24_480.0,
                ema50 = 24_450.0,
                ema20ThreeBarsAgo = 24_465.0,
                ema50ThreeBarsAgo = 24_445.0,
                atr = 60.0,
                adx = 28.0,
                volumeRatio = 1.4,
                higherTimeframeBullish = true,
                higherTimeframeBearish = false,
                bullishStructure = true,
                bearishStructure = false,
                emaCrossesLastTenBars = 0,
                priceCrossesEma20LastTenBars = 1,
            ),
        )

        assertEquals(SignalAction.BUY_CE, result.action)
    }

    @Test
    fun chopIsAlwaysRejected() {
        val result = engine.evaluate(
            SignalInput(
                spot = 24_500.0,
                ema20 = 24_501.0,
                ema50 = 24_500.0,
                ema20ThreeBarsAgo = 24_499.0,
                ema50ThreeBarsAgo = 24_499.5,
                atr = 60.0,
                adx = 14.0,
                volumeRatio = 1.0,
                higherTimeframeBullish = true,
                higherTimeframeBearish = false,
                bullishStructure = true,
                bearishStructure = false,
                emaCrossesLastTenBars = 4,
                priceCrossesEma20LastTenBars = 6,
            ),
        )

        assertEquals(SignalAction.WAIT, result.action)
    }
}
