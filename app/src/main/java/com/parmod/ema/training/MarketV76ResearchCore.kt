package com.parmod.ema.training

import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Side-effect-free V7.6 directional/setup/trigger core for parallel AI research.
 * It mirrors the production 5m bias -> 3m setup -> 1m micro-trigger structure but
 * never touches MetaBrainRuntime or global D30 buffers. The coordinator combines
 * this raw signal with a market-scoped [MarketMicrostructureResearch] result.
 */
class MarketV76ResearchCore {
    data class Bar(
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 0,
    )

    data class Evaluation(
        val signal: SignalSnapshot,
        val strategy: String? = null,
        val score: Int = 0,
        val signalTimeMillis: Long = 0L,
    )

    private data class Row(
        val bar: Bar,
        val ema9: Double,
        val ema21: Double,
        val ema50: Double,
        val ema200: Double,
        val vwap: Double,
        val rsi: Double,
        val macdHist: Double,
        val macdHistSlope: Double,
        val atr: Double,
        val atrRatio: Double,
        val adx: Double,
        val plusDi: Double,
        val minusDi: Double,
        val ema9Slope: Double,
        val ema21Slope: Double,
        val ema50Slope: Double,
        val emaSeparationAtr: Double,
        val bbUpper: Double,
        val bbLower: Double,
        val bbWidth: Double,
        val bbWidthRatio: Double,
        val bodyRatio: Double,
        val rangeAtr: Double,
    )

    fun evaluate(oneMinute: List<Bar>, optionChain: List<OptionQuote>, spot: Double, vix: Double): Evaluation {
        if (oneMinute.size < MIN_READY_1M_CANDLES) return wait("1m candles ${oneMinute.size}/$MIN_READY_1M_CANDLES", "V7.6 WARM-UP")
        val entryBars = oneMinute.sortedBy { it.timestamp }
        val setupBars = resample(entryBars, SETUP_TIMEFRAME_MINUTES)
        val biasBars = resample(entryBars, BIAS_TIMEFRAME_MINUTES)
        if (entryBars.size < 30 || setupBars.size < 35 || biasBars.size < 45) return wait("V7.6 multi-timeframe indicators warming", "V7.6 WARM-UP")
        val entry = indicators(entryBars)
        val setup = indicators(setupBars)
        val bias = indicators(biasBars)
        if (entry.isEmpty() || setup.isEmpty() || bias.isEmpty()) return wait("Indicators warming up", "V7.6 WARM-UP")

        val biasRow = bias.last()
        val minuteOfDay = localMinuteOfDay(biasRow.bar.timestamp)
        if (minuteOfDay !in ENTRY_START_MINUTE..ENTRY_END_MINUTE) return wait("Outside entry window", "V7.6 WAIT")
        val context = summarizeOptionChain(optionChain, spot)
        val bullOk = mandatoryAlignment(biasRow, "CE")
        val bearOk = mandatoryAlignment(biasRow, "PE")
        val bull = bullOk && biasRow.ema21Slope >= 0.0 && biasRow.emaSeparationAtr >= MIN_EMA_SEPARATION_ATR &&
            (biasRow.adx >= MIN_ADX || biasRow.atrRatio >= 1.0)
        val bear = bearOk && biasRow.ema21Slope <= 0.0 && biasRow.emaSeparationAtr >= MIN_EMA_SEPARATION_ATR &&
            (biasRow.adx >= MIN_ADX || biasRow.atrRatio >= 1.0)
        val direction = when { bull -> "CE"; bear -> "PE"; else -> null }
            ?: return wait("5m VWAP/EMA/momentum bias not aligned", "WAIT / 5M BIAS")
        val scored = scoreDirection(biasRow, direction, context, spot)
        val required = MIN_ENTRY_SCORE + if (vix > 0.0 && vix < LOW_VIX_LEVEL && biasRow.atrRatio < 1.0) LOW_VIX_EXTRA_SCORE else 0
        if (scored.first < required) return wait("5m score ${scored.first}/$required too low", "WAIT / 5M BIAS", scored.first)
        val setupResult = evaluateSetup(setup, direction)
        val strategy = setupResult.first ?: return wait(setupResult.second, "5M $direction READY", scored.first)
        val trigger = evaluateEntryTrigger(entry, direction, strategy)
        if (!trigger.first) return wait(trigger.second, "3M $strategy READY", scored.first)
        val trend = if (direction == "CE") TrendDirection.BULLISH else TrendDirection.BEARISH
        val action = if (direction == "CE") SignalAction.BUY_CE else SignalAction.BUY_PE
        val confidence = (scored.first * 100 / 11).coerceIn(0, 100)
        val raw = SignalSnapshot(
            action = action,
            confidence = confidence,
            trend = trend,
            entry = spot,
            stopLoss = null,
            target = null,
            reasons = listOf("5m $direction bias score ${scored.first}/11", setupResult.second, trigger.second) + scored.second.take(4),
            setup = "V7.6 $strategy · RESEARCH CORE",
        )
        return Evaluation(raw, strategy, scored.first, trigger.third)
    }

