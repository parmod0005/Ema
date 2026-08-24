package com.parmod.ema.training

import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-market live microstructure research state used only by dual-market AI training.
 * No static/global buffers are shared between NIFTY and SENSEX.
 */
class MarketMicrostructureResearch {
    data class Quality(
        val score: Int,
        val directionScore: Int,
        val entryQualityScore: Int,
        val orderFlow: Double,
        val relativeActivity: Double,
        val oiImpulse: Double,
        val optionFlow: Double,
        val acceleration: Double,
        val extensionAtr: Double,
        val depthImbalance: Double,
        val micropricePressure: Double,
        val totalBookPressure: Double,
        val wallPressure: Double,
        val depthLevels: Int,
        val canEnter: Boolean,
    )

    data class DepthLevel(
        val bidPrice: Double,
        val bidQty: Long,
        val askPrice: Double,
        val askQty: Long,
    )

    private data class SpotTick(val price: Double, val ts: Long)
    private data class OptionTick(
        val ltp: Double,
        val oi: Long,
        val volume: Long,
        val bid: Double,
        val ask: Double,
        val ts: Long,
        val totalBuyQty: Long,
        val totalSellQty: Long,
        val depth: List<DepthLevel>,
    )

    private val spots = ArrayDeque<SpotTick>()
    private val options = linkedMapOf<String, ArrayDeque<OptionTick>>()

    @Synchronized
    fun reset() {
        spots.clear()
        options.clear()
    }

    @Synchronized
    fun ingestSpot(price: Double, timestamp: Long) {
        if (price <= 0.0 || timestamp <= 0L) return
        val last = spots.lastOrNull()
        if (last != null && timestamp < last.ts) return
        if (last != null && last.ts == timestamp && last.price == price) return
        spots.addLast(SpotTick(price, timestamp))
        while (spots.size > MAX_SPOT_TICKS) spots.removeFirst()
    }

    @Synchronized
    fun ingestOption(
        instrumentKey: String,
        ltp: Double?,
        oi: Long?,
        volume: Long?,
        bid: Double?,
        ask: Double?,
        timestamp: Long,
        totalBuyQty: Long?,
        totalSellQty: Long?,
        depth: List<DepthLevel>,
    ) {
        if (instrumentKey.isBlank() || ltp == null || ltp <= 0.0 || timestamp <= 0L) return
        val q = options.getOrPut(instrumentKey) { ArrayDeque() }
        val tick = OptionTick(
            ltp = ltp,
            oi = oi ?: 0L,
            volume = volume ?: 0L,
            bid = bid ?: 0.0,
            ask = ask ?: 0.0,
            ts = timestamp,
            totalBuyQty = totalBuyQty ?: 0L,
            totalSellQty = totalSellQty ?: 0L,
            depth = depth.take(30),
        )
        val last = q.lastOrNull()
        if (last != null && timestamp < last.ts) return
        if (last != null && last.ts == tick.ts && last.ltp == tick.ltp && last.oi == tick.oi && last.volume == tick.volume && last.depth == tick.depth) return
        q.addLast(tick)
        while (q.size > MAX_OPTION_TICKS) q.removeFirst()
        while (options.size > MAX_INSTRUMENTS) {
            val first = options.keys.firstOrNull() ?: break
            if (first == instrumentKey) break
            options.remove(first)
        }
    }

