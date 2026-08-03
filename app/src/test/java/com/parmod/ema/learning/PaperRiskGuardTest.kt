package com.parmod.ema.learning

import com.parmod.ema.ai.MarketRegime
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PaperRiskGuardTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val noon = LocalDate.of(2026, 8, 3).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun locksAfterDailyLossLimit() {
        val status = PaperRiskGuard().evaluate(
            outcomes = listOf(outcome(1, -2_100.0)),
            startingCapital = 100_000.0,
            nowMillis = noon,
            zoneId = zone,
        )
        assertTrue(status.dailyLossLocked)
        assertTrue(status.locked)
    }

    @Test
    fun locksAfterThreeConsecutiveLosses() {
        val status = PaperRiskGuard().evaluate(
            outcomes = listOf(outcome(1, -100.0), outcome(2, -100.0), outcome(3, -100.0)),
            startingCapital = 100_000.0,
            nowMillis = noon,
            zoneId = zone,
        )
        assertTrue(status.consecutiveLossLocked)
        assertTrue(status.locked)
    }

    @Test
    fun winningTradeBreaksLossSequence() {
        val status = PaperRiskGuard().evaluate(
            outcomes = listOf(outcome(1, -100.0), outcome(2, -100.0), outcome(3, 150.0)),
            startingCapital = 100_000.0,
            nowMillis = noon,
            zoneId = zone,
        )
        assertFalse(status.consecutiveLossLocked)
    }

    @Test
    fun tradeCountAloneDoesNotLock() {
        val status = PaperRiskGuard().evaluate(
            outcomes = (1..8).map { outcome(it, 50.0) },
            startingCapital = 100_000.0,
            nowMillis = noon,
            zoneId = zone,
        )
        assertFalse(status.tradeCountLocked)
        assertFalse(status.locked)
    }

    private fun outcome(index: Int, pnl: Double): AdaptivePaperLearningEngine.PaperTradeOutcome {
        val closed = noon - (10 - index) * 60_000L
        return AdaptivePaperLearningEngine.PaperTradeOutcome(
            openedAtMillis = closed - 30_000L,
            closedAtMillis = closed,
            provider = "DIRECT_OPENAI",
            modelVersion = "test",
            promptVersion = "v1",
            regime = MarketRegime.UNKNOWN,
            side = PositionSide.CE,
            confidence = 82,
            entryPrice = 100.0,
            exitPrice = if (pnl >= 0) 101.0 else 99.0,
            quantity = 1,
            pnl = pnl,
            maximumAdverseExcursionPct = if (pnl < 0) 2.0 else 0.5,
            maximumFavourableExcursionPct = if (pnl > 0) 2.0 else 0.5,
            exitReason = "TEST",
        )
    }
}
