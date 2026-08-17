package com.parmod.ema.engine

import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fast tick/option-chain execution-quality layer for Engine 3.
 *
 * V7.6 remains the directional/setup engine (5m -> 3m -> 1m). This class answers a different
 * question immediately before a paper entry: is the move still fresh and supported, or are we
 * chasing an exhausted impulse? It deliberately uses only data that the app really receives:
 * index ticks plus option LTP/OI/volume/bid/ask. "Order flow" and "buyer/seller activity" are
 * therefore explicitly proxies, not exchange aggressor-side trades.
 */
class V76ExecutionQualityEngine {
    enum class Decision { EARLY_CONFIRMED, WAIT_CONFIRMATION, WAIT_PULLBACK, EXHAUSTION_RISK }

    data class Result(
        val decision: Decision,
        val score: Int,
        val directionScore: Int,
        val entryQualityScore: Int,
        val orderFlowProxy: Double,
        val relativeActivity: Double,
        val optionOiImpulse: Double,
        val optionFlowProxy: Double,
        val acceleration: Double,
        val extensionAtr: Double,
        val reasons: List<String>,
    ) {
        val canEnter: Boolean get() = decision == Decision.EARLY_CONFIRMED
        val label: String get() = when (decision) {
            Decision.EARLY_CONFIRMED -> "EARLY CONFIRMED"
            Decision.WAIT_CONFIRMATION -> "WAIT · MICRO CONFIRMATION"
            Decision.WAIT_PULLBACK -> "EXTENDED · WAIT FOR PULLBACK"
            Decision.EXHAUSTION_RISK -> "EXHAUSTION / REVERSAL RISK"
        }
    }

    private data class SpotTick(val price: Double, val ts: Long)
    private data class OptionTick(
        val ltp: Double,
        val oi: Long,
        val volume: Long,
        val bid: Double,
        val ask: Double,
        val ts: Long,
    )

    private val spots = ArrayDeque<SpotTick>()
    private val options = linkedMapOf<String, ArrayDeque<OptionTick>>()

    fun reset() {
        spots.clear()
        options.clear()
    }

    fun ingestSpot(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        if (spots.lastOrNull()?.ts == timestamp && spots.lastOrNull()?.price == price) return
        spots.addLast(SpotTick(price, timestamp))
        while (spots.size > MAX_SPOT_TICKS) spots.removeFirst()
    }

    fun ingestOption(
        instrumentKey: String,
        ltp: Double?,
        oi: Long?,
        volume: Long?,
        bid: Double?,
        ask: Double?,
        timestamp: Long,
    ) {
        if (instrumentKey.isBlank() || ltp == null || ltp <= 0.0) return
        val q = options.getOrPut(instrumentKey) { ArrayDeque() }
        val tick = OptionTick(ltp, oi ?: 0L, volume ?: 0L, bid ?: 0.0, ask ?: 0.0, timestamp)
        val last = q.lastOrNull()
        if (last != null && last.ts == tick.ts && last.ltp == tick.ltp && last.oi == tick.oi && last.volume == tick.volume) return
        q.addLast(tick)
        while (q.size > MAX_OPTION_TICKS) q.removeFirst()
    }

