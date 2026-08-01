package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side tests cover the pure AI decision contract. The org.json parser is
 * exercised by Android/integration tests because Android's org.json classes are
 * stubs in local JVM unit tests.
 */
class AiBridgeClientTest {
    private fun decision(
        action: SignalAction = SignalAction.BUY_CE,
        confidence: Int = 87,
        validForMillis: Long = 30_000L,
    ) = AiTradeDecision(
        decisionId = "d-1",
        snapshotId = "s-1",
        decidedAtMillis = 1_000L,
        validForMillis = validForMillis,
        action = action,
        confidence = confidence,
        regime = MarketRegime.TRENDING_BULLISH,
        maximumSpotMovePct = 0.15,
        modelVersion = "gpt-test",
        promptVersion = "p1",
    )

    @Test fun acceptsCallDecisionContract() {
        val parsed = decision()
        assertEquals(SignalAction.BUY_CE, parsed.action)
        assertEquals(87, parsed.confidence)
        assertFalse(parsed.isExpired(20_000L))
    }

    @Test fun acceptsPutDecisionContract() {
        assertEquals(SignalAction.BUY_PE, decision(SignalAction.BUY_PE).action)
    }

    @Test fun rejectsInvalidConfidence() {
        assertThrows(IllegalArgumentException::class.java) {
            decision(confidence = 140)
        }
    }

    @Test fun rejectsInvalidValidityWindow() {
        assertThrows(IllegalArgumentException::class.java) {
            decision(validForMillis = 500L)
        }
    }

    @Test fun expiryIsDeterministic() {
        val value = decision(validForMillis = 5_000L)
        assertFalse(value.isExpired(6_000L))
        assertTrue(value.isExpired(6_001L))
    }
}
