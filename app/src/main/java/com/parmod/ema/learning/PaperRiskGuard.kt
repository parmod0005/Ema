package com.parmod.ema.learning

import java.time.Instant
import java.time.ZoneId

/** Hard paper-trading gates. These controls can only reduce trading eligibility. */
class PaperRiskGuard(
    private val maximumDailyLossPct: Double = 2.0,
    private val maximumConsecutiveLosses: Int = 3,
) {
    data class Status(
        val locked: Boolean,
        val dailyLossLocked: Boolean,
        val consecutiveLossLocked: Boolean,
        val tradeCountLocked: Boolean,
        val todayPnl: Double,
        val todayTrades: Int,
        val consecutiveLosses: Int,
        val reason: String,
    )

    fun evaluate(
        outcomes: List<AdaptivePaperLearningEngine.PaperTradeOutcome>,
        startingCapital: Double,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Status {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val todaysTrades = outcomes.filter {
            Instant.ofEpochMilli(it.closedAtMillis).atZone(zoneId).toLocalDate() == today
        }
        val todayPnl = todaysTrades.sumOf { it.pnl }
        val dailyLimit = startingCapital.coerceAtLeast(1.0) * maximumDailyLossPct / 100.0
        val dailyLossLocked = todayPnl <= -dailyLimit

        var consecutiveLosses = 0
        for (trade in todaysTrades.sortedByDescending { it.closedAtMillis }) {
            if (trade.pnl < 0.0) consecutiveLosses++ else break
        }
        val consecutiveLossLocked = consecutiveLosses >= maximumConsecutiveLosses
        val reason = when {
            dailyLossLocked -> "Daily paper loss limit reached"
            consecutiveLossLocked -> "$consecutiveLosses consecutive paper losses"
            else -> "Paper risk gates clear"
        }
        return Status(
            locked = dailyLossLocked || consecutiveLossLocked,
            dailyLossLocked = dailyLossLocked,
            consecutiveLossLocked = consecutiveLossLocked,
            tradeCountLocked = false,
            todayPnl = todayPnl,
            todayTrades = todaysTrades.size,
            consecutiveLosses = consecutiveLosses,
            reason = reason,
        )
    }
}
