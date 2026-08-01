package com.parmod.ema.backtest

import java.time.OffsetDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountChronologicalReplayEngineTest {
    @Test
    fun enforcesAccountLevelTradeAndCapitalRules() {
        val start = OffsetDateTime.parse("2026-06-01T09:15:00+05:30")
        fun candles(up: Boolean) = (0 until 80).map { i ->
            val base = if (up) 100.0 + i * 2.0 else 260.0 - i * 2.0
            UpstoxPlusHistoricalClient.Candle(
                time = start.plusMinutes(i.toLong()),
                open = base,
                high = base + 1,
                low = base - 1,
                close = base,
                volume = 1000,
                openInterest = 10000,
            )
        }
        val series = listOf(
            AccountChronologicalReplayEngine.Series("CE", 24000.0, "2026-06-04", 65, candles(true)),
            AccountChronologicalReplayEngine.Series("PE", 24000.0, "2026-06-04", 65, candles(false)),
        )
        val result = AccountChronologicalReplayEngine().replay(
            series,
            AccountChronologicalReplayEngine.Config(
                startingCapital = 100_000.0,
                minimumConfidence = 70,
                maximumTradesPerDay = 2,
                flatChargesPerRoundTrip = 50.0,
            ),
        )
        assertTrue(result.allTrades.size <= 2)
        assertTrue(result.maxAccountDrawdown >= 0.0)
        assertTrue(result.endingCapital.isFinite())
        assertFalse(result.capitalExhausted)
    }
}
