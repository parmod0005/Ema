package com.parmod.ema.engine

import com.parmod.ema.model.EngineId
import com.parmod.ema.model.PositionSide
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic adaptive long-option exit manager used by PAPER execution.
 *
 * Safety properties:
 * - a hard catastrophic stop exists immediately at entry,
 * - the stop is monotonic and is never widened,
 * - premium volatility/structure uses only observations received after entry,
 * - D30/order-flow can tighten or close a trade but can never increase risk,
 * - T1 is a partial-profit/runner transition, not a mandatory full exit,
 * - no AI model is allowed to move the stop farther away.
 */
class AdaptiveExitEngine {
    enum class ExitReason {
        PREMIUM_STOP,
        ADAPTIVE_TRAILING_STOP,
        OPPOSITE_SIGNAL,
        INDEX_INVALIDATION,
        ORDER_FLOW_DETERIORATION,
        TIME_STOP,
        RUNNER_TIME_STOP,
        SESSION_EXIT,
    }

    data class Plan(
        val stopPrice: Double,
        val target1Price: Double,
        val maxHoldMinutes: Int,
        val runnerMaxHoldMinutes: Int,
    )

    data class Update(
        val highestPrice: Double,
        val stopPrice: Double,
        val target1Price: Double,
        val breakevenActive: Boolean,
        val trailingActive: Boolean,
        val partialTrigger: Boolean,
        val strongTrend: Boolean,
        val atrPercent: Double,
        val d30Composite: Double,
        val exitReason: ExitReason? = null,
        val diagnostic: String = "",
        val maxHoldMinutes: Int,
        val runnerMaxHoldMinutes: Int,
    )

    private data class Profile(
        val baselineStopPct: Double,
        val minStopPct: Double,
        val maxStopPct: Double,
        val atrStopMultiple: Double,
        val baseTarget1Pct: Double,
        val maxTarget1Pct: Double,
        val costLockTriggerPct: Double,
        val preT1TrailTriggerPct: Double,
        val preT1PeakRetain: Double,
        val strongRunnerRetain: Double,
        val normalRunnerRetain: Double,
        val weakRunnerRetain: Double,
        val trailAtrMultiple: Double,
        val maxHoldMinutes: Int,
        val runnerMaxHoldMinutes: Int,
        val flowSensitivity: Double,
    )

    private data class MinuteBar(
        val minute: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
    )

    private data class Tracker(
        val engine: EngineId,
        val side: PositionSide,
        val entryPrice: Double,
        val openedAt: Long,
        val profile: Profile,
        var lastPrice: Double,
        var ewmaAbsReturn: Double = 0.0,
        var working: MinuteBar? = null,
        val bars: ArrayDeque<MinuteBar> = ArrayDeque(),
    )

    private val trackers = mutableMapOf<EngineId, Tracker>()
    private val zone = ZoneId.of("Asia/Kolkata")

    @Synchronized
    fun open(
        engine: EngineId,
        side: PositionSide,
        entryPrice: Double,
        timestamp: Long,
        strategy: String = "",
    ): Plan {
        require(entryPrice > 0.0)
        val p = profile(engine, strategy)
        trackers[engine] = Tracker(engine, side, entryPrice, timestamp, p, entryPrice)
        return Plan(
            stopPrice = entryPrice * (1.0 - p.baselineStopPct / 100.0),
            target1Price = entryPrice * (1.0 + p.baseTarget1Pct / 100.0),
            maxHoldMinutes = p.maxHoldMinutes,
            runnerMaxHoldMinutes = p.runnerMaxHoldMinutes,
        )
    }

    @Synchronized fun close(engine: EngineId) { trackers.remove(engine) }
    @Synchronized fun reset() { trackers.clear() }