    @Synchronized
    fun evaluate(side: PositionSide, chain: List<OptionQuote>, spot: Double): Quality {
        val s = spots.toList()
        if (s.size < MIN_SPOT_TICKS || spot <= 0.0) return empty()
        val sign = if (side == PositionSide.CE) 1.0 else -1.0
        val prices = s.map { it.price }
        val microAtr = tickAtr(prices, 80)
        val ema21 = ema(prices, 21)
        val extension = if (microAtr > 0.0) abs(spot - ema21) / microAtr else 0.0

        val flowSample = prices.takeLast(90)
        var up = 0
        var down = 0
        flowSample.zipWithNext().forEach { (a, b) -> if (b > a) up++ else if (b < a) down++ }
        val rawFlow = if (up + down == 0) 0.0 else (up - down).toDouble() / (up + down)
        val alignedFlow = sign * rawFlow

        val recentVelocity = directionalVelocity(s.takeLast(18), sign)
        val priorSlice = s.dropLast(min(12, s.size / 4)).takeLast(30)
        val priorVelocity = directionalVelocity(priorSlice, sign)
        val acceleration = recentVelocity - priorVelocity

        val nearest = chain.sortedBy { abs(it.strike - spot) }.take(14)
        val sideQuotes = nearest.filter { it.type == side.name }.take(5)
        val oppositeType = if (side == PositionSide.CE) "PE" else "CE"
        val oppositeQuotes = nearest.filter { it.type == oppositeType }.take(5)
        val sideStats = aggregate(sideQuotes)
        val oppStats = aggregate(oppositeQuotes)

        val oiImpulse = (sideStats.oiPriceImpulse - oppStats.oiPriceImpulse).coerceIn(-1.0, 1.0)
        val optionFlow = (sideStats.bidAskPressure - oppStats.bidAskPressure).coerceIn(-1.0, 1.0)
        val activity = max(sideStats.relativeActivity, oppStats.relativeActivity)
        val premiumConfirmation = sideStats.priceMomentum - oppStats.priceMomentum
        val depthImbalance = (sideStats.depthImbalance - oppStats.depthImbalance).coerceIn(-1.0, 1.0)
        val micropricePressure = (sideStats.micropricePressure - oppStats.micropricePressure).coerceIn(-1.0, 1.0)
        val totalBookPressure = (sideStats.totalBookPressure - oppStats.totalBookPressure).coerceIn(-1.0, 1.0)
        val wallPressure = (sideStats.wallPressure - oppStats.wallPressure).coerceIn(-1.0, 1.0)
        val depthLevels = max(sideStats.depthLevels, oppStats.depthLevels)
        val d30 = depthImbalance * 0.40 + micropricePressure * 0.20 + totalBookPressure * 0.20 + wallPressure * 0.20

        var direction = 0
        if (alignedFlow >= 0.24) direction += 14 else if (alignedFlow >= 0.10) direction += 9 else if (alignedFlow > 0.0) direction += 4
        if (activity >= 1.8) direction += 10 else if (activity >= 1.15) direction += 7 else if (activity >= 0.75) direction += 3
        if (oiImpulse >= 0.30) direction += 10 else if (oiImpulse >= 0.10) direction += 7 else if (oiImpulse > -0.10) direction += 3
        if (premiumConfirmation >= 0.025 || optionFlow >= 0.35) direction += 6 else if (premiumConfirmation >= 0.008 || optionFlow >= 0.12) direction += 4
        if (d30 >= 0.30) direction += 20 else if (d30 >= 0.12) direction += 14 else if (d30 > 0.0) direction += 7
        direction = direction.coerceIn(0, 60)

        var entry = 0
        if (recentVelocity > 0.0 && acceleration > 0.0) entry += 15 else if (recentVelocity > 0.0) entry += 8
        if (extension < 2.8) entry += 10 else if (extension < 3.7) entry += 5
        if (d30 > -0.18 || depthLevels < 10) entry += 8
        if (alignedFlow > -0.05) entry += 7
        entry = entry.coerceIn(0, 40)

        val score = direction + entry
        val severeExtension = extension >= 5.0
        val bookConflict = depthLevels >= 10 && d30 < -0.18
        val canEnter = score >= ENTRY_SCORE && direction >= MIN_DIRECTION_SCORE && recentVelocity > 0.0 && !severeExtension && !bookConflict
        return Quality(
            score = score,
            directionScore = direction,
            entryQualityScore = entry,
            orderFlow = alignedFlow,
            relativeActivity = activity,
            oiImpulse = oiImpulse,
            optionFlow = optionFlow,
            acceleration = acceleration,
            extensionAtr = extension,
            depthImbalance = depthImbalance,
            micropricePressure = micropricePressure,
            totalBookPressure = totalBookPressure,
            wallPressure = wallPressure,
            depthLevels = depthLevels,
            canEnter = canEnter,
        )
    }

