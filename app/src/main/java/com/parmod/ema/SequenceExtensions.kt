package com.parmod.ema

/**
 * Small collection helper for warm-start candle loading.
 * Kotlin Sequence does not expose takeLast(), so materialize once and reuse
 * the standard List.takeLast() implementation. The warm-start list is bounded
 * to one trading session, so this is intentionally small and deterministic.
 */
fun <T> Sequence<T>.takeLast(count: Int): List<T> {
    require(count >= 0) { "count must be non-negative" }
    if (count == 0) return emptyList()
    return toList().takeLast(count)
}