    @Synchronized
    fun update(
        engine: EngineId,
        side: PositionSide,
        entryPrice: Double,
        currentPrice: Double,
        timestamp: Long,
        currentStopPrice: Double,
        previousHighestPrice: Double,
        target1Hit: Boolean,
        quantity: Int,
        strategy: String,
        oppositeSignal: Boolean,
        indexInvalidated: Boolean,
        quality: V76ExecutionQualityEngine.Result?,
    ): Update {
        require(entryPrice > 0.0)
        require(currentPrice >= 0.0)
        val tracker = trackers[engine]?.takeIf {
            abs(it.entryPrice - entryPrice) <= max(0.01, entryPrice * 1e-6) && it.side == side
        } ?: run {
            open(engine, side, entryPrice, timestamp, strategy)
            trackers.getValue(engine)
        }
        ingest(tracker, currentPrice, timestamp)
        val p = tracker.profile
        val highest = max(previousHighestPrice, currentPrice)
        val peakGain = max(0.0, highest - entryPrice)
        val peakPct = peakGain / entryPrice * 100.0
        val currentGainPct = (currentPrice - entryPrice) / entryPrice * 100.0
        val atr = premiumAtr(tracker)
        val atrPct = if (atr > 0.0) atr / entryPrice * 100.0 else 0.0
        val fallbackStopPct = p.baselineStopPct
        val atrStopPct = if (atrPct > 0.0) (atrPct * p.atrStopMultiple).coerceIn(p.minStopPct, p.maxStopPct) else fallbackStopPct
        val hardFloor = entryPrice * (1.0 - p.maxStopPct / 100.0)
        val initialAdaptive = entryPrice * (1.0 - atrStopPct / 100.0)
        var stop = max(currentStopPrice, max(hardFloor, initialAdaptive))

        val recentStructure = recentSwingLow(tracker)
        if (recentStructure > 0.0 && atr > 0.0) {
            val structureStop = recentStructure - atr * 0.18
            // Structure is allowed to tighten risk only after price has made progress.
            if (peakPct >= 3.0) stop = max(stop, structureStop)
        }

        val d30 = quality?.let(::d30Composite) ?: 0.0
        val strong = strongTrend(quality)
        val weak = weakTrend(quality, p.flowSensitivity)
        val severe = severeDeterioration(quality, p.flowSensitivity)

        val costPerUnit = if (quantity > 0) PAPER_ROUND_TRIP_COST_INR / quantity else 0.0
        val costLock = entryPrice + costPerUnit * 1.10 + entryPrice * 0.0025
        var breakeven = stop > entryPrice
        var trailing = false

        if (peakPct >= p.costLockTriggerPct && peakGain > 0.0) {
            stop = max(stop, costLock)
            breakeven = stop > entryPrice
        }

        if (!target1Hit && peakPct >= p.preT1TrailTriggerPct && peakGain > 0.0) {
            stop = max(stop, entryPrice + peakGain * p.preT1PeakRetain)
            if (atr > 0.0) stop = max(stop, highest - atr * p.trailAtrMultiple)
            trailing = true
        }

        if (weak && peakPct >= 4.0 && peakGain > 0.0) {
            // Flow deterioration cannot create risk; it only protects more of an existing gain.
            stop = max(stop, entryPrice + peakGain * if (engine == EngineId.ENGINE_2_AVWAP_LIQUIDITY) 0.78 else 0.68)
            trailing = true
        }

        if (target1Hit && peakGain > 0.0) {
            val retain = when {
                strong -> p.strongRunnerRetain
                weak -> p.weakRunnerRetain
                else -> p.normalRunnerRetain
            }
            stop = max(stop, entryPrice + peakGain * retain)
            if (atr > 0.0) {
                val atrTrail = highest - atr * if (strong) p.trailAtrMultiple * 1.20 else p.trailAtrMultiple
                stop = max(stop, atrTrail)
            }
            trailing = true
            breakeven = stop > entryPrice
        }

        // Never place a long-option stop above the current market solely due to a newly
        // computed structure/ATR value; that would manufacture an impossible fill.
        if (currentPrice > 0.0 && stop > currentPrice && currentPrice > currentStopPrice) {
            stop = max(currentStopPrice, currentPrice * 0.998)
        }
        stop = max(currentStopPrice, stop)

        val riskPct = ((entryPrice - max(hardFloor, initialAdaptive)) / entryPrice * 100.0).coerceAtLeast(0.1)
        var targetPct = max(p.baseTarget1Pct, riskPct * 1.20)
        if (strong) targetPct += 2.0
        targetPct = targetPct.coerceAtMost(p.maxTarget1Pct)
        val target1 = entryPrice * (1.0 + targetPct / 100.0)
        val partial = !target1Hit && currentPrice >= target1

        val heldMinutes = (timestamp - tracker.openedAt).coerceAtLeast(0L) / 60_000.0
        val holdLimit = if (target1Hit) p.runnerMaxHoldMinutes else p.maxHoldMinutes
        val local = Instant.ofEpochMilli(timestamp).atZone(zone)
        val minuteOfDay = local.hour * 60 + local.minute

        val exit = when {
            oppositeSignal -> ExitReason.OPPOSITE_SIGNAL
            indexInvalidated -> ExitReason.INDEX_INVALIDATION
            currentPrice <= stop -> if (trailing || target1Hit || breakeven) ExitReason.ADAPTIVE_TRAILING_STOP else ExitReason.PREMIUM_STOP
            shouldExitOnFlow(engine, severe, quality, heldMinutes, currentGainPct, target1Hit) -> ExitReason.ORDER_FLOW_DETERIORATION
            minuteOfDay >= FORCE_EXIT_MINUTE -> ExitReason.SESSION_EXIT
            heldMinutes >= holdLimit -> if (target1Hit) ExitReason.RUNNER_TIME_STOP else ExitReason.TIME_STOP
            else -> null
        }

        val diag = buildString {
            append("ATR ")
            append("%.1f%%".format(atrPct))
            append(" · D30 ")
            append("%.2f".format(d30))
            append(" · ")
            append(if (strong) "STRONG" else if (weak) "WEAK" else "NORMAL")
            append(" · T1 ")
            append("%.1f%%".format(targetPct))
        }

        return Update(
            highestPrice = highest,
            stopPrice = stop,
            target1Price = target1,
            breakevenActive = breakeven,
            trailingActive = trailing,
            partialTrigger = partial,
            strongTrend = strong,
            atrPercent = atrPct,
            d30Composite = d30,
            exitReason = exit,
            diagnostic = diag,
            maxHoldMinutes = p.maxHoldMinutes,
            runnerMaxHoldMinutes = p.runnerMaxHoldMinutes,
        )
    }

