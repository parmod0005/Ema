package com.parmod.ema.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineV2Test {
    @Test
    fun strongExpandingTrendBecomesActionable() {
        val bars = (0 until 140).map { i ->
            val base = 100.0 + i * 0.55
            val expansion = if (i > 115) 1.8 else 0.9
            SignalEngineV2.Bar(
                open = base - 0.2,
                high = base + expansion,
                low = base - expansion * 0.4,
                close = base + 0.3,
                volume = if (i == 139) 2200 else 1000,
            )
        }

        val result = SignalEngineV2().evaluate(bars)
        assertEquals(SignalEngineV2.Direction.BULLISH, result.direction)
        assertTrue(result.score >= 80)
        assertTrue(result.higherTimeframeAligned)
    }

    @Test
    fun sidewaysMarketIsRejected() {
        val bars = (0 until 140).map { i ->
            val base = 100.0 + if (i % 2 == 0) 0.25 else -0.25
            SignalEngineV2.Bar(base, base + 0.4, base - 0.4, base, 1000)
        }

        val result = SignalEngineV2().evaluate(bars)
        assertEquals(SignalEngineV2.Direction.NEUTRAL, result.direction)
        assertTrue(!result.actionable)
    }
}
