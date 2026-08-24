package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.PositionSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AdaptiveExitEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = ZonedDateTime.of(2026, 8, 21, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun warmup_keeps_baseline_stop_and_never_widens() {
        val e = AdaptiveExitEngine()
        val plan = e.open(EngineId.ENGINE_1_TREND, PositionSide.CE, 100.0, start)
        assertEquals(87.0, plan.stopPrice, 1e-9)

        val u1 = e.update(
            engine = EngineId.ENGINE_1_TREND, side = PositionSide.CE, entryPrice = 100.0,
            currentPrice = 99.0, timestamp = start + 5_000, currentStopPrice = plan.stopPrice,
            previousHighestPrice = 100.0, target1Hit = false, quantity = 65, strategy = "TREND",
            oppositeSignal = false, indexInvalidated = false, quality = null,
        )
        assertEquals(plan.stopPrice, u1.stopPrice, 1e-9)

        val u2 = e.update(
            engine = EngineId.ENGINE_1_TREND, side = PositionSide.CE, entryPrice = 100.0,
            currentPrice = 101.0, timestamp = start + 10_000, currentStopPrice = 90.0,
            previousHighestPrice = 101.0, target1Hit = false, quantity = 65, strategy = "TREND",
            oppositeSignal = false, indexInvalidated = false, quality = null,
        )
        assertTrue(u2.stopPrice >= 90.0)
    }

    @Test fun reaching_t1_creates_partial_trigger_not_forced_exit() {
        val e = AdaptiveExitEngine()
        val plan = e.open(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.PE, 100.0, start)
        val u = e.update(
            engine = EngineId.ENGINE_2_AVWAP_LIQUIDITY, side = PositionSide.PE, entryPrice = 100.0,
            currentPrice = 116.0, timestamp = start + 120_000, currentStopPrice = plan.stopPrice,
            previousHighestPrice = 116.0, target1Hit = false, quantity = 40, strategy = "E2",
            oppositeSignal = false, indexInvalidated = false, quality = null,
        )
        assertTrue(u.partialTrigger)
        assertNull(u.exitReason)
    }

    @Test fun opposite_signal_exits_immediately() {
        val e = AdaptiveExitEngine()
        val plan = e.open(EngineId.ENGINE_1_TREND, PositionSide.CE, 100.0, start)
        val u = e.update(
            engine = EngineId.ENGINE_1_TREND, side = PositionSide.CE, entryPrice = 100.0,
            currentPrice = 102.0, timestamp = start + 60_000, currentStopPrice = plan.stopPrice,
            previousHighestPrice = 103.0, target1Hit = false, quantity = 65, strategy = "TREND",
            oppositeSignal = true, indexInvalidated = false, quality = null,
        )
        assertEquals(AdaptiveExitEngine.ExitReason.OPPOSITE_SIGNAL, u.exitReason)
    }

    @Test fun severe_d30_flow_deterioration_exits_e2_after_minimum_hold() {
        val e = AdaptiveExitEngine()
        val plan = e.open(EngineId.ENGINE_2_AVWAP_LIQUIDITY, PositionSide.CE, 100.0, start)
        val bad = quality(
            decision = V76ExecutionQualityEngine.Decision.EXHAUSTION_RISK,
            score = 30,
            direction = 18,
            orderFlow = -0.45,
            optionFlow = -0.40,
            acceleration = -0.30,
            depth = -0.60,
        )
        val u = e.update(
            engine = EngineId.ENGINE_2_AVWAP_LIQUIDITY, side = PositionSide.CE, entryPrice = 100.0,
            currentPrice = 101.0, timestamp = start + 4 * 60_000, currentStopPrice = plan.stopPrice,
            previousHighestPrice = 106.0, target1Hit = false, quantity = 40, strategy = "E2",
            oppositeSignal = false, indexInvalidated = false, quality = bad,
        )
        assertEquals(AdaptiveExitEngine.ExitReason.ORDER_FLOW_DETERIORATION, u.exitReason)
    }

    @Test fun strong_runner_keeps_stop_profitable() {
        val e = AdaptiveExitEngine()
        val plan = e.open(EngineId.ENGINE_1_TREND, PositionSide.CE, 100.0, start)
        val strong = quality(
            decision = V76ExecutionQualityEngine.Decision.EARLY_CONFIRMED,
            score = 86,
            direction = 52,
            orderFlow = 0.35,
            optionFlow = 0.30,
            acceleration = 0.25,
            depth = 0.45,
        )
        val u = e.update(
            engine = EngineId.ENGINE_1_TREND, side = PositionSide.CE, entryPrice = 100.0,
            currentPrice = 130.0, timestamp = start + 10 * 60_000, currentStopPrice = plan.stopPrice,
            previousHighestPrice = 135.0, target1Hit = true, quantity = 65, strategy = "TREND",
            oppositeSignal = false, indexInvalidated = false, quality = strong,
        )
        assertTrue(u.strongTrend)
        assertTrue(u.stopPrice > 100.0)
        assertNull(u.exitReason)
    }

    private fun quality(
        decision: V76ExecutionQualityEngine.Decision,
        score: Int,
        direction: Int,
        orderFlow: Double,
        optionFlow: Double,
        acceleration: Double,
        depth: Double,
    ) = V76ExecutionQualityEngine.Result(
        decision = decision,
        score = score,
        directionScore = direction,
        entryQualityScore = (score - direction).coerceIn(0, 40),
        orderFlowProxy = orderFlow,
        relativeActivity = 1.5,
        optionOiImpulse = optionFlow,
        optionFlowProxy = optionFlow,
        acceleration = acceleration,
        extensionAtr = 1.2,
        depthImbalance = depth,
        micropricePressure = depth,
        totalBookPressure = depth,
        wallPressure = depth,
        depthLevels = 30,
        reasons = emptyList(),
    )
}
