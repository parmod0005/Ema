package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import kotlin.math.max

/**
 * Causal option-premium labeler used by historical AI training.
 * Signal is formed on bar N; simulated entry is bar N+1 open.
 * If stop and target are both touched inside one OHLC candle, stop wins so
 * ambiguous intrabar ordering cannot create optimistic training labels.
 */
object HistoricalPremiumLabeler {
    data class Config(
        val horizonBars: Int = 5,
        val targetReturn: Double = 0.10,
        val stopReturn: Double = 0.075,
        val slippageEachSide: Double = 0.0015,
        val flatRoundTripCost: Double = 70.80,
    )

    enum class ExitReason { TARGET, STOP, TIMEOUT }

    data class Outcome(
        val success: Boolean,
        val entryPrice: Double,
        val exitPrice: Double,
        val mfeReturn: Double,
        val maeReturn: Double,
        val netReturn: Double,
        val exitReason: ExitReason,
        val barsObserved: Int,
    )

    fun label(
        candles: List<UpstoxPlusHistoricalClient.Candle>,
        signalIndex: Int,
        lotSize: Int,
        config: Config = Config(),
    ): Outcome? {
        require(config.horizonBars >= 1)
        require(config.targetReturn > 0.0)
        require(config.stopReturn > 0.0)
        require(config.slippageEachSide >= 0.0)
        require(config.flatRoundTripCost >= 0.0)
        if (signalIndex < 0 || signalIndex + 1 >= candles.size) return null

        val entryIndex = signalIndex + 1
        val rawEntry = candles[entryIndex].open
        if (!rawEntry.isFinite() || rawEntry <= 0.0) return null
        val quantity = lotSize.coerceAtLeast(1)
        val lastIndex = minOf(candles.lastIndex, entryIndex + config.horizonBars - 1)
        val future = candles.subList(entryIndex, lastIndex + 1)
        if (future.isEmpty()) return null

        val target = rawEntry * (1.0 + config.targetReturn)
        val stop = rawEntry * (1.0 - config.stopReturn)
        val maxHigh = future.maxOf { it.high }
        val minLow = future.minOf { it.low }
        val mfe = (maxHigh - rawEntry) / rawEntry
        val mae = (minLow - rawEntry) / rawEntry

        var reason = ExitReason.TIMEOUT
        var rawExit = future.last().close
        var barsObserved = future.size
        for ((offset, candle) in future.withIndex()) {
            val stopHit = candle.low <= stop
            val targetHit = candle.high >= target
            if (stopHit) {
                // Conservative ordering: STOP wins when the same candle also hits target.
                reason = ExitReason.STOP
                rawExit = stop
                barsObserved = offset + 1
                break
            }
            if (targetHit) {
                reason = ExitReason.TARGET
                rawExit = target
                barsObserved = offset + 1
                break
            }
        }

        val paidEntry = rawEntry * (1.0 + config.slippageEachSide)
        val receivedExit = rawExit * (1.0 - config.slippageEachSide)
        val gross = (receivedExit - paidEntry) * quantity
        val net = gross - config.flatRoundTripCost
        val deployed = max(paidEntry * quantity, 1.0)
        val netReturn = net / deployed
        return Outcome(
            success = net > 0.0,
            entryPrice = paidEntry,
            exitPrice = receivedExit,
            mfeReturn = mfe,
            maeReturn = mae,
            netReturn = netReturn,
            exitReason = reason,
            barsObserved = barsObserved,
        )
    }
}
