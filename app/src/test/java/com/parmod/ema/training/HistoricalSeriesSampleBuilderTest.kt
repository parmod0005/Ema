package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.engine.SignalEngineV2
import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class HistoricalSeriesSampleBuilderTest {
    private val config = HistoricalCorpusTrainer.Config(
        months = 1,
        sampleStrideBars = 1,
        minimumSignalScore = 60,
        minimumCorpusSamples = 1,
    )

    @Test
    fun ce_uses_bullish_underlying_and_rejects_bearish_underlying() {
        val options = risingOptionCandles()
        val bullish = series("CE", options, trendCandles(bullish = true))
        val bearish = series("CE", options, trendCandles(bullish = false))

        val good = HistoricalSeriesSampleBuilder.build(bullish, options, config, SignalEngineV2())
        val wrong = HistoricalSeriesSampleBuilder.build(bearish, options, config, SignalEngineV2())

        assertTrue(good.nativeUnderlying)
        assertTrue(good.samples.isNotEmpty())
        assertTrue(wrong.nativeUnderlying)
        assertTrue(wrong.samples.isEmpty())
    }

    @Test
    fun pe_uses_bearish_underlying_and_rejects_bullish_underlying() {
        val options = risingOptionCandles()
        val bearish = series("PE", options, trendCandles(bullish = false))
        val bullish = series("PE", options, trendCandles(bullish = true))

        val good = HistoricalSeriesSampleBuilder.build(bearish, options, config, SignalEngineV2())
        val wrong = HistoricalSeriesSampleBuilder.build(bullish, options, config, SignalEngineV2())

        assertTrue(good.samples.isNotEmpty())
        assertTrue(wrong.samples.isEmpty())
    }

    @Test
    fun changing_future_underlying_bars_cannot_change_earlier_feature_vector() {
        val options = risingOptionCandles()
        val original = trendCandles(bullish = true)
        val first = HistoricalSeriesSampleBuilder.build(series("CE", options, original), options, config, SignalEngineV2())
        assertFalse(first.samples.isEmpty())
        val anchor = first.samples.first()

        val mutated = original.mapIndexed { i, candle ->
            if (i < 80) candle else candle.copy(
                open = candle.open * 0.55,
                high = candle.high * 0.57,
                low = candle.low * 0.50,
                close = candle.close * 0.52,
            )
        }
        val second = HistoricalSeriesSampleBuilder.build(series("CE", options, mutated), options, config, SignalEngineV2())
        val matching = second.samples.first { it.timestamp == anchor.timestamp }
        assertArrayEquals(anchor.features.vector(), matching.features.vector(), 1e-12)
    }

    private fun series(
        type: String,
        options: List<UpstoxPlusHistoricalClient.Candle>,
        underlying: List<UpstoxPlusHistoricalClient.Candle>,
    ) = HistoricalOptionSeries(
        index = MarketIndex.NIFTY,
        optionType = type,
        strike = 24_000.0,
        expiry = LocalDate.of(2026, 8, 20),
        lotSize = 65,
        symbol = "NIFTY 24000 $type",
        source = "TEST",
        candles = options,
        underlyingCandles = underlying,
    )

    private fun trendCandles(bullish: Boolean): List<UpstoxPlusHistoricalClient.Candle> {
        val start = OffsetDateTime.of(2026, 8, 18, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
        return (0 until 110).map { i ->
            val curve = i.toDouble() * i.toDouble() * 0.75
            val close = if (bullish) 24_000.0 + curve else 32_000.0 - curve
            val previousCurve = (i - 1).coerceAtLeast(0).toDouble().let { it * it * 0.75 }
            val open = if (bullish) 24_000.0 + previousCurve else 32_000.0 - previousCurve
            val high = maxOf(open, close) + 3.0 + i * 0.05
            val low = minOf(open, close) - 3.0 - i * 0.05
            UpstoxPlusHistoricalClient.Candle(
                time = start.plusMinutes(i.toLong()),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 0L,
                openInterest = 0L,
            )
        }
    }

    private fun risingOptionCandles(): List<UpstoxPlusHistoricalClient.Candle> {
        val start = OffsetDateTime.of(2026, 8, 18, 9, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))
        return (0 until 110).map { i ->
            val open = 80.0 + i * 2.0
            val close = open + 1.8
            UpstoxPlusHistoricalClient.Candle(
                time = start.plusMinutes(i.toLong()),
                open = open,
                high = close + 1.5,
                low = open - 0.8,
                close = close,
                volume = 10_000L + i * 100L,
                openInterest = 100_000L + i * 250L,
            )
        }
    }
}
