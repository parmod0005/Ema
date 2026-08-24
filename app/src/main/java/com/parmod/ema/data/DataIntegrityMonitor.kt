package com.parmod.ema.data

import java.time.Duration
import kotlin.math.abs

/**
 * Lightweight background integrity checks for VARDHANI market data.
 *
 * Historical checks reject malformed/duplicate/out-of-order candles and large
 * unexplained gaps inside a trading session. Live checks reject impossible
 * prices and track duplicate/out-of-order/stale timestamps per instrument.
 */
class DataIntegrityMonitor {
    data class HistoricalReport(
        val valid: Boolean,
        val rows: Int,
        val duplicates: Int,
        val outOfOrder: Int,
        val badOhlc: Int,
        val sessionGaps: Int,
        val message: String,
    )

    data class LiveReport(
        val accepted: Boolean,
        val duplicate: Boolean = false,
        val outOfOrder: Boolean = false,
        val stale: Boolean = false,
        val message: String = "LIVE DATA OK",
    )

    private data class LiveState(var timestamp: Long = 0L, var price: Double = 0.0, var seenAt: Long = 0L)
    private val live = HashMap<String, LiveState>()

    @Synchronized
    fun checkLive(instrumentKey: String, price: Double?, timestamp: Long, now: Long = System.currentTimeMillis()): LiveReport {
        if (instrumentKey.isBlank()) return LiveReport(false, message = "Blank instrument key")
        if (price != null && (!price.isFinite() || price <= 0.0)) return LiveReport(false, message = "Invalid LTP")
        if (timestamp <= 0L) return LiveReport(false, message = "Invalid tick timestamp")

        val s = live.getOrPut(instrumentKey) { LiveState() }
        val normalizedTs = normalizeEpochMillis(timestamp)
        val duplicate = s.timestamp == normalizedTs && price != null && s.price == price
        val outOfOrder = s.timestamp > 0L && normalizedTs < s.timestamp
        val stale = now - normalizedTs > MAX_LIVE_AGE_MS

        if (outOfOrder || stale) {
            return LiveReport(
                accepted = false,
                duplicate = duplicate,
                outOfOrder = outOfOrder,
                stale = stale,
                message = when {
                    outOfOrder -> "Out-of-order live tick"
                    else -> "Stale live tick"
                },
            )
        }

        // Identical retransmissions are harmless but need not be processed twice.
        if (duplicate) return LiveReport(false, duplicate = true, message = "Duplicate live tick")

        s.timestamp = normalizedTs
        if (price != null) s.price = price
        s.seenAt = now
        return LiveReport(true)
    }

    @Synchronized
    fun stalledInstruments(now: Long = System.currentTimeMillis(), maxSilenceMs: Long = MAX_SILENCE_MS): List<String> =
        live.filterValues { it.seenAt > 0L && now - it.seenAt > maxSilenceMs }.keys.toList()

    fun validateHistorical(candles: List<UpstoxIntradayCandleClient.Candle>): HistoricalReport {
        if (candles.isEmpty()) return HistoricalReport(false, 0, 0, 0, 0, 0, "No historical candles")

        var duplicates = 0
        var outOfOrder = 0
        var badOhlc = 0
        var sessionGaps = 0
        val seen = HashSet<Long>()
        var previous: UpstoxIntradayCandleClient.Candle? = null

        candles.forEach { c ->
            val ts = c.time.toInstant().toEpochMilli()
            if (!seen.add(ts)) duplicates++
            val values = listOf(c.open, c.high, c.low, c.close)
            val ohlcOk = values.all { it.isFinite() && it > 0.0 } &&
                c.high >= maxOf(c.open, c.close, c.low) &&
                c.low <= minOf(c.open, c.close, c.high) && c.volume >= 0
            if (!ohlcOk) badOhlc++

            previous?.let { p ->
                val pTs = p.time.toInstant().toEpochMilli()
                if (ts < pTs) outOfOrder++
                if (sameSessionDate(p, c)) {
                    val gapMinutes = Duration.between(p.time, c.time).toMinutes()
                    // Allow normal minute progression; flag >3 minutes inside same session.
                    if (gapMinutes > 3 && isMarketMinute(p) && isMarketMinute(c)) sessionGaps++
                }
            }
            previous = c
        }

        val valid = duplicates == 0 && outOfOrder == 0 && badOhlc == 0 && sessionGaps <= MAX_ALLOWED_SESSION_GAPS
        val msg = if (valid) {
            "HISTORICAL DATA OK · ${candles.size} candles"
        } else {
            "Historical integrity failed: dup=$duplicates order=$outOfOrder ohlc=$badOhlc gaps=$sessionGaps"
        }
        return HistoricalReport(valid, candles.size, duplicates, outOfOrder, badOhlc, sessionGaps, msg)
    }

    private fun sameSessionDate(a: UpstoxIntradayCandleClient.Candle, b: UpstoxIntradayCandleClient.Candle): Boolean =
        a.time.toLocalDate() == b.time.toLocalDate()

    private fun isMarketMinute(c: UpstoxIntradayCandleClient.Candle): Boolean {
        val t = c.time.toLocalTime()
        val minute = t.hour * 60 + t.minute
        return minute in (9 * 60 + 15)..(15 * 60 + 30)
    }

    private fun normalizeEpochMillis(value: Long): Long = when {
        value < 10_000_000_000L -> value * 1000L // seconds -> ms
        else -> value
    }

    companion object {
        private const val MAX_LIVE_AGE_MS = 15_000L
        private const val MAX_SILENCE_MS = 20_000L
        private const val MAX_ALLOWED_SESSION_GAPS = 2
    }
}
