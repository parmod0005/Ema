package com.parmod.ema.training

import com.parmod.ema.model.MarketIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AimlHistoricalOptionCorpusV1LabelTest {
    private fun row(
        future5: Double,
        mfe5: Double,
        mae5: Double,
        close: Double = 100.0,
        lot: Int = 65,
    ) = AimlHistoricalOptionCorpusV1Store.Record(
        timestampMs = 1_735_700_400_000L,
        expiryEpochDay = 20_000,
        index = MarketIndex.NIFTY,
        optionType = "CE",
        lotSize = lot,
        open = close,
        high = close * 1.01,
        low = close * 0.99,
        close = close,
        volume = 10_000.0,
        oi = 100_000.0,
        spot = 24_000.0,
        strike = 24_000.0,
        strikeStep = 50.0,
        signedMoneynessSteps = 0.0,
        future1 = 0.0,
        future3 = 0.0,
        future5 = future5,
        future15 = future5,
        mfe1 = 0.0,
        mfe3 = 0.0,
        mfe5 = mfe5,
        mfe15 = mfe5,
        mae1 = 0.0,
        mae3 = 0.0,
        mae5 = mae5,
        mae15 = mae5,
    )

    @Test
    fun targetOnlyIsSuccessful() {
        assertTrue(AimlHistoricalOptionCorpusV1Store.success5(row(future5 = 0.06, mfe5 = 0.12, mae5 = -0.03)))
    }

    @Test
    fun stopWinsWhenBothStopAndTargetWereReachable() {
        assertFalse(AimlHistoricalOptionCorpusV1Store.success5(row(future5 = 0.08, mfe5 = 0.15, mae5 = -0.09)))
    }

    @Test
    fun positiveRawMoveCanStillFailAfterRoundTripCosts() {
        assertFalse(AimlHistoricalOptionCorpusV1Store.success5(row(future5 = 0.005, mfe5 = 0.02, mae5 = -0.01, close = 100.0, lot = 65)))
    }
}
