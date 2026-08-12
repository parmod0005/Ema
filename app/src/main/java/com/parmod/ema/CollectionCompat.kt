package com.parmod.ema

/**
 * Small collection compatibility helpers for the Android/Kotlin toolchain used by CI.
 */
fun <T> List<T>.takeLast(count: Int): List<T> {
    require(count >= 0) { "Requested element count must be non-negative" }
    if (count == 0 || isEmpty()) return emptyList()
    val fromIndex = (size - count).coerceAtLeast(0)
    return subList(fromIndex, size).toList()
}
