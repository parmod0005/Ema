package com.parmod.ema.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionEngineV2Test {
    private val engine = ExecutionEngineV2()

    @Test
    fun initialStopAndTargetAreCreated() {
        val state = engine.open(100.0)
        assertEquals(85.0, state.stopPrice, 0.0001)
        assertEquals(130.0, state.targetPrice, 0.0001)
    }

    @Test
    fun breakevenMovesStopAboveEntry() {
        val opened = engine.open(100.0)
        val update = engine.update(opened, 112.0, oppositeSignal = false)
        assertTrue(update.state.breakevenActive)
        assertTrue(update.state.stopPrice >= 101.0)
    }

    @Test
    fun trailingStopFollowsHighestPriceOnlyUpward() {
        val opened = engine.open(100.0)
        val first = engine.update(opened, 120.0, oppositeSignal = false)
        val second = engine.update(first.state, 116.0, oppositeSignal = false)
        assertTrue(first.state.trailingActive)
        assertEquals(first.state.stopPrice, second.state.stopPrice, 0.0001)
    }

    @Test
    fun oppositeSignalExitsImmediately() {
        val opened = engine.open(100.0)
        val update = engine.update(opened, 105.0, oppositeSignal = true)
        assertEquals(ExecutionEngineV2.ExitReason.OPPOSITE_SIGNAL, update.exitReason)
    }
}
