package com.parmod.ema.engine

import kotlin.math.max

/**
 * Deterministic long-option position manager used by paper trading.
 * Handles initial stop, breakeven, trailing stop, target and opposite-signal exit.
 */
class ExecutionEngineV2 {
    data class Config(
        val initialStopPct: Double = 0.15,
        val targetPct: Double = 0.30,
        val breakevenTriggerPct: Double = 0.12,
        val breakevenLockPct: Double = 0.01,
        val trailingTriggerPct: Double = 0.18,
        val trailingDistancePct: Double = 0.08,
    )

    data class State(
        val entryPrice: Double,
        val highestPrice: Double,
        val stopPrice: Double,
        val targetPrice: Double,
        val breakevenActive: Boolean = false,
        val trailingActive: Boolean = false,
    )

    enum class ExitReason { STOP_LOSS, TARGET, OPPOSITE_SIGNAL }

    data class Update(
        val state: State,
        val exitReason: ExitReason? = null,
    )

    fun open(entryPrice: Double, config: Config = Config()): State {
        require(entryPrice > 0.0) { "Entry price must be positive" }
        return State(
            entryPrice = entryPrice,
            highestPrice = entryPrice,
            stopPrice = entryPrice * (1.0 - config.initialStopPct),
            targetPrice = entryPrice * (1.0 + config.targetPct),
        )
    }

    fun update(
        previous: State,
        currentPrice: Double,
        oppositeSignal: Boolean,
        config: Config = Config(),
    ): Update {
        require(currentPrice >= 0.0) { "Current price cannot be negative" }
        val highest = max(previous.highestPrice, currentPrice)
        val gain = (highest - previous.entryPrice) / previous.entryPrice

        val breakevenActive = previous.breakevenActive || gain >= config.breakevenTriggerPct
        val trailingActive = previous.trailingActive || gain >= config.trailingTriggerPct

        var stop = previous.stopPrice
        if (breakevenActive) {
            stop = max(stop, previous.entryPrice * (1.0 + config.breakevenLockPct))
        }
        if (trailingActive) {
            stop = max(stop, highest * (1.0 - config.trailingDistancePct))
        }

        val next = previous.copy(
            highestPrice = highest,
            stopPrice = stop,
            breakevenActive = breakevenActive,
            trailingActive = trailingActive,
        )

        val reason = when {
            oppositeSignal -> ExitReason.OPPOSITE_SIGNAL
            currentPrice <= next.stopPrice -> ExitReason.STOP_LOSS
            currentPrice >= next.targetPrice -> ExitReason.TARGET
            else -> null
        }
        return Update(next, reason)
    }
}