    private fun profile(engine: EngineId, strategy: String): Profile = when (engine) {
        EngineId.ENGINE_1_TREND -> Profile(
            baselineStopPct = 13.0, minStopPct = 8.0, maxStopPct = 15.0, atrStopMultiple = 1.45,
            baseTarget1Pct = 16.0, maxTarget1Pct = 24.0, costLockTriggerPct = 8.0,
            preT1TrailTriggerPct = 12.0, preT1PeakRetain = 0.42,
            strongRunnerRetain = 0.65, normalRunnerRetain = 0.74, weakRunnerRetain = 0.84,
            trailAtrMultiple = 1.55, maxHoldMinutes = 45, runnerMaxHoldMinutes = 75, flowSensitivity = 0.90,
        )
        EngineId.ENGINE_2_AVWAP_LIQUIDITY -> Profile(
            baselineStopPct = 12.0, minStopPct = 8.0, maxStopPct = 14.0, atrStopMultiple = 1.30,
            baseTarget1Pct = 14.0, maxTarget1Pct = 20.0, costLockTriggerPct = 7.0,
            preT1TrailTriggerPct = 10.0, preT1PeakRetain = 0.48,
            strongRunnerRetain = 0.72, normalRunnerRetain = 0.80, weakRunnerRetain = 0.88,
            trailAtrMultiple = 1.35, maxHoldMinutes = 25, runnerMaxHoldMinutes = 45, flowSensitivity = 1.20,
        )
        EngineId.ENGINE_3_V76_SCALPER -> if (strategy.uppercase().contains("BREAKOUT")) Profile(
            baselineStopPct = 10.5, minStopPct = 8.0, maxStopPct = 12.0, atrStopMultiple = 1.20,
            baseTarget1Pct = 16.0, maxTarget1Pct = 20.0, costLockTriggerPct = 6.0,
            preT1TrailTriggerPct = 8.0, preT1PeakRetain = 0.50,
            strongRunnerRetain = 0.74, normalRunnerRetain = 0.82, weakRunnerRetain = 0.90,
            trailAtrMultiple = 1.25, maxHoldMinutes = 12, runnerMaxHoldMinutes = 30, flowSensitivity = 1.05,
        ) else Profile(
            baselineStopPct = 12.5, minStopPct = 9.0, maxStopPct = 14.0, atrStopMultiple = 1.35,
            baseTarget1Pct = 20.0, maxTarget1Pct = 24.0, costLockTriggerPct = 7.0,
            preT1TrailTriggerPct = 9.0, preT1PeakRetain = 0.48,
            strongRunnerRetain = 0.72, normalRunnerRetain = 0.80, weakRunnerRetain = 0.88,
            trailAtrMultiple = 1.35, maxHoldMinutes = 20, runnerMaxHoldMinutes = 40, flowSensitivity = 1.00,
        )
    }

