package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class CausalFeatureStreamingOptimizationTest {
    private val startMs = LocalDate.of(2026, 7, 1)
        .atTime(9, 15)
        .toInstant(ZoneOffset.ofHoursMinutes(5, 30))
        .toEpochMilli()
    private val expiryDay = LocalDate.of(2026, 7, 30).toEpochDay().toInt()

    private fun record(i: Int, index: MarketIndex = MarketIndex.NIFTY): AimlHistoricalOptionCorpusV1Store.Record {
        val close = 100.0 + i * 0.35 + if (i % 7 == 0) 1.1 else 0.0
        val open = close - if (i % 2 == 0) 0.4 else -0.2
        return AimlHistoricalOptionCorpusV1Store.Record(
            timestampMs = startMs + i * 60_000L,
            expiryEpochDay = expiryDay,
            index = index,
            optionType = if (i % 2 == 0) "CE" else "PE",
            lotSize = 65,
            open = open,
            high = maxOf(open, close) + 0.8,
            low = minOf(open, close) - 0.6,
            close = close,
            volume = 1_000.0 + i * 20.0,
            oi = 10_000.0 + i * 35.0,
            spot = 24_000.0 + i * 2.0,
            strike = if (i % 2 == 0) 24_000.0 else 24_050.0,
            strikeStep = 50.0,
            signedMoneynessSteps = if (i % 2 == 0) 0.0 else 1.0,
            future1 = 0.01,
            future3 = 0.02,
            future5 = 0.03,
            future15 = 0.04,
            mfe1 = 0.02,
            mfe3 = 0.04,
            mfe5 = 0.06,
            mfe15 = 0.08,
            mae1 = -0.01,
            mae3 = -0.02,
            mae5 = -0.03,
            mae15 = -0.04,
        )
    }

    @Test
    fun deferred_materialization_matches_full_materialization_on_emitted_rows() {
        val rows = (0 until 90).map(::record)
        val baselineState = CausalFeatureEngineering.PrelabelledState()
        val baseline = linkedMapOf<Int, DoubleArray>()
        rows.forEachIndexed { i, row ->
            val extras = requireNotNull(baselineState.observe(row, materialize = true))
            if (i % 7 == 0) baseline[i] = extrasVector(extras)
        }

        val optimizedState = CausalFeatureEngineering.PrelabelledState()
        val optimized = linkedMapOf<Int, DoubleArray>()
        rows.forEachIndexed { i, row ->
            val extras = optimizedState.observe(row, materialize = i % 7 == 0)
            if (i % 7 == 0) optimized[i] = extrasVector(requireNotNull(extras))
            else assertEquals(null, extras)
        }

        assertEquals(baseline.keys, optimized.keys)
        baseline.forEach { (i, expected) ->
            assertArrayEquals("feature mismatch at row $i", expected, optimized.getValue(i), 1e-12)
        }
    }

    private fun extrasVector(e: com.parmod.ema.engine.NumericalMetaBrain.CausalExtras) = doubleArrayOf(
        e.premiumReturn1, e.premiumReturn3, e.premiumReturn5, e.premiumReturn15,
        e.emaSpread, e.emaSlope, e.zlemaSpread, e.rsi, e.macdHistogram, e.atrRatio,
        e.bbPosition, e.bbWidth, e.bodyRatio, e.wickSkew, e.volumeAcceleration,
        e.oiAcceleration, e.spotReturn3, e.optionSpotRelative, e.moneynessSteps,
        e.daysToExpiry, e.realizedVolatility, e.momentumPersistence,
    )
}
