package com.parmod.ema.engine

import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared fast tick/option-chain execution-quality layer for Engine 3.
 * V7.6 remains the directional/setup engine. This layer validates whether a move is still fresh.
 * Upstox Plus full_d30 is used when present. Aggressor side is still inferred, not exchange-labelled.
 */
object V76ExecutionQualityEngine {
    enum class Decision { EARLY_CONFIRMED, WAIT_CONFIRMATION, WAIT_PULLBACK, EXHAUSTION_RISK }

    data class DepthLevel(
        val bidPrice: Double,
        val bidQty: Long,
        val askPrice: Double,
        val askQty: Long,
    )

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
        val depthImbalance: Double = 0.0,
        val micropricePressure: Double = 0.0,
        val totalBookPressure: Double = 0.0,
        val wallPressure: Double = 0.0,
        val depthLevels: Int = 0,
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
        val ltq: Long,
        val totalBuyQty: Long,
        val totalSellQty: Long,
        val depth: List<DepthLevel>,
    )

    private val spots = ArrayDeque<SpotTick>()
    private val options = linkedMapOf<String, ArrayDeque<OptionTick>>()

    @Synchronized fun reset() {
        spots.clear()
        options.clear()
    }

    @Synchronized fun ingestSpot(price: Double, timestamp: Long) {
        if (price <= 0.0) return
        if (spots.lastOrNull()?.ts == timestamp && spots.lastOrNull()?.price == price) return
        spots.addLast(SpotTick(price, timestamp))
        while (spots.size > MAX_SPOT_TICKS) spots.removeFirst()
    }

    @Synchronized fun ingestOption(
        instrumentKey: String,
        ltp: Double?,
        oi: Long?,
        volume: Long?,
        bid: Double?,
        ask: Double?,
        timestamp: Long,
        ltq: Long? = null,
        totalBuyQty: Long? = null,
        totalSellQty: Long? = null,
        depth: List<DepthLevel> = emptyList(),
    ) {
        if (instrumentKey.isBlank() || ltp == null || ltp <= 0.0) return
        val q = options.getOrPut(instrumentKey) { ArrayDeque() }
        val tick = OptionTick(
            ltp = ltp,
            oi = oi ?: 0L,
            volume = volume ?: 0L,
            bid = bid ?: 0.0,
            ask = ask ?: 0.0,
            ts = timestamp,
            ltq = ltq ?: 0L,
            totalBuyQty = totalBuyQty ?: 0L,
            totalSellQty = totalSellQty ?: 0L,
            depth = depth.take(30),
        )
        val last = q.lastOrNull()
        if (last != null && last.ts == tick.ts && last.ltp == tick.ltp && last.oi == tick.oi && last.volume == tick.volume && last.depth == tick.depth) return
        q.addLast(tick)
        while (q.size > MAX_OPTION_TICKS) q.removeFirst()
    }

    @Synchronized fun evaluate(side: PositionSide, chain: List<OptionQuote>, spot: Double): Result {
        val s = spots.toList()
        if (s.size < MIN_SPOT_TICKS || spot <= 0.0) {
            return Result(
                Decision.WAIT_CONFIRMATION, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                reasons = listOf("Microstructure warm-up ${s.size}/$MIN_SPOT_TICKS ticks"),
            )
        }

        val sign = if (side == PositionSide.CE) 1.0 else -1.0
        val prices = s.map { it.price }
        val microAtr = tickAtr(prices, 80)
        val ema21 = ema(prices, 21)
        val extension = if (microAtr > 0.0) abs(spot - ema21) / microAtr else 0.0

        val flowSample = prices.takeLast(90)
        var up = 0
        var down = 0
        flowSample.zipWithNext().forEach { (a, b) -> if (b > a) up++ else if (b < a) down++ }
        val orderFlow = if (up + down == 0) 0.0 else (up - down).toDouble() / (up + down)
        val alignedFlow = sign * orderFlow

        val recentVelocity = directionalVelocity(s.takeLast(18), sign)
        val priorSlice = s.dropLast(min(12, s.size / 4)).takeLast(30)
        val priorVelocity = directionalVelocity(priorSlice, sign)
        val acceleration = recentVelocity - priorVelocity

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

        val absorbSample = prices.takeLast(36)
        val netDirectional = if (absorbSample.size > 1) sign * (absorbSample.last() - absorbSample.first()) else 0.0
        val path = absorbSample.zipWithNext().sumOf { (a, b) -> abs(b - a) }
        val efficiency = if (path > 0.0) abs(absorbSample.last() - absorbSample.first()) / path else 0.0
        val absorption = alignedFlow >= 0.14 && efficiency < 0.20 && netDirectional < microAtr * 1.1

        val impulseSample = prices.takeLast(55)
        val directionalImpulse = if (impulseSample.size > 1 && microAtr > 0.0) sign * (impulseSample.last() - impulseSample.first()) / microAtr else 0.0
        val severeExtension = extension >= 5.0 || (directionalImpulse >= 7.0 && acceleration <= 0.0)
        val moderateExtension = extension >= 3.7 || (directionalImpulse >= 5.0 && acceleration < 0.0)

        val nearest = chain.sortedBy { abs(it.strike - spot) }.take(12)
        val sideQuotes = nearest.filter { it.type == side.name }.take(4)
        val oppositeType = if (side == PositionSide.CE) "PE" else "CE"
        val oppositeQuotes = nearest.filter { it.type == oppositeType }.take(4)
        val sideStats = aggregateOptionStats(sideQuotes)
        val oppStats = aggregateOptionStats(oppositeQuotes)

        val oiImpulse = (sideStats.oiPriceImpulse - oppStats.oiPriceImpulse).coerceIn(-1.0, 1.0)
        val optionFlow = (sideStats.bidAskPressure - oppStats.bidAskPressure).coerceIn(-1.0, 1.0)
        val activity = max(sideStats.relativeActivity, oppStats.relativeActivity)
        val premiumConfirmation = sideStats.priceMomentum - oppStats.priceMomentum
        val depthImbalance = (sideStats.depthImbalance - oppStats.depthImbalance).coerceIn(-1.0, 1.0)
        val micropricePressure = (sideStats.micropricePressure - oppStats.micropricePressure).coerceIn(-1.0, 1.0)
        val totalBookPressure = (sideStats.totalBookPressure - oppStats.totalBookPressure).coerceIn(-1.0, 1.0)
        val wallPressure = (sideStats.wallPressure - oppStats.wallPressure).coerceIn(-1.0, 1.0)
        val depthLevels = max(sideStats.depthLevels, oppStats.depthLevels)

        var directionScore = 0
        val reasons = mutableListOf<String>()

        if (alignedFlow >= 0.24) { directionScore += 14; reasons += "Tick direction proxy strong ${fmt(alignedFlow)}" }
        else if (alignedFlow >= 0.10) { directionScore += 9; reasons += "Tick flow aligned ${fmt(alignedFlow)}" }
        else if (alignedFlow > 0.0) directionScore += 4
        else reasons += "Tick flow not aligned ${fmt(alignedFlow)}"

        if (activity >= 1.8) { directionScore += 10; reasons += "Option activity accelerating ${fmt(activity)}x" }
        else if (activity >= 1.15) directionScore += 7
        else if (activity >= 0.75) directionScore += 3
        else reasons += "Option participation weak"

        if (oiImpulse >= 0.30) { directionScore += 10; reasons += "OI + premium build confirms ${fmt(oiImpulse)}" }
        else if (oiImpulse >= 0.10) directionScore += 7
        else if (oiImpulse > -0.10) directionScore += 3
        else reasons += "OI structure opposes entry ${fmt(oiImpulse)}"

        if (premiumConfirmation >= 0.025 || optionFlow >= 0.35) { directionScore += 6; reasons += "CE/PE premium flow confirms" }
        else if (premiumConfirmation >= 0.008 || optionFlow >= 0.12) directionScore += 4
        else reasons += "CE/PE premium flow not confirmed"

        if (depthLevels >= 20) reasons += "UPSTOX PLUS D30 LIVE · $depthLevels levels"
        else if (depthLevels > 0) reasons += "Depth fallback · $depthLevels levels"
        else reasons += "Depth unavailable"

        val d30Composite = (depthImbalance * 0.40 + micropricePressure * 0.20 + totalBookPressure * 0.20 + wallPressure * 0.20)
        if (d30Composite >= 0.30) { directionScore += 20; reasons += "D30 book strongly aligned ${fmt(d30Composite)}" }
        else if (d30Composite >= 0.12) { directionScore += 14; reasons += "D30 book aligned ${fmt(d30Composite)}" }
        else if (d30Composite > 0.0) directionScore += 7
        else if (depthLevels > 0) reasons += "D30/book pressure opposes entry ${fmt(d30Composite)}"

        var qualityScore = 0
        if (!reversalSweep) qualityScore += 15 else reasons += "Liquidity sweep reclaimed/rejected · reversal risk"

        if (recentVelocity > 0.0 && acceleration > 0.0) { qualityScore += 10; reasons += "Directional acceleration rising" }
        else if (recentVelocity > 0.0) qualityScore += 5
        else reasons += "Directional velocity faded"

        val depthAbsorption = depthLevels >= 10 && d30Composite > 0.20 && recentVelocity <= 0.0
        if (!absorption && !depthAbsorption) qualityScore += 10
        else reasons += if (depthAbsorption) "D30 absorption · pressure without price progress" else "Absorption/exhaustion proxy detected"

        when {
            extension < 2.8 -> qualityScore += 5
            extension < 3.7 -> qualityScore += 3
            else -> reasons += "Extended ${fmt(extension)} micro-ATR from EMA21"
        }

        directionScore = directionScore.coerceIn(0, 60)
        qualityScore = qualityScore.coerceIn(0, 40)
        val score = directionScore + qualityScore

        val bookConflict = depthLevels >= 10 && d30Composite < -0.18
        val decision = when {
            reversalSweep || ((absorption || depthAbsorption) && moderateExtension) || bookConflict -> Decision.EXHAUSTION_RISK
            severeExtension || (moderateExtension && acceleration <= 0.0) -> Decision.WAIT_PULLBACK
            score >= ENTRY_SCORE && directionScore >= MIN_DIRECTION_SCORE && recentVelocity > 0.0 -> Decision.EARLY_CONFIRMED
            else -> Decision.WAIT_CONFIRMATION
        }

        reasons += "Exec quality $score/100 · direction $directionScore/60 · entry $qualityScore/40"
        reasons += "D30 ${fmt(d30Composite)} · depth ${fmt(depthImbalance)} · micro ${fmt(micropricePressure)} · TBQ/TSQ ${fmt(totalBookPressure)}"
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
            depthImbalance = depthImbalance,
            micropricePressure = micropricePressure,
            totalBookPressure = totalBookPressure,
            wallPressure = wallPressure,
            depthLevels = depthLevels,
            reasons = reasons,
        )
    }

    private data class OptionStats(
        val priceMomentum: Double,
        val oiPriceImpulse: Double,
        val bidAskPressure: Double,
        val relativeActivity: Double,
        val depthImbalance: Double,
        val micropricePressure: Double,
        val totalBookPressure: Double,
        val wallPressure: Double,
        val depthLevels: Int,
    )

    private fun aggregateOptionStats(quotes: List<OptionQuote>): OptionStats {
        if (quotes.isEmpty()) return OptionStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val stats = quotes.mapNotNull { q ->
            val hist = options[q.instrumentKey]?.toList()?.takeLast(50) ?: return@mapNotNull null
            if (hist.size < 2) return@mapNotNull null
            val first = hist.first()
            val last = hist.last()
            val priceMomentum = if (first.ltp > 0.0) (last.ltp - first.ltp) / first.ltp else 0.0
            val oiBase = max(abs(first.oi).toDouble(), 1.0)
            val oiPct = (last.oi - first.oi) / oiBase
            val oiPriceImpulse = (priceMomentum * 12.0 + oiPct * if (priceMomentum >= 0) 1.0 else -1.0).coerceIn(-1.0, 1.0)
            val spread = last.ask - last.bid
            val pressure = if (spread > 0.0 && last.bid > 0.0) (((last.ltp - (last.bid + last.ask) / 2.0) / (spread / 2.0)).coerceIn(-1.0, 1.0)) else 0.0
            val recentIndex = max(0, hist.size - 12)
            val recentVol = (last.volume - hist[recentIndex].volume).coerceAtLeast(0L).toDouble()
            val baseVol = (hist[recentIndex].volume - first.volume).coerceAtLeast(0L).toDouble()
            val recentN = min(11, hist.size - 1).coerceAtLeast(1)
            val baseN = max(1, hist.size - recentN - 1)
            val recentRate = recentVol / recentN
            val baseRate = baseVol / baseN
            val activity = if (baseRate > 0.0) recentRate / baseRate else if (recentRate > 0.0) 1.0 else 0.0

            val book = bookStats(last)
            OptionStats(
                priceMomentum,
                oiPriceImpulse,
                pressure,
                activity.coerceIn(0.0, 5.0),
                book.depthImbalance,
                book.micropricePressure,
                book.totalBookPressure,
                book.wallPressure,
                last.depth.size,
            )
        }
        if (stats.isEmpty()) return OptionStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        return OptionStats(
            stats.map { it.priceMomentum }.average(),
            stats.map { it.oiPriceImpulse }.average(),
            stats.map { it.bidAskPressure }.average(),
            stats.map { it.relativeActivity }.average(),
            stats.map { it.depthImbalance }.average(),
            stats.map { it.micropricePressure }.average(),
            stats.map { it.totalBookPressure }.average(),
            stats.map { it.wallPressure }.average(),
            stats.maxOf { it.depthLevels },
        )
    }

    private data class BookStats(
        val depthImbalance: Double,
        val micropricePressure: Double,
        val totalBookPressure: Double,
        val wallPressure: Double,
    )

    private fun bookStats(tick: OptionTick): BookStats {
        val levels = tick.depth.filter { it.bidPrice > 0.0 || it.askPrice > 0.0 }.take(30)
        if (levels.isEmpty()) return BookStats(0.0, 0.0, totalBookPressure(tick), 0.0)

        var weightedBid = 0.0
        var weightedAsk = 0.0
        levels.forEachIndexed { i, d ->
            val w = 1.0 / (1.0 + i * 0.22)
            weightedBid += d.bidQty.coerceAtLeast(0).toDouble() * w
            weightedAsk += d.askQty.coerceAtLeast(0).toDouble() * w
        }
        val denom = weightedBid + weightedAsk
        val depthImbalance = if (denom > 0.0) (weightedBid - weightedAsk) / denom else 0.0

        val best = levels.first()
        val microprice = if (best.bidPrice > 0.0 && best.askPrice > 0.0 && best.bidQty + best.askQty > 0) {
            (best.askPrice * best.bidQty + best.bidPrice * best.askQty) / (best.bidQty + best.askQty).toDouble()
        } else tick.ltp
        val halfSpread = max((best.askPrice - best.bidPrice) / 2.0, 0.01)
        val midpoint = if (best.bidPrice > 0.0 && best.askPrice > 0.0) (best.bidPrice + best.askPrice) / 2.0 else tick.ltp
        val microPressure = ((microprice - midpoint) / halfSpread).coerceIn(-1.0, 1.0)

        val near = levels.take(min(5, levels.size))
        val bidWall = near.maxOfOrNull { it.bidQty }?.toDouble() ?: 0.0
        val askWall = near.maxOfOrNull { it.askQty }?.toDouble() ?: 0.0
        val wallDenom = bidWall + askWall
        val wallPressure = if (wallDenom > 0.0) (bidWall - askWall) / wallDenom else 0.0

        return BookStats(depthImbalance, microPressure, totalBookPressure(tick), wallPressure)
    }

    private fun totalBookPressure(tick: OptionTick): Double {
        val b = tick.totalBuyQty.toDouble().coerceAtLeast(0.0)
        val a = tick.totalSellQty.toDouble().coerceAtLeast(0.0)
        val d = b + a
        return if (d > 0.0) ((b - a) / d).coerceIn(-1.0, 1.0) else 0.0
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

    private const val MIN_SPOT_TICKS = 36
    private const val MAX_SPOT_TICKS = 1200
    private const val MAX_OPTION_TICKS = 180
    private const val ENTRY_SCORE = 66
    private const val MIN_DIRECTION_SCORE = 36
}
