package com.parmod.ema.backtest

import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic research engine used by the app's historical-data pipeline.
 * It does not fetch data and contains no broker execution code.
 */
class BacktestEngine {
    data class Trade(
        val entryEpochMs: Long,
        val exitEpochMs: Long,
        val side: String,
        val entryPrice: Double,
        val exitPrice: Double,
        val quantity: Int,
        val signalScore: Int,
        val expiry: String,
    ) {
        val pnl: Double get() = (exitPrice - entryPrice) * quantity
        val isWin: Boolean get() = pnl > 0.0
    }

    data class Report(
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val netPnl: Double,
        val grossProfit: Double,
        val grossLoss: Double,
        val profitFactor: Double,
        val expectancy: Double,
        val maxDrawdown: Double,
        val averageHoldingMinutes: Double,
        val signalPrecision: Double,
    )

    fun evaluate(trades: List<Trade>): Report {
        if (trades.isEmpty()) return Report(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val wins = trades.count { it.isWin }
        val losses = trades.size - wins
        val grossProfit = trades.filter { it.pnl > 0 }.sumOf { it.pnl }
        val grossLoss = abs(trades.filter { it.pnl < 0 }.sumOf { it.pnl })
        val net = trades.sumOf { it.pnl }
        val profitFactor = if (grossLoss == 0.0) {
            if (grossProfit > 0.0) Double.POSITIVE_INFINITY else 0.0
        } else grossProfit / grossLoss

        var equity = 0.0
        var peak = 0.0
        var maxDrawdown = 0.0
        trades.sortedBy { it.exitEpochMs }.forEach {
            equity += it.pnl
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, peak - equity)
        }

        val avgHolding = trades.map { (it.exitEpochMs - it.entryEpochMs).coerceAtLeast(0L) / 60_000.0 }.average()
        val qualified = trades.filter { it.signalScore >= 75 }
        val precision = if (qualified.isEmpty()) 0.0 else qualified.count { it.isWin }.toDouble() / qualified.size

        return Report(
            trades = trades.size,
            wins = wins,
            losses = losses,
            winRate = wins.toDouble() / trades.size,
            netPnl = net,
            grossProfit = grossProfit,
            grossLoss = grossLoss,
            profitFactor = profitFactor,
            expectancy = net / trades.size,
            maxDrawdown = maxDrawdown,
            averageHoldingMinutes = avgHolding,
            signalPrecision = precision,
        )
    }
}
