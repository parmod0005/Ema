package com.parmod.ema.engine

import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Engine 2: session-anchored VWAP + volume-profile proxy + liquidity sweep + order-flow proxy.
 *
 * NIFTY/SENSEX index feed does not expose traded volume in Market Data Feed V3, so live
 * volume is represented by tick participation. The dashboard labels this as a proxy.
 * AUTO entries require FULL CONFLUENCE; partial combinations are diagnostic only.
 */
class AvwapLiquidityEngine {
    data class Bar(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val tickVolume: Long,
        val buyTicks: Long,
        val sellTicks: Long,
        val timestamp: Long,
    )

    data class Evaluation(
        val signal: SignalSnapshot,
        val avwap: Double,
        val poc: Double,
        val orderFlow: Double,
        val bullishSweep: Boolean,
        val bearishSweep: Boolean,
    )

    fun evaluate(bars: List<Bar>): Evaluation {
        if (bars.size < 24) return wait("Collecting 1-minute structure ${bars.size}/24")

        val last = bars.last()
        val avwap = anchoredVwap(bars)
        val poc = volumeProfilePoc(bars.takeLast(60))
        val atr = averageTrueRange(bars, 14).coerceAtLeast(last.close * 0.0004)
        val flow = orderFlowProxy(bars.takeLast(5))

        val sweepWindow = bars.dropLast(1).takeLast(12)
        val priorLow = sweepWindow.minOf { it.low }
        val priorHigh = sweepWindow.maxOf { it.high }
        val bullishSweep = last.low < priorLow && last.close > priorLow && last.close > last.open
        val bearishSweep = last.high > priorHigh && last.close < priorHigh && last.close < last.open

        val aboveAvwap = last.close > avwap + atr * 0.05
        val belowAvwap = last.close < avwap - atr * 0.05
        val abovePoc = last.close > poc
        val belowPoc = last.close < poc
        val bullishFlow = flow >= 0.18
        val bearishFlow = flow <= -0.18

        val bullParts = listOf(aboveAvwap, abovePoc, bullishSweep, bullishFlow).count { it }
        val bearParts = listOf(belowAvwap, belowPoc, bearishSweep, bearishFlow).count { it }

        val setup = when {
            bullParts == 4 || bearParts == 4 -> "AVWAP + VOLUME PROFILE + SWEEP + ORDER FLOW · FULL CONFLUENCE"
            (aboveAvwap && bullishSweep && bullishFlow) || (belowAvwap && bearishSweep && bearishFlow) -> "AVWAP + SWEEP + ORDER FLOW"
            (bullishSweep && bullishFlow) || (bearishSweep && bearishFlow) -> "LIQUIDITY SWEEP + ORDER-FLOW PROXY"
            (aboveAvwap && abovePoc) || (belowAvwap && belowPoc) -> "AVWAP + VOLUME PROFILE"
            (aboveAvwap && bullishSweep) || (belowAvwap && bearishSweep) -> "AVWAP + LIQUIDITY SWEEP"
            else -> "NO CONFLUENCE"
        }

        val fullBull = aboveAvwap && abovePoc && bullishSweep && bullishFlow
        val fullBear = belowAvwap && belowPoc && bearishSweep && bearishFlow
        val confidence = when {
            fullBull || fullBear -> 92
            max(bullParts, bearParts) == 3 -> 78
            max(bullParts, bearParts) == 2 -> 62
            else -> 40
        }
        val risk = atr * 0.9

        val reasons = mutableListOf<String>()
        reasons += "AVWAP ${format(avwap)} · POC ${format(poc)}"
        reasons += "Order-flow proxy ${format(flow)}"
        if (bullishSweep) reasons += "Sell-side liquidity swept and reclaimed"
        if (bearishSweep) reasons += "Buy-side liquidity swept and rejected"
        if (!(fullBull || fullBear)) reasons += "AUTO waits for full four-factor confluence"

        val signal = when {
            fullBull -> SignalSnapshot(
                action = SignalAction.BUY_CE,
                confidence = confidence,
                trend = TrendDirection.BULLISH,
                entry = last.close,
                stopLoss = last.close - risk,
                target = last.close + risk * 2.0,
                reasons = reasons,
                setup = setup,
            )
            fullBear -> SignalSnapshot(
                action = SignalAction.BUY_PE,
                confidence = confidence,
                trend = TrendDirection.BEARISH,
                entry = last.close,
                stopLoss = last.close + risk,
                target = last.close - risk * 2.0,
                reasons = reasons,
                setup = setup,
            )
            else -> SignalSnapshot(
                action = SignalAction.WAIT,
                confidence = confidence,
                trend = TrendDirection.NEUTRAL,
                entry = null,
                stopLoss = null,
                target = null,
                reasons = reasons,
                setup = setup,
            )
        }
        return Evaluation(signal, avwap, poc, flow, bullishSweep, bearishSweep)
    }

    private fun wait(reason: String) = Evaluation(
        SignalSnapshot(SignalAction.WAIT, 0, TrendDirection.NEUTRAL, null, null, null, listOf(reason), "COLLECTING DATA"),
        0.0, 0.0, 0.0, false, false,
    )

    private fun anchoredVwap(bars: List<Bar>): Double {
        var pv = 0.0
        var volume = 0.0
        bars.forEach { bar ->
            val weight = bar.tickVolume.coerceAtLeast(1L).toDouble()
            val typical = (bar.high + bar.low + bar.close) / 3.0
            pv += typical * weight
            volume += weight
        }
        return if (volume > 0.0) pv / volume else bars.last().close
    }

    private fun volumeProfilePoc(bars: List<Bar>): Double {
        if (bars.isEmpty()) return 0.0
        val range = (bars.maxOf { it.high } - bars.minOf { it.low }).coerceAtLeast(1.0)
        val step = (range / 24.0).coerceAtLeast(0.5)
        val buckets = linkedMapOf<Long, Long>()
        bars.forEach { bar ->
            val key = (bar.close / step).toLong()
            buckets[key] = (buckets[key] ?: 0L) + bar.tickVolume.coerceAtLeast(1L)
        }
        val key = buckets.maxByOrNull { it.value }?.key ?: return bars.last().close
        return (key + 0.5) * step
    }

    private fun orderFlowProxy(bars: List<Bar>): Double {
        val buy = bars.sumOf { it.buyTicks }.toDouble()
        val sell = bars.sumOf { it.sellTicks }.toDouble()
        val total = buy + sell
        if (total <= 0.0) return 0.0
        return ((buy - sell) / total).coerceIn(-1.0, 1.0)
    }

    private fun averageTrueRange(bars: List<Bar>, period: Int): Double {
        if (bars.size < 2) return 0.0
        return bars.zipWithNext().takeLast(period).map { (previous, current) ->
            max(current.high - current.low, max(abs(current.high - previous.close), abs(current.low - previous.close)))
        }.average()
    }

    private fun format(value: Double) = "%.2f".format(value)
}