    fun evaluate(side: PositionSide, chain: List<OptionQuote>, spot: Double): Result {
        val s = spots.toList()
        if (s.size < MIN_SPOT_TICKS || spot <= 0.0) {
            return Result(
                Decision.WAIT_CONFIRMATION, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                listOf("Microstructure warm-up ${s.size}/$MIN_SPOT_TICKS ticks"),
            )
        }

        val sign = if (side == PositionSide.CE) 1.0 else -1.0
        val prices = s.map { it.price }
        val microAtr = tickAtr(prices, 80)
        val ema21 = ema(prices, 21)
        val extension = if (microAtr > 0.0) abs(spot - ema21) / microAtr else 0.0

        // 1) Tick order-flow proxy: direction of successive index prints.
        val flowSample = prices.takeLast(90)
        var up = 0
        var down = 0
        flowSample.zipWithNext().forEach { (a, b) -> if (b > a) up++ else if (b < a) down++ }
        val orderFlow = if (up + down == 0) 0.0 else (up - down).toDouble() / (up + down)
        val alignedFlow = sign * orderFlow

        // 2) Velocity/acceleration: fresh moves should still be accelerating in the signal direction.
        val recentVelocity = directionalVelocity(s.takeLast(18), sign)
        val priorSlice = s.dropLast(min(12, s.size / 4)).takeLast(30)
        val priorVelocity = directionalVelocity(priorSlice, sign)
        val acceleration = recentVelocity - priorVelocity

        // 3) Liquidity sweep/reclaim: veto a fresh entry when the intended direction just swept a
        // prior extreme and immediately reclaimed it (classic exhaustion/reversal behaviour).
        val prior = prices.dropLast(10).takeLast(120)
        val recent = prices.takeLast(10)
        val priorLow = prior.minOrNull() ?: spot
        val priorHigh = prior.maxOrNull() ?: spot
        val recentLow = recent.minOrNull() ?: spot
        val recentHigh = recent.maxOrNull() ?: spot
        val sweepBuffer = max(microAtr * 0.40, spot * 0.000025)
        val bearishSweepReclaim = recentLow < priorLow - sweepBuffer && spot > priorLow
        val bullishSweepReject = recentHigh > priorHigh + sweepBuffer && spot < priorHigh
        val reversalSweep = if (side == PositionSide.PE) bearishSweepReclaim else bullishSweepReject

        // 4) Absorption/exhaustion proxy: lots of directional tick activity but little net progress.
        val absorbSample = prices.takeLast(36)
        val netDirectional = if (absorbSample.size > 1) sign * (absorbSample.last() - absorbSample.first()) else 0.0
        val path = absorbSample.zipWithNext().sumOf { (a, b) -> abs(b - a) }
        val efficiency = if (path > 0.0) abs(absorbSample.last() - absorbSample.first()) / path else 0.0
        val absorption = alignedFlow >= 0.14 && efficiency < 0.20 && netDirectional < microAtr * 1.1

        // 5) Recent impulse size. Strong is useful; huge + decelerating is a chase warning.
        val impulseSample = prices.takeLast(55)
        val directionalImpulse = if (impulseSample.size > 1 && microAtr > 0.0) {
            sign * (impulseSample.last() - impulseSample.first()) / microAtr
        } else 0.0
        val severeExtension = extension >= 5.0 || (directionalImpulse >= 7.0 && acceleration <= 0.0)
        val moderateExtension = extension >= 3.7 || (directionalImpulse >= 5.0 && acceleration < 0.0)

        // 6) Option participation around ATM: use the chosen side and opposite side together.
        val nearest = chain.sortedBy { abs(it.strike - spot) }.take(12)
        val sideQuotes = nearest.filter { it.type == side.name }.take(4)
        val oppositeType = if (side == PositionSide.CE) "PE" else "CE"
        val oppositeQuotes = nearest.filter { it.type == oppositeType }.take(4)
        val sideStats = aggregateOptionStats(sideQuotes)
        val oppStats = aggregateOptionStats(oppositeQuotes)

        // Positive means the intended option is showing long-build/participation relative to the opposite side.
        val oiImpulse = (sideStats.oiPriceImpulse - oppStats.oiPriceImpulse).coerceIn(-1.0, 1.0)
        val optionFlow = (sideStats.bidAskPressure - oppStats.bidAskPressure).coerceIn(-1.0, 1.0)
        val activity = max(sideStats.relativeActivity, oppStats.relativeActivity)
        val premiumConfirmation = sideStats.priceMomentum - oppStats.priceMomentum

        var directionScore = 0
        val reasons = mutableListOf<String>()

        if (alignedFlow >= 0.24) { directionScore += 20; reasons += "Tick seller/buyer proxy strong ${fmt(alignedFlow)}" }
        else if (alignedFlow >= 0.10) { directionScore += 13; reasons += "Tick flow aligned ${fmt(alignedFlow)}" }
        else if (alignedFlow > 0.0) directionScore += 6
        else reasons += "Tick flow not aligned ${fmt(alignedFlow)}"

        if (activity >= 1.8) { directionScore += 15; reasons += "Option activity accelerating ${fmt(activity)}x" }
        else if (activity >= 1.15) directionScore += 10
        else if (activity >= 0.75) directionScore += 5
        else reasons += "Option participation weak"

        if (oiImpulse >= 0.30) { directionScore += 15; reasons += "OI + premium build confirms ${fmt(oiImpulse)}" }
        else if (oiImpulse >= 0.10) directionScore += 10
        else if (oiImpulse > -0.10) directionScore += 5
        else reasons += "OI structure opposes entry ${fmt(oiImpulse)}"

        if (premiumConfirmation >= 0.025 || optionFlow >= 0.35) {
            directionScore += 10; reasons += "CE/PE chain confirms"
        } else if (premiumConfirmation >= 0.008 || optionFlow >= 0.12) directionScore += 6
        else reasons += "CE/PE premium flow not confirmed"

        var qualityScore = 0
        if (!reversalSweep) qualityScore += 15 else reasons += "Liquidity sweep reclaimed/rejected · reversal risk"

        if (recentVelocity > 0.0 && acceleration > 0.0) { qualityScore += 10; reasons += "Directional acceleration rising" }
        else if (recentVelocity > 0.0) qualityScore += 5
        else reasons += "Directional velocity faded"

        if (!absorption) qualityScore += 10 else reasons += "Absorption/exhaustion proxy detected"

        when {
            extension < 2.8 -> qualityScore += 5
            extension < 3.7 -> qualityScore += 3
            else -> reasons += "Extended ${fmt(extension)} micro-ATR from EMA21"
        }

        directionScore = directionScore.coerceIn(0, 60)
        qualityScore = qualityScore.coerceIn(0, 40)
        val score = directionScore + qualityScore

        val decision = when {
            reversalSweep || (absorption && moderateExtension) -> Decision.EXHAUSTION_RISK
            severeExtension || (moderateExtension && acceleration <= 0.0) -> Decision.WAIT_PULLBACK
            score >= ENTRY_SCORE && directionScore >= MIN_DIRECTION_SCORE && recentVelocity > 0.0 -> Decision.EARLY_CONFIRMED
            else -> Decision.WAIT_CONFIRMATION
        }

        reasons += "Exec quality $score/100 · direction $directionScore/60 · entry $qualityScore/40"
        reasons += "Ext ${fmt(extension)} ATR · accel ${fmt(acceleration)} · activity ${fmt(activity)}x"

        return Result(
            decision = decision,
            score = score,
            directionScore = directionScore,
            entryQualityScore = qualityScore,
            orderFlowProxy = alignedFlow,
            relativeActivity = activity,
            optionOiImpulse = oiImpulse,
            optionFlowProxy = optionFlow,
            acceleration = acceleration,
            extensionAtr = extension,
            reasons = reasons,
        )
    }

