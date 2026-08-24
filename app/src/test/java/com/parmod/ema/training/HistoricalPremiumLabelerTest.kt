package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class HistoricalPremiumLabelerTest {
    private fun candle(minute: Int, open: Double, high: Double, low: Double, close: Double) =
        UpstoxPlusHistoricalClient.Candle(
            time = OffsetDateTime.of(2026, 1, 5, 9, 15 + minute, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1_000,
            openInterest = 10_000,
        )

    @Test fun uses_next_bar_open_not_signal_close() {
        val candles = listOf(
            candle(0, 95.0, 101.0, 94.0, 100.0),
            candle(1, 110.0, 122.0, 109.0, 120.0),
            candle(2, 120.0, 124.0, 118.0, 121.0),
        )
        val result = HistoricalPremiumLabeler.label(
            candles,
            signalIndex = 0,
            lotSize = 65,
            config = HistoricalPremiumLabeler.Config(horizonBars = 2, targetReturn = 0.10, stopReturn = 0.075, slippageEachSide = 0.0, flatRoundTripCost = 0.0),
        )!!
        assertEquals(110.0, result.entryPrice, 1e-9)
        assertTrue(result.success)
        assertEquals(HistoricalPremiumLabeler.ExitReason.TARGET, result.exitReason)
    }

    @Test fun same_bar_stop_and_target_is_counted_as_stop() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 112.0, 90.0, 105.0),
        )
        val result = HistoricalPremiumLabeler.label(
            candles,
            signalIndex = 0,
            lotSize = 65,
            config = HistoricalPremiumLabeler.Config(horizonBars = 1, targetReturn = 0.10, stopReturn = 0.075, slippageEachSide = 0.0, flatRoundTripCost = 0.0),
        )!!
        assertFalse(result.success)
        assertEquals(HistoricalPremiumLabeler.ExitReason.STOP, result.exitReason)
    }

    @Test fun brokerage_can_turn_small_timeout_gain_into_failure() {
        val candles = listOf(
            candle(0, 100.0, 101.0, 99.0, 100.0),
            candle(1, 100.0, 102.0, 99.0, 101.0),
        )
        val result = HistoricalPremiumLabeler.label(
            candles,
            signalIndex = 0,
            lotSize = 20,
            config = HistoricalPremiumLabeler.Config(horizonBars = 1, targetReturn = 0.10, stopReturn = 0.075, slippageEachSide = 0.0, flatRoundTripCost = 70.80),
        )!!
        assertFalse(result.success)
        assertTrue(result.netReturn < 0.0)
    }
}
