package com.parmod.ema.model

/** Central fail-closed risk-lock decision shared by runtime and UI state. */
internal object RiskLockPolicy {
    data class Decision(
        val locked: Boolean,
        val reason: String,
    )

    fun evaluate(
        recoveredPnlUncertain: Boolean,
        realizedPnl: Double,
        dailyLossLimitInr: Double,
    ): Decision {
        require(dailyLossLimitInr > 0.0) { "dailyLossLimitInr must be positive" }
        return when {
            recoveredPnlUncertain -> Decision(
                locked = true,
                reason = "Recovered LIVE exit P&L is unpriced · daily safety lock active",
            )
            realizedPnl <= -dailyLossLimitInr -> Decision(
                locked = true,
                reason = "Daily realized loss reached ₹${"%.0f".format(dailyLossLimitInr)}",
            )
            else -> Decision(
                locked = false,
                reason = "Risk gates clear",
            )
        }
    }
}