    private fun ingest(t: Tracker, price: Double, timestamp: Long) {
        if (price <= 0.0 || timestamp <= 0L) return
        if (t.lastPrice > 0.0) {
            val r = abs(price - t.lastPrice) / t.lastPrice
            t.ewmaAbsReturn = if (t.ewmaAbsReturn == 0.0) r else t.ewmaAbsReturn * 0.88 + r * 0.12
        }
        t.lastPrice = price
        val minute = timestamp / 60_000L
        val w = t.working
        when {
            w == null -> t.working = MinuteBar(minute, price, price, price, price)
            minute < w.minute -> Unit
            minute == w.minute -> {
                w.high = max(w.high, price)
                w.low = min(w.low, price)
                w.close = price
            }
            else -> {
                t.bars.addLast(w.copy())
                while (t.bars.size > MAX_BARS) t.bars.removeFirst()
                t.working = MinuteBar(minute, price, price, price, price)
            }
        }
    }

    private fun premiumAtr(t: Tracker): Double {
        val bars = t.bars.toList().takeLast(14)
        if (bars.size >= 3) {
            var previousClose = bars.first().close
            val trs = mutableListOf<Double>()
            bars.forEachIndexed { i, b ->
                val tr = if (i == 0) b.high - b.low else max(b.high - b.low, max(abs(b.high - previousClose), abs(b.low - previousClose)))
                if (tr.isFinite() && tr >= 0.0) trs += tr
                previousClose = b.close
            }
            if (trs.isNotEmpty()) return trs.average()
        }
        // Tick-volatility fallback during the first few minutes. It intentionally has a
        // floor so startup noise cannot tighten the catastrophic stop immediately.
        return max(t.entryPrice * t.ewmaAbsReturn * 4.0, t.entryPrice * 0.015)
    }

    private fun recentSwingLow(t: Tracker): Double {
        val bars = t.bars.toList().takeLast(4)
        return bars.minOfOrNull { it.low } ?: 0.0
    }

    private fun d30Composite(q: V76ExecutionQualityEngine.Result): Double =
        q.depthImbalance * 0.40 + q.micropricePressure * 0.20 + q.totalBookPressure * 0.20 + q.wallPressure * 0.20

    private fun strongTrend(q: V76ExecutionQualityEngine.Result?): Boolean {
        if (q == null) return false
        val d30 = d30Composite(q)
        val bookOkay = q.depthLevels < 10 || d30 >= 0.05
        return q.score >= 72 && q.directionScore >= 42 && q.orderFlowProxy >= 0.08 &&
            q.optionFlowProxy >= -0.03 && q.acceleration >= 0.0 && bookOkay &&
            q.decision != V76ExecutionQualityEngine.Decision.EXHAUSTION_RISK
    }

    private fun weakTrend(q: V76ExecutionQualityEngine.Result?, sensitivity: Double): Boolean {
        if (q == null) return false
        val d30 = d30Composite(q)
        val bookWeak = q.depthLevels >= 10 && d30 < -0.08 / sensitivity
        return q.score < (52.0 * sensitivity).coerceIn(45.0, 62.0) ||
            q.orderFlowProxy < -0.10 / sensitivity || q.optionFlowProxy < -0.12 / sensitivity ||
            q.acceleration < -0.08 / sensitivity || bookWeak
    }

    private fun severeDeterioration(q: V76ExecutionQualityEngine.Result?, sensitivity: Double): Boolean {
        if (q == null) return false
        val d30 = d30Composite(q)
        val bookConflict = q.depthLevels >= 10 && d30 < -0.18 / sensitivity
        val flowConflict = q.orderFlowProxy < -0.18 / sensitivity && q.optionFlowProxy < -0.16 / sensitivity
        return (q.decision == V76ExecutionQualityEngine.Decision.EXHAUSTION_RISK && (bookConflict || flowConflict || q.score < 42)) ||
            (bookConflict && flowConflict)
    }

    private fun shouldExitOnFlow(
        engine: EngineId,
        severe: Boolean,
        quality: V76ExecutionQualityEngine.Result?,
        heldMinutes: Double,
        currentGainPct: Double,
        target1Hit: Boolean,
    ): Boolean {
        if (!severe || quality == null || heldMinutes < 1.0) return false
        return when (engine) {
            EngineId.ENGINE_1_TREND -> target1Hit || currentGainPct >= 3.0 || heldMinutes >= 5.0
            EngineId.ENGINE_2_AVWAP_LIQUIDITY -> currentGainPct >= -2.0 || heldMinutes >= 3.0
            EngineId.ENGINE_3_V76_SCALPER -> target1Hit || currentGainPct >= 2.0 || heldMinutes >= 3.0
        }
    }

    companion object {
        const val PAPER_ROUND_TRIP_COST_INR = 70.80
        const val PAPER_EXTRA_EXIT_ORDER_COST_INR = 35.40
        const val TARGET1_PARTIAL_FRACTION = 0.50
        const val FORCE_EXIT_MINUTE = 15 * 60 + 15
        private const val MAX_BARS = 30
    }
}
