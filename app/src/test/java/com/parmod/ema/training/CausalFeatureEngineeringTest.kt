package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CausalFeatureEngineeringTest {
    private val start = OffsetDateTime.of(2026, 1, 5, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))

    private fun candles(futureShock: Boolean = false): List<UpstoxPlusHistoricalClient.Candle> =
        (0 until 30).map { i ->
            val base = 100.0 + i
            val close = if (futureShock && i > 20) 500.0 + i else base + 0.6
            UpstoxPlusHistoricalClient.Candle(
                time = start.plusMinutes(i.toLong()),
                open = base,
                high = maxOf(base + 1.0, close + 0.2),
                low = minOf(base - 0.5, close - 0.2),
                close = close,
                volume = 1_000L + i * 50L,
                openInterest = 10_000L + i * 100L,
            )
        }

    @Test
    fun rising_completed_history_produces_positive_momentum_features() {
        val rows = candles()
        val e = CausalFeatureEngineering.fromCandles(
            candles = rows,
            index = 20,
            expiry = rows[20].time.toLocalDate().plusDays(3),
            moneynessSteps = 1.5,
            spot = 24_000.0,
        )
        assertTrue(e.premiumReturn1 > 0.0)
        assertTrue(e.premiumReturn3 > 0.0)
        assertTrue(e.emaSpread > 0.0)
        assertTrue(e.zlemaSpread > 0.0)
        assertTrue(e.rsi > 50.0)
        assertTrue(e.momentumPersistence > 0.0)
        assertEquals(3.0, e.daysToExpiry, 1e-12)
        assertEquals(1.5, e.moneynessSteps, 1e-12)
    }

    @Test
    fun changing_future_bars_cannot_change_signal_time_features() {
        val normal = candles(false)
        val shocked = candles(true)
        val a = CausalFeatureEngineering.fromCandles(normal, 20, normal[20].time.toLocalDate().plusDays(5))
        val b = CausalFeatureEngineering.fromCandles(shocked, 20, shocked[20].time.toLocalDate().plusDays(5))
        assertEquals(a, b)
    }
}