    private data class OptionStats(
        val priceMomentum: Double,
        val oiPriceImpulse: Double,
        val bidAskPressure: Double,
        val relativeActivity: Double,
    )

    private fun aggregateOptionStats(quotes: List<OptionQuote>): OptionStats {
        if (quotes.isEmpty()) return OptionStats(0.0, 0.0, 0.0, 0.0)
        val stats = quotes.mapNotNull { q ->
            val hist = options[q.instrumentKey]?.toList()?.takeLast(50) ?: return@mapNotNull null
            if (hist.size < 2) return@mapNotNull null
            val first = hist.first()
            val last = hist.last()
            val priceMomentum = if (first.ltp > 0.0) (last.ltp - first.ltp) / first.ltp else 0.0
            val oiBase = max(abs(first.oi).toDouble(), 1.0)
            val oiPct = (last.oi - first.oi) / oiBase
            // price↑ + OI↑ behaves like long build; price↓ + OI↑ behaves like writing/pressure.
            val oiPriceImpulse = (priceMomentum * 12.0 + oiPct * if (priceMomentum >= 0) 1.0 else -1.0).coerceIn(-1.0, 1.0)
            val spread = last.ask - last.bid
            val pressure = if (spread > 0.0 && last.bid > 0.0) {
                (((last.ltp - (last.bid + last.ask) / 2.0) / (spread / 2.0)).coerceIn(-1.0, 1.0))
            } else 0.0
            val recentVol = (last.volume - hist[max(0, hist.size - 12)].volume).coerceAtLeast(0L).toDouble()
            val baseVol = (hist[max(0, hist.size - 12)].volume - first.volume).coerceAtLeast(0L).toDouble()
            val recentN = min(11, hist.size - 1).coerceAtLeast(1)
            val baseN = max(1, hist.size - recentN - 1)
            val recentRate = recentVol / recentN
            val baseRate = baseVol / baseN
            val activity = if (baseRate > 0.0) recentRate / baseRate else if (recentRate > 0.0) 1.0 else 0.0
            OptionStats(priceMomentum, oiPriceImpulse, pressure, activity.coerceIn(0.0, 5.0))
        }
        if (stats.isEmpty()) return OptionStats(0.0, 0.0, 0.0, 0.0)
        return OptionStats(
            stats.map { it.priceMomentum }.average(),
            stats.map { it.oiPriceImpulse }.average(),
            stats.map { it.bidAskPressure }.average(),
            stats.map { it.relativeActivity }.average(),
        )
    }

    private fun directionalVelocity(ticks: List<SpotTick>, sign: Double): Double {
        if (ticks.size < 2) return 0.0
        val dt = max((ticks.last().ts - ticks.first().ts) / 1000.0, 0.25)
        return sign * (ticks.last().price - ticks.first().price) / dt
    }

    private fun tickAtr(values: List<Double>, lookback: Int): Double {
        val sample = values.takeLast(lookback + 1)
        if (sample.size < 2) return max(values.lastOrNull()?.times(0.00002) ?: 0.01, 0.01)
        val meanMove = sample.zipWithNext().map { (a, b) -> abs(b - a) }.average()
        return max(meanMove * sqrt(8.0), sample.last() * 0.00002)
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val sample = values.takeLast(min(period, values.size))
        if (sample.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var out = sample.first()
        for (x in sample.drop(1)) out = x * k + out * (1.0 - k)
        return out
    }

    private fun fmt(v: Double) = "%.2f".format(v)

    companion object {
        private const val MIN_SPOT_TICKS = 36
        private const val MAX_SPOT_TICKS = 1200
        private const val MAX_OPTION_TICKS = 180
        private const val ENTRY_SCORE = 66
        private const val MIN_DIRECTION_SCORE = 36
    }
}
