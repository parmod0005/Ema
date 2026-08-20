package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime

class HistoricalSeriesMergerTest {
    private fun candle(time: String, close: Double) = UpstoxPlusHistoricalClient.Candle(
        time = OffsetDateTime.parse(time),
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 1,
        openInterest = 1,
    )

    @Test fun combined_corpus_deduplicates_and_prefers_upstox_overlap() {
        val expiry = LocalDate.parse("2026-08-20")
        val local = HistoricalOptionSeries(
            MarketIndex.NIFTY, "CE", 24500.0, expiry, 65, "NIFTY 24500 CE", "LOCAL_IMPORT",
            listOf(candle("2026-08-20T09:15:00+05:30", 100.0), candle("2026-08-20T09:16:00+05:30", 101.0)),
        )
        val upstox = local.copy(
            source = "UPSTOX_PLUS",
            candles = listOf(candle("2026-08-20T09:16:00+05:30", 111.0), candle("2026-08-20T09:17:00+05:30", 112.0)),
        )
        val merged = HistoricalSeriesMerger.merge(listOf(local, upstox))
        assertEquals(1, merged.size)
        assertEquals(3, merged.single().candles.size)
        assertEquals(111.0, merged.single().candles[1].close, 0.0001)
        assertTrue(merged.single().source.contains("UPSTOX_PLUS"))
        assertTrue(merged.single().source.contains("LOCAL_IMPORT"))
    }
}