    private fun mandatoryAlignment(row: Row, direction: String): Boolean = if (direction == "CE") {
        row.bar.close > row.vwap && row.ema9 > row.ema21 && row.rsi > MIN_RSI_LONG
    } else {
        row.bar.close < row.vwap && row.ema9 < row.ema21 && row.rsi < MIN_RSI_SHORT
    }

    private fun scoreDirection(row: Row, direction: String, context: Map<String, Double>, spot: Double): Pair<Int, List<String>> {
        var score = 0
        val factors = mutableListOf<String>()
        if (direction == "CE") {
            if (row.bar.close > row.vwap) { score += 2; factors += "VWAP" }
            if (row.ema9 > row.ema21) { score += 2; factors += "EMA9>21" }
            if (row.ema9Slope > 0 && row.ema21Slope >= 0) { score++; factors += "EMA slope" }
            if (row.rsi >= STRONG_RSI_LONG) { score++; factors += "RSI" }
            if (row.macdHist >= 0 || row.macdHistSlope > 0) { score++; factors += "MACD" }
            if (row.atrRatio >= MIN_ATR_RATIO) { score++; factors += "ATR" }
            if (row.bar.close >= row.ema50 || row.ema50Slope > 0) { score++; factors += "EMA50" }
        } else {
            if (row.bar.close < row.vwap) { score += 2; factors += "VWAP" }
            if (row.ema9 < row.ema21) { score += 2; factors += "EMA9<21" }
            if (row.ema9Slope < 0 && row.ema21Slope <= 0) { score++; factors += "EMA slope" }
            if (row.rsi <= STRONG_RSI_SHORT) { score++; factors += "RSI" }
            if (row.macdHist <= 0 || row.macdHistSlope < 0) { score++; factors += "MACD" }
            if (row.atrRatio >= MIN_ATR_RATIO) { score++; factors += "ATR" }
            if (row.bar.close <= row.ema50 || row.ema50Slope < 0) { score++; factors += "EMA50" }
        }
        if (context["available"] == 1.0) {
            val pcr = context["pcr"] ?: 0.0
            val changePcr = context["changePcr"] ?: 0.0
            val callResistance = context["callResistance"] ?: 0.0
            val putSupport = context["putSupport"] ?: 0.0
            if (direction == "CE") {
                if (pcr >= 1.0 || changePcr >= 1.05) { score++; factors += "PCR" }
                if (putSupport > 0 && putSupport < spot) { score++; factors += "put support" }
                else if (callResistance == 0.0 || callResistance - spot >= 1.5 * row.atr) { score++; factors += "OI room" }
            } else {
                if (pcr <= 1.0 || (changePcr > 0 && changePcr <= 0.95)) { score++; factors += "PCR" }
                if (callResistance > spot) { score++; factors += "call resistance" }
                else if (putSupport == 0.0 || spot - putSupport >= 1.5 * row.atr) { score++; factors += "OI room" }
            }
        } else factors += "OI neutral"
        return min(score, 11) to factors
    }

