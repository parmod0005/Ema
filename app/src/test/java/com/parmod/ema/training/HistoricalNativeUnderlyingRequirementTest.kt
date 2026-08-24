package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.engine.SignalEngineV2
import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class HistoricalNativeUnderlyingRequirementTest {
    @Test
    fun legacy_option_only_series_produces_no_official_samples() {
        val start = OffsetDateTime.of(2026, 8, 18, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
        val options = (0 until 100).map { i ->
            val open = 100.0 + i * 1.5
            val close = open + 1.0
            UpstoxPlusHistoricalClient.Candle(
                time = start.plusMinutes(i.toLong()),
                open = open,
                high = close + 1.0,
                low = open - 1.0,
                close = close,
                volume = 10_000L + i,
                openInterest = 50_000L + i,
            )
        }
        val series = HistoricalOptionSeries(
            index = MarketIndex.NIFTY,
            optionType = "CE",
            strike = 24_000.0,
            expiry = LocalDate.of(2026, 8, 20),
            lotSize = 65,
            symbol = "NIFTY 24000 CE",
            source = "LEGACY_TEST",
            candles = options,
            underlyingCandles = emptyList(),
        )
        val result = HistoricalSeriesSampleBuilder.build(
            contract = series,
            optionCandlesInput = options,
            config = HistoricalCorpusTrainer.Config(
                months = 1,
                sampleStrideBars = 1,
                minimumSignalScore = 60,
                minimumCorpusSamples = 1,
            ),
            signalEngine = SignalEngineV2(),
        )

        assertFalse(result.nativeUnderlying)
        assertTrue(result.samples.isEmpty())
        assertTrue(result.alignedUnderlyingBars == 0)
    }
}
