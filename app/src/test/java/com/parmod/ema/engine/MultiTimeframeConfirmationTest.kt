package com.parmod.ema.engine

import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.SignalTimeframe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTimeframeConfirmationTest {
    @Test
    fun insufficient_completed_bias_bars_fail_closed() {
        val start = 1_786_900_000_000L
        val bars = (0 until 100).map { i ->
            val open = 24_000.0 + i
            MultiTimeframeConfirmation.TimedBar(
                timestamp = start + i * 60_000L,
                open = open,
                high = open + 2.0,
                low = open - 1.0,
                close = open + 1.0,
                volume = 10_000L + i,
            )
        }
        val result = MultiTimeframeConfirmation().evaluate(
            bars,
            EngineTimeframeConfig(SignalTimeframe.M1, SignalTimeframe.M3, SignalTimeframe.M5),
        )
        assertFalse(result.ready)
    }

    @Test
    fun completed_one_three_five_profile_can_reach_ready_state() {
        val start = 1_786_900_000_000L
        val bars = (0 until 600).map { i ->
            val open = 24_000.0 + i * 1.2
            MultiTimeframeConfirmation.TimedBar(
                timestamp = start + i * 60_000L,
                open = open,
                high = open + 2.2,
                low = open - 0.8,
                close = open + 1.5,
                volume = 20_000L + i * 10L,
            )
        }
        val result = MultiTimeframeConfirmation().evaluate(
            bars,
            EngineTimeframeConfig.E1_DEFAULT,
        )
        assertTrue(result.ready)
        assertTrue(result.triggerBars >= 55)
        assertTrue(result.setupBars >= 55)
        assertTrue(result.biasBars >= 55)
    }
}