    private fun evaluateSetup(rows: List<Row>, direction: String): Pair<String?, String> {
        if (rows.size < 35) return null to "3m setup warming up"
        val prev = rows[rows.lastIndex - 1]
        val row = rows.last()
        if (!mandatoryAlignment(row, direction)) return null to "3m Price/VWAP/EMA not aligned"
        if (row.rangeAtr > MAX_TRIGGER_RANGE_ATR) return null to "3m candle overextended"
        val breakout: Boolean
        val pullback: Boolean
        if (direction == "CE") {
            val touched = row.bar.low <= max(row.ema9, row.ema21) * 1.0005
            pullback = touched && row.bar.close > row.bar.open && row.bar.close > row.ema9 && row.rsi > MIN_RSI_LONG && row.bodyRatio >= MIN_TRIGGER_BODY_RATIO
            breakout = prev.bar.close <= prev.bbUpper && row.bar.close > row.bbUpper && row.rsi >= STRONG_RSI_LONG &&
                row.bbWidth > prev.bbWidth * MIN_BB_WIDTH_EXPANSION && row.macdHistSlope >= 0 && row.bodyRatio >= MIN_TRIGGER_BODY_RATIO
        } else {
            val touched = row.bar.high >= min(row.ema9, row.ema21) * 0.9995
            pullback = touched && row.bar.close < row.bar.open && row.bar.close < row.ema9 && row.rsi < MIN_RSI_SHORT && row.bodyRatio >= MIN_TRIGGER_BODY_RATIO
            breakout = prev.bar.close >= prev.bbLower && row.bar.close < row.bbLower && row.rsi <= STRONG_RSI_SHORT &&
                row.bbWidth > prev.bbWidth * MIN_BB_WIDTH_EXPANSION && row.macdHistSlope <= 0 && row.bodyRatio >= MIN_TRIGGER_BODY_RATIO
        }
        return when {
            breakout -> "BREAKOUT" to "3m Bollinger expansion breakout"
            pullback -> "PULLBACK" to "3m EMA/VWAP trend pullback"
            else -> null to "Waiting for 3m pullback or fresh breakout"
        }
    }

    private fun evaluateEntryTrigger(rows: List<Row>, direction: String, strategy: String): Triple<Boolean, String, Long> {
        if (rows.size < 30) return Triple(false, "1m trigger warming up", 0L)
        val prev = rows[rows.lastIndex - 1]
        val row = rows.last()
        if (!mandatoryAlignment(row, direction)) return Triple(false, "1m Price/VWAP/EMA not aligned", row.bar.timestamp)
        if (row.bodyRatio < 0.25) return Triple(false, "1m candle body too weak", row.bar.timestamp)
        if (row.rangeAtr > MAX_TRIGGER_RANGE_ATR) return Triple(false, "1m trigger candle too large", row.bar.timestamp)
        val microBreak: Boolean
        val momentum: Boolean
        val macdOk: Boolean
        if (direction == "CE") {
            microBreak = row.bar.close > prev.bar.high && row.bar.close > row.bar.open
            momentum = row.rsi > if (strategy == "BREAKOUT") 52.0 else 50.0
            macdOk = row.macdHist >= 0 || row.macdHistSlope > 0
        } else {
            microBreak = row.bar.close < prev.bar.low && row.bar.close < row.bar.open
            momentum = row.rsi < if (strategy == "BREAKOUT") 48.0 else 50.0
            macdOk = row.macdHist <= 0 || row.macdHistSlope < 0
        }
        val ok = microBreak && momentum && macdOk
        return Triple(ok, if (ok) "1m $direction micro-break confirmed" else "Waiting for 1m $direction micro-break", row.bar.timestamp)
    }

    private fun indicators(bars: List<Bar>): List<Row> {
        val close = bars.map { it.close }
        val ema9 = emaSeries(close, 9)
        val ema21 = emaSeries(close, 21)
        val ema50 = emaSeries(close, 50)
        val ema200 = emaSeries(close, 200)
        val rsi = rsiSeries(close, 9)
        val atr = atrSeries(bars, 14)
        val macdFast = emaSeries(close, 12)
        val macdSlow = emaSeries(close, 26)
        val macd = close.indices.map { macdFast[it] - macdSlow[it] }
        val macdSignal = emaSeries(macd, 9)
        val hist = close.indices.map { macd[it] - macdSignal[it] }
        val adxPack = adxSeries(bars, atr, 14)
        val vwap = sessionVwap(bars)
        return bars.indices.map { i ->
            val bb = bollinger(close, i, 20, 2.0)
            val bbMedian = rollingMedianWidth(close, i, 20)
            val atrMedian = median(atr.subList(max(0, i - 19), i + 1).filter { it > 0 })
            val range = bars[i].high - bars[i].low
            Row(
                bar = bars[i], ema9 = ema9[i], ema21 = ema21[i], ema50 = ema50[i], ema200 = ema200[i], vwap = vwap[i], rsi = rsi[i],
                macdHist = hist[i], macdHistSlope = if (i > 0) hist[i] - hist[i - 1] else 0.0, atr = atr[i],
                atrRatio = if (atrMedian > 0) atr[i] / atrMedian else 0.0, adx = adxPack.first[i], plusDi = adxPack.second[i], minusDi = adxPack.third[i],
                ema9Slope = if (i > 0) ema9[i] - ema9[i - 1] else 0.0, ema21Slope = if (i > 0) ema21[i] - ema21[i - 1] else 0.0,
                ema50Slope = if (i > 0) ema50[i] - ema50[i - 1] else 0.0, emaSeparationAtr = if (atr[i] > 0) abs(ema9[i] - ema21[i]) / atr[i] else 0.0,
                bbUpper = bb.first, bbLower = bb.second, bbWidth = bb.third, bbWidthRatio = if (bbMedian > 0) bb.third / bbMedian else 0.0,
                bodyRatio = if (range > 0) abs(bars[i].close - bars[i].open) / range else 0.0, rangeAtr = if (atr[i] > 0) range / atr[i] else 0.0,
            )
        }
    }

