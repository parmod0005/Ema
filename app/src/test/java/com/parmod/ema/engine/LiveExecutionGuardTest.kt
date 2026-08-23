package com.parmod.ema.engine

import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.LiveArmMode
import com.parmod.ema.model.LiveExecutionGuard
import com.parmod.ema.model.LiveGateInput
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.MarketSelection
import com.parmod.ema.model.SignalTimeframe
import com.parmod.ema.model.TradingRiskConfig
import com.parmod.ema.model.UpstoxComplianceRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveExecutionGuardTest {
    private val risk = TradingRiskConfig()
    private val valid = LiveGateInput(
        executionMode = ExecutionMode.LIVE,
        armMode = LiveArmMode.AUTO_ARMED,
        automatic = true,
        connected = true,
        upstoxTokenPresent = true,
        instrumentKeyPresent = true,
        quantity = 65,
        plannedRiskInr = 1_500.0,
        riskLocked = false,
        emergencyKill = false,
        marketOpen = true,
        entriesAllowed = true,
        tickAgeMillis = 100L,
        confidence = 82,
        spreadPercent = 0.5,
        tradesToday = 0,
        risk = risk,
    )

    @Before
    fun resetCompliance() {
        UpstoxComplianceRegistry.configureAlgoName("VARDHANI_TEST")
        UpstoxComplianceRegistry.clearProtectionFault()
    }

    @Test
    fun both_market_selection_is_really_dual_market() {
        assertTrue(MarketSelection.BOTH.includes(MarketIndex.NIFTY))
        assertTrue(MarketSelection.BOTH.includes(MarketIndex.SENSEX))
        assertTrue(MarketSelection.BOTH.indexes.size == 2)
    }

    @Test
    fun exact_v76_profile_is_one_three_five() {
        assertEquals(SignalTimeframe.M1, EngineTimeframeConfig.E3_DEFAULT.trigger)
        assertEquals(SignalTimeframe.M3, EngineTimeframeConfig.E3_DEFAULT.setup)
        assertEquals(SignalTimeframe.M5, EngineTimeframeConfig.E3_DEFAULT.bias)
    }

    @Test
    fun automatic_live_order_requires_explicit_auto_arm() {
        assertTrue(LiveExecutionGuard.evaluate(valid).allowed)
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(armMode = LiveArmMode.DISARMED)).allowed)
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(armMode = LiveArmMode.MANUAL_ONLY)).allowed)
    }

    @Test
    fun automatic_live_requires_configured_algo_name() {
        UpstoxComplianceRegistry.configureAlgoName("")
        assertFalse(LiveExecutionGuard.evaluate(valid).allowed)
    }

    @Test
    fun broker_protection_fault_blocks_new_live_entries() {
        UpstoxComplianceRegistry.setProtectionFault("test protection failure")
        assertFalse(LiveExecutionGuard.evaluate(valid).allowed)
    }

    @Test
    fun stale_market_data_blocks_live_order() {
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(tickAgeMillis = risk.maximumTickAgeMillis + 1)).allowed)
    }

    @Test
    fun per_trade_risk_blocks_oversized_live_order() {
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(plannedRiskInr = risk.maxRiskPerTradeInr + 1.0)).allowed)
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(plannedRiskInr = Double.NaN)).allowed)
    }

    @Test
    fun risk_and_kill_switches_fail_closed() {
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(riskLocked = true)).allowed)
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(emergencyKill = true)).allowed)
        assertFalse(LiveExecutionGuard.evaluate(valid.copy(marketOpen = false)).allowed)
    }

    @Test
    fun manual_live_can_be_armed_without_auto_authority() {
        val manual = valid.copy(automatic = false, armMode = LiveArmMode.MANUAL_ONLY, confidence = 0)
        assertTrue(LiveExecutionGuard.evaluate(manual).allowed)
    }
}