    private data class Stats(
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

    private fun aggregate(quotes: List<OptionQuote>): Stats {
        if (quotes.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val rows = quotes.mapNotNull { q ->
            val hist = options[q.instrumentKey]?.toList()?.takeLast(50) ?: return@mapNotNull null
            if (hist.size < 2) return@mapNotNull null
            val first = hist.first()
            val last = hist.last()
            val priceMomentum = if (first.ltp > 0.0) (last.ltp - first.ltp) / first.ltp else 0.0
            val oiBase = max(abs(first.oi).toDouble(), 1.0)
            val oiPct = (last.oi - first.oi) / oiBase
            val oiPrice = (priceMomentum * 12.0 + oiPct * if (priceMomentum >= 0.0) 1.0 else -1.0).coerceIn(-1.0, 1.0)
            val spread = last.ask - last.bid
            val pressure = if (spread > 0.0 && last.bid > 0.0) {
                ((last.ltp - (last.bid + last.ask) / 2.0) / (spread / 2.0)).coerceIn(-1.0, 1.0)
            } else 0.0
            val recentIndex = max(0, hist.size - 12)
            val recentVol = (last.volume - hist[recentIndex].volume).coerceAtLeast(0L).toDouble()
            val baseIndex = max(0, recentIndex - 12)
            val baseVol = (hist[recentIndex].volume - hist[baseIndex].volume).coerceAtLeast(0L).toDouble()
            val activity = if (baseVol > 0.0) (recentVol / baseVol).coerceIn(0.0, 5.0) else if (recentVol > 0.0) 1.0 else 0.0
            val d = depthStats(last)
            Stats(priceMomentum, oiPrice, pressure, activity, d.depthImbalance, d.micropricePressure, d.totalBookPressure, d.wallPressure, last.depth.size)
        }
        if (rows.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        return Stats(
            rows.map { it.priceMomentum }.average(),
            rows.map { it.oiPriceImpulse }.average(),
            rows.map { it.bidAskPressure }.average(),
            rows.maxOf { it.relativeActivity },
            rows.map { it.depthImbalance }.average(),
            rows.map { it.micropricePressure }.average(),
            rows.map { it.totalBookPressure }.average(),
            rows.map { it.wallPressure }.average(),
            rows.maxOf { it.depthLevels },
        )
    }

    private data class DepthStats(
        val depthImbalance: Double,
        val micropricePressure: Double,
        val totalBookPressure: Double,
        val wallPressure: Double,
    )

    private fun depthStats(t: OptionTick): DepthStats {
        if (t.depth.isEmpty()) return DepthStats(0.0, 0.0, tbqTsq(t), 0.0)
        var bidWeighted = 0.0
        var askWeighted = 0.0
        t.depth.forEachIndexed { i, d ->
            val weight = 1.0 / (1.0 + i * 0.20)
            bidWeighted += d.bidQty * weight
            askWeighted += d.askQty * weight
        }
        val depthDen = bidWeighted + askWeighted
        val depthImbalance = if (depthDen > 0.0) (bidWeighted - askWeighted) / depthDen else 0.0
        val first = t.depth.first()
        val micro = if (first.bidPrice > 0.0 && first.askPrice > 0.0 && first.bidQty + first.askQty > 0L) {
            val microprice = (first.askPrice * first.bidQty + first.bidPrice * first.askQty) / (first.bidQty + first.askQty).toDouble()
            val mid = (first.bidPrice + first.askPrice) / 2.0
            val half = max((first.askPrice - first.bidPrice) / 2.0, 0.0001)
            ((microprice - mid) / half).coerceIn(-1.0, 1.0)
        } else 0.0
        val maxBid = t.depth.maxOfOrNull { it.bidQty } ?: 0L
        val maxAsk = t.depth.maxOfOrNull { it.askQty } ?: 0L
        val wallDen = maxBid + maxAsk
        val wall = if (wallDen > 0L) (maxBid - maxAsk).toDouble() / wallDen else 0.0
        return DepthStats(depthImbalance, micro, tbqTsq(t), wall)
    }

    private fun tbqTsq(t: OptionTick): Double {
        val den = t.totalBuyQty + t.totalSellQty
        return if (den > 0L) (t.totalBuyQty - t.totalSellQty).toDouble() / den else 0.0
    }

    private fun directionalVelocity(sample: List<SpotTick>, sign: Double): Double {
        if (sample.size < 2) return 0.0
        val moves = sample.zipWithNext().map { (a, b) -> sign * (b.price - a.price) }
        return if (moves.isEmpty()) 0.0 else moves.average()
    }

    private fun tickAtr(values: List<Double>, lookback: Int): Double {
        val s = values.takeLast(lookback + 1)
        if (s.size < 2) return 0.0
        val meanMove = s.zipWithNext().map { (a, b) -> abs(b - a) }.average()
        return max(meanMove * sqrt(8.0), values.last() * 0.00002)
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val s = values.takeLast(period.coerceAtMost(values.size))
        if (s.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var v = s.first()
        s.drop(1).forEach { v = it * k + v * (1.0 - k) }
        return v
    }

    private fun empty() = Quality(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false)

    companion object {
        private const val MIN_SPOT_TICKS = 40
        private const val MAX_SPOT_TICKS = 5000
        private const val MAX_OPTION_TICKS = 240
        private const val MAX_INSTRUMENTS = 128
        private const val ENTRY_SCORE = 70
        private const val MIN_DIRECTION_SCORE = 36
    }
}
