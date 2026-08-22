package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoricalDownloadPlannerTest {
    @Test
    fun trailing_window_keeps_only_completed_expiries_inside_requested_months() {
        val today = LocalDate.of(2026, 8, 22)
        val available = listOf(
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 7, 23),
            LocalDate.of(2026, 8, 6),
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 22),
            LocalDate.of(2026, 8, 27),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 20)),
            HistoricalDownloadPlanner.expiries(available, 1, today),
        )
    }

    @Test
    fun full_keeps_all_completed_expiries_but_never_today_or_future() {
        val today = LocalDate.of(2026, 8, 22)
        val available = listOf(
            LocalDate.of(2025, 1, 2),
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 22),
            LocalDate.of(2026, 8, 27),
        )
        val result = HistoricalDownloadPlanner.expiries(available, PrelabelledTrainingWindowPlan.FULL, today)
        assertEquals(listOf(LocalDate.of(2025, 1, 2), LocalDate.of(2026, 8, 20)), result)
        assertFalse(result.any { !it.isBefore(today) })
    }

    @Test
    fun causal_reference_spot_centres_selected_strike_band() {
        val expiry = LocalDate.of(2026, 8, 20)
        val contracts = contracts(expiry)
        val selected = HistoricalDownloadPlanner.selectContracts(
            contracts = contracts,
            strikesEachSide = 2,
            referenceSpot = 20_175.0,
        )
        val strikes = selected.map { it.strike }.distinct().sorted()
        assertEquals(listOf(20_050.0, 20_100.0, 20_150.0, 20_200.0, 20_250.0), strikes)
        assertEquals(10, selected.size)
        strikes.forEach { strike ->
            assertEquals(setOf("CE", "PE"), selected.filter { it.strike == strike }.map { it.optionType }.toSet())
        }
    }

    @Test
    fun tie_distance_is_deterministic_and_prefers_lower_strike_first() {
        val expiry = LocalDate.of(2026, 8, 20)
        val selected = HistoricalDownloadPlanner.selectContracts(
            contracts = contracts(expiry),
            strikesEachSide = 2,
            referenceSpot = 20_175.0,
        )
        val orderedDistinct = selected.map { it.strike }.distinct()
        assertTrue(orderedDistinct.contains(20_150.0))
        assertTrue(orderedDistinct.contains(20_200.0))
    }

    @Test
    fun fallback_without_reference_still_selects_balanced_ce_pe_band() {
        val expiry = LocalDate.of(2026, 8, 20)
        val selected = HistoricalDownloadPlanner.selectContracts(contracts(expiry), 5, referenceSpot = null)
        val strikes = selected.map { it.strike }.distinct()
        assertEquals(11, strikes.size)
        assertEquals(22, selected.size)
        assertTrue(strikes.contains(20_500.0))
    }

    private fun contracts(expiry: LocalDate) = buildList {
        (0..20).forEach { i ->
            val strike = 20_000.0 + i * 50.0
            add(contract("CE-$i", expiry, strike, "CE"))
            add(contract("PE-$i", expiry, strike, "PE"))
        }
    }

    private fun contract(key: String, expiry: LocalDate, strike: Double, type: String) =
        UpstoxPlusHistoricalClient.ExpiredContract(
            instrumentKey = key,
            expiry = expiry,
            strike = strike,
            optionType = type,
            lotSize = 20,
            tradingSymbol = "$strike$type",
        )
}
