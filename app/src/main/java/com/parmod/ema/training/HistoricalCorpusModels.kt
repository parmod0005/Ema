package com.parmod.ema.training

import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import java.time.LocalDate

enum class HistoricalCorpusSource(val label: String) {
    UPSTOX("UPSTOX"),
    LOCAL("LOCAL"),
    COMBINED("COMBINED"),
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
) {
    val key: String
        get() = "${index.name}|${expiry}|${strike}|${optionType.uppercase()}"
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
}
