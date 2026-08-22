package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoricalDownloadPlannerTest {
    @Test
    fun trailing_window_keeps_only_expiries_inside_requested_months() {
        val today = LocalDate.of(2026, 8, 22)
        val available = listOf(
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 7, 23),
            LocalDate.of(2026, 8, 6),
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 27),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 7, 23), LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 20)),
            HistoricalDownloadPlanner.expiries(available, 1, today),
        )
    }

    @Test
    fun full_keeps_all_closed_expiries_but_never_future_expiry() {
        val today = LocalDate.of(2026, 8, 22)
        val available = listOf(
            LocalDate.of(2025, 1, 2),
            LocalDate.of(2026, 8, 20),
            LocalDate.of(2026, 8, 27),
        )
        assertEquals(
            listOf(LocalDate.of(2025, 1, 2), LocalDate.of(2026, 8, 20)),
            HistoricalDownloadPlanner.expiries(available, PrelabelledTrainingWindowPlan.FULL, today),
        )
    }

    @Test
    fun centre_radius_five_selects_eleven_strikes_with_ce_and_pe() {
        val expiry = LocalDate.of(2026, 8, 20)
        val contracts = buildList {
            (0..20).forEach { i ->
                val strike = 20_000.0 + i * 50.0
                add(contract("CE-$i", expiry, strike, "CE"))
                add(contract("PE-$i", expiry, strike, "PE"))
            }
        }
        val selected = HistoricalDownloadPlanner.selectContracts(contracts, 5)
        val strikes = selected.map { it.strike }.distinct()
        assertEquals(11, strikes.size)
        assertEquals(22, selected.size)
        assertTrue(strikes.contains(20_500.0))
        strikes.forEach { strike ->
            assertEquals(setOf("CE", "PE"), selected.filter { it.strike == strike }.map { it.optionType }.toSet())
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
