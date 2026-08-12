package com.parmod.ema

/**
 * Local compatibility helper for the warm-start path.
 * Avoids relying on stdlib takeLast resolution in the Android CI compiler.
 */
fun <T> List<T>.takeLast(count: Int): List<T> {
    require(count >= 0) { "count must be non-negative" }
    if (count == 0 || isEmpty()) return emptyList()
    if (count >= size) return toList()
    return subList(size - count, size).toList()
}
