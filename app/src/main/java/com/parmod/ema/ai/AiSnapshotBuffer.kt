package com.parmod.ema.ai

import com.parmod.ema.model.DashboardState
import java.util.UUID

/** Builds compact 1m/5m/15m OHLC bars from the underlying tick stream. */
class AiSnapshotBuffer(
    private val maxSamples: Int = 12_000,
) {
    private data class Sample(val time: Long, val price: Double, val volume: Long)
    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    fun add(timeMillis: Long, price: Double, volume: Long = 0L) {
        if (price <= 0.0 || !price.isFinite()) return
        samples.addLast(Sample(timeMillis, price, volume.coerceAtLeast(0L)))
        while (samples.size > maxSamples) samples.removeFirst()
        val cutoff = timeMillis - 16L * 60L * 60L * 1_000L
        while (samples.firstOrNull()?.time?.let { it < cutoff } == true) samples.removeFirst()
    }

    fun isReady(): Boolean = bars(60_000L, 20).size >= 5

    fun build(state: DashboardState, expiry: String, nowMillis: Long = System.currentTimeMillis()): AiMarketSnapshot {
        return AiMarketSnapshot(
            snapshotId = UUID.randomUUID().toString(),
            generatedAtMillis = nowMillis,
            index = state.index,
            expiry = expiry,
            spot = state.spotPrice,
            bars1m = bars(60_000L, 60),
            bars5m = bars(300_000L, 48),
            bars15m = bars(900_000L, 32),
            optionChain = state.optionChain.filter { it.ltp > 0.0 }.sortedWith(compareBy({ it.strike }, { it.type })),
            nativeAction = state.signal.action,
            nativeConfidence = state.signal.confidence,
            risk = RiskContext(
                capital = state.equity,
                realizedPnl = state.realizedPnl,
                openSide = state.position?.side,
                openEntryPrice = state.position?.entryPrice,
                dailyTrades = 0,
                dailyLossLocked = false,
            ),
        )
    }

    private fun bars(bucketMillis: Long, limit: Int): List<CompactBar> {
        if (samples.isEmpty()) return emptyList()
        return samples
            .groupBy { it.time / bucketMillis * bucketMillis }
            .toSortedMap()
            .map { (bucket, points) ->
                CompactBar(
                    epochMillis = bucket,
                    open = points.first().price,
                    high = points.maxOf { it.price },
                    low = points.minOf { it.price },
                    close = points.last().price,
                    volume = points.sumOf { it.volume },
                )
            }
            .takeLast(limit)
    }
}
