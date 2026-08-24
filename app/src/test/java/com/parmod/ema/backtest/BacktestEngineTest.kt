package com.parmod.ema.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestEngineTest {
    @Test
    fun calculatesCoreMetricsDeterministically() {
        val trades = listOf(
            BacktestEngine.Trade(0, 60_000, "CE", 100.0, 120.0, 10, 85, "2026-07-02"),
            BacktestEngine.Trade(60_000, 180_000, "PE", 80.0, 70.0, 10, 82, "2026-07-09"),
            BacktestEngine.Trade(180_000, 300_000, "CE", 90.0, 105.0, 10, 78, "2026-07-16"),
        )

        val report = BacktestEngine().evaluate(trades)

        assertEquals(3, report.trades)
        assertEquals(2, report.wins)
        assertEquals(1, report.losses)
        assertEquals(250.0, report.netPnl, 0.001)
        assertEquals(100.0, report.maxDrawdown, 0.001)
        assertTrue(report.profitFactor > 3.0)
        assertEquals(2.0 / 3.0, report.signalPrecision, 0.001)
    }
}