    private fun resample(input: List<Bar>, minutes: Int): List<Bar> {
        val zone = ZoneId.of("Asia/Kolkata")
        return input.groupBy { bar ->
            val z = Instant.ofEpochMilli(bar.timestamp).atZone(zone)
            val mins = z.hour * 60 + z.minute
            val elapsed = mins - 555
            val bucket = if (elapsed >= 0) 555 + (elapsed / minutes) * minutes else mins
            z.toLocalDate().toEpochDay() * 1440L + bucket
        }.toSortedMap().mapNotNull { (_, chunk) ->
            if (chunk.size < minutes) null else Bar(chunk.first().timestamp, chunk.first().open, chunk.maxOf { it.high }, chunk.minOf { it.low }, chunk.last().close, chunk.sumOf { it.volume })
        }
    }

    private fun sessionVwap(bars: List<Bar>): List<Double> {
        val zone = ZoneId.of("Asia/Kolkata")
        val out = MutableList(bars.size) { 0.0 }
        var day = Long.MIN_VALUE
        var pv = 0.0
        var w = 0.0
        bars.forEachIndexed { i, b ->
            val d = Instant.ofEpochMilli(b.timestamp).atZone(zone).toLocalDate().toEpochDay()
            if (d != day) { day = d; pv = 0.0; w = 0.0 }
            val weight = if (b.volume > 0) b.volume.toDouble() else 1.0
            val tp = (b.high + b.low + b.close) / 3.0
            pv += tp * weight
            w += weight
            out[i] = if (w > 0) pv / w else b.close
        }
        return out
    }

    private fun emaSeries(v: List<Double>, period: Int): List<Double> {
        if (v.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1.0)
        val out = MutableList(v.size) { 0.0 }
        var e = v.first()
        out[0] = e
        for (i in 1 until v.size) { e = v[i] * k + e * (1.0 - k); out[i] = e }
        return out
    }

