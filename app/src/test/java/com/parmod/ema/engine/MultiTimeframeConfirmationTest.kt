package com.parmod.ema.engine

import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.SignalTimeframe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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
            enforceEntryWindow = false,
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
            enforceEntryWindow = false,
        )
        assertTrue(result.ready)
        assertTrue(result.triggerBars >= 55)
        assertTrue(result.setupBars >= 55)
        assertTrue(result.biasBars >= 55)
    }

    @Test
    fun automatic_entry_window_fails_closed_after_1510_ist() {
        val zone = ZoneId.of("Asia/Kolkata")
        val end = LocalDate.of(2026, 8, 21)
            .atTime(LocalTime.of(15, 11))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val start = end - 599L * 60_000L
        val bars = (0 until 600).map { i ->
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
        val result = MultiTimeframeConfirmation().evaluate(bars, EngineTimeframeConfig.E1_DEFAULT)
        assertFalse(result.ready)
        assertTrue(result.reasons.any { it.contains("entry window", ignoreCase = true) })
    }
}
