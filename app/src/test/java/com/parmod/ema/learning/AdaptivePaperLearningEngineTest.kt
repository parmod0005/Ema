package com.parmod.ema.learning

import com.parmod.ema.ai.MarketRegime
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePaperLearningEngineTest {
    private val engine = AdaptivePaperLearningEngine()
    private val current = AdaptivePaperLearningEngine.Policy()

    @Test
    fun smallSampleCannotPromote() {
        val outcomes = List(10) { trade(index = it, pnl = 1.0, confidence = 85) }
        val result = engine.evaluate(outcomes, current)

        assertFalse(result.eligibleForPromotion)
        assertEquals(current, result.candidatePolicy)
        assertTrue(result.reasons.any { it.contains("40") })
    }

    @Test
    fun losingPolicyCannotPromote() {
        val outcomes = List(60) { index ->
            trade(index, pnl = if (index % 3 == 0) 1.2 else -1.0, confidence = 82)
        }
        val result = engine.evaluate(outcomes, current)

        assertFalse(result.eligibleForPromotion)
        assertTrue(result.expectancy < 0.0)
    }

    @Test
    fun strongPaperEvidenceProducesOnlyBoundedChange() {
        // P&L values are realistic percentages of the ₹100 test premium so the
        // drawdown gate is exercised rather than accidentally tripped by a
        // synthetic +120% / -70% sequence.
        val outcomes = List(80) { index ->
            trade(
                index = index,
                pnl = if (index % 10 < 7) 1.2 else -0.7,
                confidence = 84,
                adverse = if (index % 10 < 7) 0.4 else 0.9,
            )
        }
        val result = engine.evaluate(outcomes, current)

        assertTrue(result.eligibleForPromotion)
        assertTrue(result.candidatePolicy.minimumAiConfidence in 75..90)
        assertTrue(result.candidatePolicy.riskFractionPct in 0.25..0.75)
        assertEquals(current.version + 1, result.candidatePolicy.version)
    }

    private fun trade(
        index: Int,
        pnl: Double,
        confidence: Int,
        adverse: Double = if (pnl < 0) 1.0 else 0.3,
    ) = AdaptivePaperLearningEngine.PaperTradeOutcome(
        openedAtMillis = index * 60_000L,
        closedAtMillis = index * 60_000L + 30_000L,
        provider = "DIRECT_OPENAI",
        modelVersion = "test-model",
        promptVersion = "v1",
        regime = MarketRegime.TRENDING_BULLISH,
        side = PositionSide.CE,
        confidence = confidence,
        entryPrice = 100.0,
        exitPrice = if (pnl >= 0) 101.0 else 99.0,
        quantity = 1,
        pnl = pnl,
        maximumAdverseExcursionPct = adverse,
        maximumFavourableExcursionPct = if (pnl > 0) 1.4 else 0.2,
        exitReason = "TEST",
    )
}
