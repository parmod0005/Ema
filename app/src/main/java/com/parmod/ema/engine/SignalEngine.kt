package com.parmod.ema.engine

import com.parmod.ema.model.SignalAction
import com.parmod.ema.model.SignalSnapshot
import com.parmod.ema.model.TrendDirection
import kotlin.math.abs

data class SignalInput(
    val spot: Double,
    val ema20: Double,
    val ema50: Double,
    val ema20ThreeBarsAgo: Double,
    val ema50ThreeBarsAgo: Double,
    val atr: Double,
    val adx: Double,
    val volumeRatio: Double,
    val higherTimeframeBullish: Boolean,
    val higherTimeframeBearish: Boolean,
    val bullishStructure: Boolean,
    val bearishStructure: Boolean,
    val emaCrossesLastTenBars: Int,
    val priceCrossesEma20LastTenBars: Int,
)

class SignalEngine {
    fun evaluate(input: SignalInput): SignalSnapshot {
        require(input.atr >= 0.0) { "ATR cannot be negative" }

        val ema20Slope = input.ema20 - input.ema20ThreeBarsAgo
        val ema50Slope = input.ema50 - input.ema50ThreeBarsAgo
        val separationRatio = if (input.atr > 0.0) abs(input.ema20 - input.ema50) / input.atr else 0.0

        val isChoppy = input.adx < 18.0 ||
            separationRatio < 0.15 ||
            input.emaCrossesLastTenBars >= 3 ||
            input.priceCrossesEma20LastTenBars > 4

        if (isChoppy) {
            return waitSignal(
                reasons = listOf(
                    "Chop filter active",
                    "ADX ${input.adx.toInt()} · EMA separation ${"%.2f".format(separationRatio)} ATR",
                ),
            )
        }

        val bullish = input.ema20 > input.ema50 &&
            ema20Slope > 0.0 && ema50Slope >= 0.0 &&
            input.higherTimeframeBullish && input.bullishStructure

        val bearish = input.ema20 < input.ema50 &&
            ema20Slope < 0.0 && ema50Slope <= 0.0 &&
            input.higherTimeframeBearish && input.bearishStructure

        val score = score(input, bullish, bearish, separationRatio)
        if (score < 75 || (!bullish && !bearish)) {
            return waitSignal(listOf("Setup score $score/100", "Waiting for stronger confirmation"), score)
        }

        val risk = (input.atr * 0.8).coerceAtLeast(input.spot * 0.001)
        return if (bullish) {
            SignalSnapshot(
                action = SignalAction.BUY_CE,
                confidence = score,
                trend = TrendDirection.BULLISH,
                entry = input.spot,
                stopLoss = input.spot - risk,
                target = input.spot + (risk * 1.8),
                reasons = listOf("EMA20 above EMA50 with positive slope", "Higher timeframe bullish", "ADX and structure confirmed"),
            )
        } else {
            SignalSnapshot(
                action = SignalAction.BUY_PE,
                confidence = score,
                trend = TrendDirection.BEARISH,
                entry = input.spot,
                stopLoss = input.spot + risk,
                target = input.spot - (risk * 1.8),
                reasons = listOf("EMA20 below EMA50 with negative slope", "Higher timeframe bearish", "ADX and structure confirmed"),
            )
        }
    }

    private fun score(input: SignalInput, bullish: Boolean, bearish: Boolean, separationRatio: Double): Int {
        var score = 0
        if (bullish || bearish) score += 35
        if (input.adx >= 23.0) score += 15 else if (input.adx >= 20.0) score += 10
        if (separationRatio >= 0.30) score += 15 else if (separationRatio >= 0.20) score += 10
        if (input.volumeRatio >= 1.30) score += 10 else if (input.volumeRatio >= 1.10) score += 5
        if (input.bullishStructure || input.bearishStructure) score += 15
        if (input.higherTimeframeBullish || input.higherTimeframeBearish) score += 10
        return score.coerceIn(0, 100)
    }

    private fun waitSignal(reasons: List<String>, confidence: Int = 0) = SignalSnapshot(
        action = SignalAction.WAIT,
        confidence = confidence,
        trend = TrendDirection.NEUTRAL,
        entry = null,
        stopLoss = null,
        target = null,
        reasons = reasons,
    )
}
