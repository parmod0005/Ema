package com.parmod.ema.risk

class TradingRiskPolicy(
    val maximumDailyLoss: Double = 1_000.0,
    val maximumOpenRisk: Double = 500.0,
    val maximumTradesPerDay: Int = 4,
    val maximumLotsPerTrade: Int = 1,
    val minimumSignalConfidence: Int = 85,
) {
    data class Snapshot(
        val realizedPnlToday: Double,
        val openRisk: Double,
        val tradesToday: Int,
        val requestedLots: Int,
        val signalConfidence: Int,
        val marketDataConnected: Boolean,
        val tokenVerified: Boolean,
        val liveToggleEnabled: Boolean,
        val userConfirmationGranted: Boolean,
    )

    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(snapshot: Snapshot): Decision = when {
        !snapshot.liveToggleEnabled -> Decision(false, "Live trading is OFF")
        !snapshot.userConfirmationGranted -> Decision(false, "Live trading requires explicit confirmation")
        !snapshot.marketDataConnected -> Decision(false, "Live market data is disconnected")
        !snapshot.tokenVerified -> Decision(false, "Upstox token is not verified")
        snapshot.realizedPnlToday <= -maximumDailyLoss -> Decision(false, "Daily loss limit reached")
        snapshot.openRisk > maximumOpenRisk -> Decision(false, "Open-risk limit exceeded")
        snapshot.tradesToday >= maximumTradesPerDay -> Decision(false, "Daily trade limit reached")
        snapshot.requestedLots !in 1..maximumLotsPerTrade -> Decision(false, "Requested lot size is outside policy")
        snapshot.signalConfidence < minimumSignalConfidence -> Decision(false, "Signal confidence is below live threshold")
        else -> Decision(true, "Risk checks passed")
    }
}