    private fun rsiSeries(v: List<Double>, period: Int): List<Double> {
        val out = MutableList(v.size) { 50.0 }
        if (v.size < 2) return out
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1 until v.size) {
            val d = v[i] - v[i - 1]
            val g = max(d, 0.0)
            val l = max(-d, 0.0)
            avgGain = if (i == 1) g else avgGain + (g - avgGain) / period
            avgLoss = if (i == 1) l else avgLoss + (l - avgLoss) / period
            out[i] = if (avgLoss <= 0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
        return out
    }

    private fun atrSeries(b: List<Bar>, period: Int): List<Double> {
        val out = MutableList(b.size) { 0.0 }
        var a = 0.0
        for (i in b.indices) {
            val tr = if (i == 0) b[i].high - b[i].low else max(b[i].high - b[i].low, max(abs(b[i].high - b[i - 1].close), abs(b[i].low - b[i - 1].close)))
            a = if (i == 0) tr else a + (tr - a) / period
            out[i] = a
        }
        return out
    }

    private fun adxSeries(b: List<Bar>, atr: List<Double>, period: Int): Triple<List<Double>, List<Double>, List<Double>> {
        val adx = MutableList(b.size) { 0.0 }
        val plus = MutableList(b.size) { 0.0 }
        val minus = MutableList(b.size) { 0.0 }
        var p = 0.0
        var m = 0.0
        var dxSmooth = 0.0
        for (i in 1 until b.size) {
            val up = b[i].high - b[i - 1].high
            val down = b[i - 1].low - b[i].low
            val pdm = if (up > down && up > 0) up else 0.0
            val mdm = if (down > up && down > 0) down else 0.0
            p += (pdm - p) / period
            m += (mdm - m) / period
            if (atr[i] > 0) { plus[i] = 100 * p / atr[i]; minus[i] = 100 * m / atr[i] }
            val den = plus[i] + minus[i]
            val dx = if (den > 0) 100 * abs(plus[i] - minus[i]) / den else 0.0
            dxSmooth += (dx - dxSmooth) / period
            adx[i] = dxSmooth
        }
        return Triple(adx, plus, minus)
    }

    private fun bollinger(v: List<Double>, i: Int, period: Int, std: Double): Triple<Double, Double, Double> {
        val s = v.subList(max(0, i - period + 1), i + 1)
        val mean = s.average()
        val sd = sqrt(s.map { (it - mean) * (it - mean) }.average())
        val up = mean + std * sd
        val dn = mean - std * sd
        val width = if (mean != 0.0) (up - dn) / mean else 0.0
        return Triple(up, dn, width)
    }

    private fun rollingMedianWidth(v: List<Double>, i: Int, period: Int): Double =
        median((max(0, i - 19)..i).map { bollinger(v, it, period, 2.0).third })

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun summarizeOptionChain(chain: List<OptionQuote>, spot: Double): Map<String, Double> {
        if (chain.isEmpty()) return mapOf("available" to 0.0)
        val calls = chain.filter { it.type == "CE" }
        val puts = chain.filter { it.type == "PE" }
        val callOi = calls.sumOf { it.openInterest.toDouble() }
        val putOi = puts.sumOf { it.openInterest.toDouble() }
        val callChange = calls.sumOf { max(it.changeInOpenInterest, 0L).toDouble() }
        val putChange = puts.sumOf { max(it.changeInOpenInterest, 0L).toDouble() }
        val resistance = calls.filter { it.strike >= spot }.maxByOrNull { it.openInterest }?.strike ?: 0.0
        val support = puts.filter { it.strike <= spot }.maxByOrNull { it.openInterest }?.strike ?: 0.0
        return mapOf(
            "available" to 1.0,
            "pcr" to if (callOi > 0) putOi / callOi else 0.0,
            "changePcr" to if (callChange > 0) putChange / callChange else 0.0,
            "callResistance" to resistance,
            "putSupport" to support,
        )
    }

    private fun localMinuteOfDay(ts: Long): Int {
        val z = Instant.ofEpochMilli(ts).atZone(ZoneId.of("Asia/Kolkata"))
        return z.hour * 60 + z.minute
    }

    private fun wait(reason: String, setup: String, score: Int = 0) = Evaluation(
        SignalSnapshot(
            SignalAction.WAIT,
            (score * 100 / 11).coerceIn(0, 100),
            TrendDirection.NEUTRAL,
            null, null, null,
            listOf(reason),
            setup,
        ),
        score = score,
    )

    companion object {
        const val MIN_READY_1M_CANDLES = 220
        private const val SETUP_TIMEFRAME_MINUTES = 3
        private const val BIAS_TIMEFRAME_MINUTES = 5
        private const val ENTRY_START_MINUTE = 9 * 60 + 25
        private const val ENTRY_END_MINUTE = 14 * 60 + 45
        private const val MIN_ENTRY_SCORE = 7
        private const val LOW_VIX_EXTRA_SCORE = 1
        private const val MIN_RSI_LONG = 50.0
        private const val STRONG_RSI_LONG = 55.0
        private const val MIN_RSI_SHORT = 50.0
        private const val STRONG_RSI_SHORT = 45.0
        private const val MIN_ATR_RATIO = 0.78
        private const val MIN_ADX = 16.0
        private const val MIN_EMA_SEPARATION_ATR = 0.025
        private const val MIN_TRIGGER_BODY_RATIO = 0.35
        private const val MAX_TRIGGER_RANGE_ATR = 1.8
        private const val MIN_BB_WIDTH_EXPANSION = 1.01
        private const val LOW_VIX_LEVEL = 12.5
    }
}
