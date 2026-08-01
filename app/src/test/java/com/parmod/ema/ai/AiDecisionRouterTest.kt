package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDecisionRouterTest {
    private val router = AiDecisionRouter()
    private val now = 1_000_000L
    private val health = AiBridgeHealth(configured = true, reachable = true)

    private fun ai(
        action: SignalAction = SignalAction.BUY_CE,
        confidence: Int = 88,
        decidedAt: Long = now - 500,
        validFor: Long = 30_000,
        maxMove: Double = 0.20,
        riskFlags: List<String> = emptyList(),
    ) = AiTradeDecision(
        decisionId = "d1",
        snapshotId = "s1",
        decidedAtMillis = decidedAt,
        validForMillis = validFor,
        action = action,
        confidence = confidence,
        regime = MarketRegime.TRENDING_BULLISH,
        maximumSpotMovePct = maxMove,
        reasons = listOf("trend", "liquidity"),
        riskFlags = riskFlags,
        modelVersion = "test",
        promptVersion = "test",
    )

    private fun context(
        mode: SignalEngineMode = SignalEngineMode.HYBRID,
        runMode: AiRunMode = AiRunMode.PAPER,
        nativeAction: SignalAction = SignalAction.BUY_CE,
        nativeConfidence: Int = 85,
        decision: AiTradeDecision? = ai(),
        bridgeHealth: AiBridgeHealth = health,
        currentSpot: Double = 24_500.0,
        snapshotSpot: Double = 24_500.0,
    ) = AiDecisionRouter.Context(
        mode = mode,
        aiRunMode = runMode,
        nowMillis = now,
        currentSpot = currentSpot,
        snapshotSpot = snapshotSpot,
        dataAgeMillis = 200,
        bridgeHealth = bridgeHealth,
        dailyLossLocked = false,
        hasOpenPosition = false,
        native = AiDecisionRouter.NativeDecision(nativeAction, nativeConfidence),
        ai = decision,
    )

    @Test fun hybridAgreementExecutesInPaperMode() {
        val result = router.route(context())
        assertEquals(SignalAction.BUY_CE, result.action)
        assertEquals(AiDecisionRouter.Authority.AGREEMENT, result.authority)
        assertTrue(result.executable)
    }

    @Test fun shadowModeNeverExecutes() {
        val result = router.route(context(runMode = AiRunMode.SHADOW))
        assertEquals(SignalAction.BUY_CE, result.action)
        assertFalse(result.executable)
    }

    @Test fun disagreementReturnsWait() {
        val result = router.route(context(nativeAction = SignalAction.BUY_PE))
        assertEquals(SignalAction.WAIT, result.action)
        assertFalse(result.executable)
    }

    @Test fun expiredAiFallsBackToStrongNativeInHybrid() {
        val expired = ai(decidedAt = now - 40_000, validFor = 5_000)
        val result = router.route(context(decision = expired))
        assertEquals(SignalAction.BUY_CE, result.action)
        assertEquals(AiDecisionRouter.Authority.NATIVE, result.authority)
        assertTrue(result.executable)
    }

    @Test fun aiOnlyRejectsBridgeOutage() {
        val down = AiBridgeHealth(configured = true, reachable = false)
        val result = router.route(context(mode = SignalEngineMode.AI_BRAIN, bridgeHealth = down))
        assertEquals(SignalAction.WAIT, result.action)
        assertFalse(result.executable)
    }

    @Test fun excessivePriceMoveRejectsAiDecision() {
        val result = router.route(context(
            mode = SignalEngineMode.AI_BRAIN,
            currentSpot = 24_600.0,
            snapshotSpot = 24_500.0,
        ))
        assertEquals(SignalAction.WAIT, result.action)
        assertFalse(result.executable)
    }
}
