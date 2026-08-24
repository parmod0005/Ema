package com.parmod.ema.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineV2Test {
    @Test
    fun strongExpandingBreakoutBecomesActionable() {
        val bars = (0 until 160).map { i ->
            val trend = when {
                i < 120 -> i * 0.22
                else -> 26.4 + (i - 120) * 0.95
            }
            val base = 100.0 + trend
            val isBreakoutBar = i == 159
            val range = when {
                isBreakoutBar -> 4.8
                i < 120 -> 0.55
                else -> 1.65
            }
            SignalEngineV2.Bar(
                open = base - 0.25,
                high = if (isBreakoutBar) base + 5.2 else base + range,
                low = base - range * 0.35,
                close = if (isBreakoutBar) base + 4.6 else base + if (i < 120) 0.15 else 0.80,
                volume = if (isBreakoutBar) 5_000 else if (i >= 140) 2_400 else 1_000,
            )
        }

        val result = SignalEngineV2().evaluate(bars)
        assertEquals(SignalEngineV2.Direction.BULLISH, result.direction)
        assertTrue(result.score >= 85)
        assertTrue(result.higherTimeframeAligned)
        assertTrue(result.actionable)
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
