package com.parmod.ema.engine

import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.SignalTimeframe
import com.parmod.ema.model.TrendDirection
import java.time.Instant
import java.time.ZoneId

/**
 * Completed-candle confirmation layer for E1/E2.
 * The underlying tick-native engines remain authoritative; this layer can confirm or veto
 * a direction but never manufacture an opposite signal by itself.
 *
 * The full runtime uses this as the shared PAPER/LIVE automatic-entry clock gate, so E1/E2
 * cannot keep producing actionable automatic entries after the configured 15:10 IST cutoff.
 * Manual order buttons remain explicit user actions and do not depend on this signal gate.
 */
class MultiTimeframeConfirmation(
    private val signalEngine: SignalEngineV2 = SignalEngineV2(),
) {
    data class TimedBar(
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 0L,
    )

    data class Result(
        val ready: Boolean,
        val direction: TrendDirection,
        val score: Int,
        val triggerBars: Int,
        val setupBars: Int,
        val biasBars: Int,
        val reasons: List<String>,
    ) {
        fun confirms(direction: TrendDirection): Boolean = ready && this.direction == direction
    }

    fun evaluate(
        oneMinuteBars: List<TimedBar>,
        config: EngineTimeframeConfig,
        enforceEntryWindow: Boolean = true,
    ): Result {
        val base = oneMinuteBars.sortedBy { it.timestamp }
        val trigger = resample(base, config.trigger)
        val setup = resample(base, config.setup)
        val bias = resample(base, config.bias)

        if (enforceEntryWindow && base.isNotEmpty() && !isEntryWindow(base.last().timestamp)) {
            return Result(
                ready = false,
                direction = TrendDirection.NEUTRAL,
                score = 0,
                triggerBars = trigger.size,
                setupBars = setup.size,
                biasBars = bias.size,
                reasons = listOf(
                    "Automatic entry window closed",
                    "E1/E2 entries allowed 09:25-15:10 IST",
                ),
            )
        }

        if (trigger.size < 55 || setup.size < 55 || bias.size < 55) {
            return Result(
                ready = false,
                direction = TrendDirection.NEUTRAL,
                score = 0,
                triggerBars = trigger.size,
                setupBars = setup.size,
                biasBars = bias.size,
                reasons = listOf(
                    "MTF warm-up ${config.trigger.label}/${config.setup.label}/${config.bias.label}",
                    "bars ${trigger.size}/${setup.size}/${bias.size}",
                ),
            )
        }

        val evalConfig = SignalEngineV2.Config(minimumScore = 60)
        val triggerEval = signalEngine.evaluate(trigger.map(::toSignalBar), evalConfig)
        val setupEval = signalEngine.evaluate(setup.map(::toSignalBar), evalConfig)
        val biasEval = signalEngine.evaluate(bias.map(::toSignalBar), evalConfig)
        val triggerDirection = direction(triggerEval)
        val setupDirection = direction(setupEval)
        val biasDirection = direction(biasEval)
        val aligned = triggerDirection != TrendDirection.NEUTRAL &&
            triggerDirection == setupDirection && setupDirection == biasDirection
        val score = ((triggerEval.score * 0.25) + (setupEval.score * 0.40) + (biasEval.score * 0.35))
            .toInt()
            .coerceIn(0, 100)

        val reasons = buildList {
            add("MTF ${config.trigger.label}/${config.setup.label}/${config.bias.label}")
            add("trigger ${triggerEval.score} · setup ${setupEval.score} · bias ${biasEval.score}")
            if (aligned) add("Completed-candle direction aligned") else add("Completed-candle direction not aligned")
            addAll(setupEval.reasons.take(2))
            addAll(biasEval.reasons.take(2))
        }
        return Result(
            ready = true,
            direction = if (aligned) triggerDirection else TrendDirection.NEUTRAL,
            score = score,
            triggerBars = trigger.size,
            setupBars = setup.size,
            biasBars = bias.size,
            reasons = reasons,
        )
    }

    private fun isEntryWindow(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        val local = Instant.ofEpochMilli(timestamp).atZone(INDIA_ZONE)
        val minute = local.hour * 60 + local.minute
        return minute in ENTRY_START_MINUTE..ENTRY_END_MINUTE
    }

    private fun direction(e: SignalEngineV2.Evaluation): TrendDirection = when (e.direction) {
        SignalEngineV2.Direction.BULLISH -> TrendDirection.BULLISH
        SignalEngineV2.Direction.BEARISH -> TrendDirection.BEARISH
        SignalEngineV2.Direction.NEUTRAL -> TrendDirection.NEUTRAL
    }

    private fun toSignalBar(bar: TimedBar) = SignalEngineV2.Bar(
        open = bar.open,
        high = bar.high,
        low = bar.low,
        close = bar.close,
        volume = bar.volume,
    )

    private fun resample(input: List<TimedBar>, timeframe: SignalTimeframe): List<TimedBar> {
        if (timeframe == SignalTimeframe.M1) return input
        val bucketMillis = timeframe.minutes * 60_000L
        return input.groupBy { (it.timestamp / bucketMillis) * bucketMillis }
            .toSortedMap()
            .values
            .mapNotNull { group ->
                val expected = timeframe.minutes
                if (group.size < expected) return@mapNotNull null
                TimedBar(
                    timestamp = group.first().timestamp,
                    open = group.first().open,
                    high = group.maxOf { it.high },
                    low = group.minOf { it.low },
                    close = group.last().close,
                    volume = group.sumOf { it.volume },
                )
            }
    }

    companion object {
        private val INDIA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        private const val ENTRY_START_MINUTE = 9 * 60 + 25
        private const val ENTRY_END_MINUTE = 15 * 60 + 10
    }
}
