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
        val outcomes = List(10) { trade(index = it, pnl = 100.0, confidence = 85) }
        val result = engine.evaluate(outcomes, current)

        assertFalse(result.eligibleForPromotion)
        assertEquals(current, result.candidatePolicy)
        assertTrue(result.reasons.any { it.contains("40") })
    }

    @Test
    fun losingPolicyCannotPromote() {
        val outcomes = List(60) { index ->
            trade(index, pnl = if (index % 3 == 0) 120.0 else -100.0, confidence = 82)
        }
        val result = engine.evaluate(outcomes, current)

        assertFalse(result.eligibleForPromotion)
        assertTrue(result.expectancy < 0.0)
    }

    @Test
    fun strongPaperEvidenceProducesOnlyBoundedChange() {
        val outcomes = List(80) { index ->
            trade(
                index = index,
                pnl = if (index % 10 < 7) 120.0 else -70.0,
                confidence = 84,
                adverse = if (index % 10 < 7) 4.0 else 9.0,
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
        adverse: Double = if (pnl < 0) 10.0 else 3.0,
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
        maximumFavourableExcursionPct = if (pnl > 0) 14.0 else 2.0,
        exitReason = "TEST",
    )
}
