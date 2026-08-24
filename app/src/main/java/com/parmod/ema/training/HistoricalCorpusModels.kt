package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.time.LocalDate

enum class HistoricalCorpusSource(val label: String) {
    UPSTOX("UPSTOX"),
    LOCAL("LOCAL"),
    DOWNLOADED("DOWNLOADED"),
    LIVE_ARCHIVE("LIVE ARCHIVE"),
    COMBINED("COMBINED"),
}

enum class HistoricalMarketScope(val label: String) {
    NIFTY("NIFTY"),
    SENSEX("SENSEX"),
    BOTH("BOTH"),
    ;

    fun singleIndexOrNull(): MarketIndex? = when (this) {
        NIFTY -> MarketIndex.NIFTY
        SENSEX -> MarketIndex.SENSEX
        BOTH -> null
    }
}

data class HistoricalOptionSeries(
    val index: MarketIndex,
    val optionType: String,
    val strike: Double,
    val expiry: LocalDate,
    val lotSize: Int,
    val symbol: String,
    val source: String,
    val candles: List<UpstoxPlusHistoricalClient.Candle>,
    /** Past/at-signal underlying index bars; empty means legacy option-premium proxy fallback. */
    val underlyingCandles: List<UpstoxPlusHistoricalClient.Candle> = emptyList(),
) {
    val key: String get() = "${index.name}|${expiry}|${strike}|${optionType.uppercase()}"
    val hasNativeUnderlyingContext: Boolean get() = underlyingCandles.isNotEmpty()
}

data class LocalCorpusSummary(
    val filesImported: Int = 0,
    val supportedFiles: Int = 0,
    val rowsRead: Long = 0,
    val rowsAccepted: Long = 0,
    val rowsRejected: Long = 0,
    val duplicatesRemoved: Long = 0,
    val optionContracts: Int = 0,
    val niftyContracts: Int = 0,
    val sensexContracts: Int = 0,
    val ceContracts: Int = 0,
    val peContracts: Int = 0,
    val inferredLotSizeContracts: Int = 0,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val trainable: Boolean get() = optionContracts > 0 && rowsAccepted > 0
    val bothMarketsPresent: Boolean get() = niftyContracts > 0 && sensexContracts > 0
}
