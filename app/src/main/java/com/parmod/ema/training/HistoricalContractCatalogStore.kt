package com.parmod.ema.training

import android.content.Context
import com.parmod.ema.backtest.UpstoxPlusHistoricalClient
import com.parmod.ema.model.MarketIndex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * Persistent metadata catalogue for expired NIFTY/SENSEX option contracts.
 *
 * Upstox's current expiry-discovery response can be shorter than the candle history
 * accessible for an already-known expired instrument key. This catalogue therefore
 * retains every verified contract ever discovered/imported and unions it with fresh
 * discovery. No expiry date, instrument key or market identity is inferred from exchange
 * segment alone.
 */
class HistoricalContractCatalogStore(context: Context) {
    data class Summary(
        val niftyExpiries: Int = 0,
        val sensexExpiries: Int = 0,
        val contracts: Int = 0,
        val fromDate: LocalDate? = null,
        val toDate: LocalDate? = null,
    )

    private val root = File(context.applicationContext.filesDir, "vardhani_historical_contract_catalog/v$SCHEMA").apply { mkdirs() }

    @Synchronized
    fun merge(index: MarketIndex, expiry: LocalDate, incoming: List<UpstoxPlusHistoricalClient.ExpiredContract>): Int {
        val valid = incoming.filter {
            it.expiry == expiry && it.instrumentKey.isNotBlank() && it.strike > 0.0 &&
                it.optionType in setOf("CE", "PE") && it.lotSize > 0
        }
        if (valid.isEmpty()) return 0
        val old = contracts(index, expiry)
        val merged = (old + valid)
            .distinctBy { "${it.instrumentKey}|${it.optionType}|${it.strike}" }
            .sortedWith(compareBy<UpstoxPlusHistoricalClient.ExpiredContract> { it.strike }.thenBy { it.optionType }.thenBy { it.instrumentKey })
        write(index, expiry, merged)
        return (merged.size - old.size).coerceAtLeast(0)
    }

    fun contracts(index: MarketIndex, expiry: LocalDate): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        val file = file(index, expiry)
        if (!file.isFile) return emptyList()
        return runCatching { decode(file.readText(), index, expiry) }.getOrElse { emptyList() }
    }

    fun expiries(index: MarketIndex): List<LocalDate> = files(index)
        .mapNotNull { file -> parseDate(file.name.removeSuffix(".$EXT").substringAfter('_', "")) }
        .distinct()
        .sorted()

    fun summary(): Summary {
        val nifty = expiries(MarketIndex.NIFTY)
        val sensex = expiries(MarketIndex.SENSEX)
        val all = nifty.map { MarketIndex.NIFTY to it } + sensex.map { MarketIndex.SENSEX to it }
        return Summary(
            niftyExpiries = nifty.size,
            sensexExpiries = sensex.size,
            contracts = all.sumOf { (index, expiry) -> contracts(index, expiry).size },
            fromDate = all.minOfOrNull { it.second },
            toDate = all.maxOfOrNull { it.second },
        )
    }

    @Synchronized
    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun write(index: MarketIndex, expiry: LocalDate, contracts: List<UpstoxPlusHistoricalClient.ExpiredContract>) {
        val target = file(index, expiry)
        val temp = File(root, target.name + ".${System.nanoTime()}.tmp")
        val rows = JSONArray()
        contracts.forEach { c ->
            rows.put(
                JSONObject()
                    .put("instrument_key", c.instrumentKey)
                    .put("expiry", c.expiry.toString())
                    .put("strike_price", c.strike)
                    .put("instrument_type", c.optionType)
                    .put("lot_size", c.lotSize)
                    .put("trading_symbol", c.tradingSymbol),
            )
        }
        val rootJson = JSONObject()
            .put("schema", SCHEMA)
            .put("complete", true)
            .put("market", index.name)
            .put("expiry", expiry.toString())
            .put("contracts", rows)
        temp.writeText(rootJson.toString())
        val verified = decode(temp.readText(), index, expiry)
        require(verified.size == contracts.size) { "Historical contract catalogue verification failed" }
        val atomic = runCatching {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
        if (!atomic) Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun decode(text: String, expectedIndex: MarketIndex, expectedExpiry: LocalDate): List<UpstoxPlusHistoricalClient.ExpiredContract> {
        val rootJson = JSONObject(text)
        require(rootJson.optInt("schema") == SCHEMA && rootJson.optBoolean("complete", false))
        require(rootJson.getString("market") == expectedIndex.name)
        require(LocalDate.parse(rootJson.getString("expiry")) == expectedExpiry)
        val rows = rootJson.getJSONArray("contracts")
        return buildList {
            for (i in 0 until rows.length()) parseContract(rows.getJSONObject(i), expectedExpiry)?.let(::add)
        }.distinctBy { "${it.instrumentKey}|${it.optionType}|${it.strike}" }
    }

    private fun file(index: MarketIndex, expiry: LocalDate): File = File(root, "${index.name}_${expiry}.$EXT")
    private fun files(index: MarketIndex): List<File> = root.listFiles { file ->
        file.isFile && file.extension == EXT && file.name.startsWith("${index.name}_")
    }?.sortedBy { it.name }.orEmpty()

    companion object {
        private const val SCHEMA = 1
        private const val EXT = "vhc"

        /** Parse one Upstox option-contract object without inventing missing metadata. */
        fun parseContract(item: JSONObject, fallbackExpiry: LocalDate? = null): UpstoxPlusHistoricalClient.ExpiredContract? {
            val type = item.optString("instrument_type").uppercase()
            if (type != "CE" && type != "PE") return null
            val key = item.optString("instrument_key")
            val strike = item.optDouble("strike_price", Double.NaN)
            val expiry = runCatching {
                LocalDate.parse(item.optString("expiry").ifBlank { fallbackExpiry?.toString().orEmpty() })
            }.getOrNull() ?: return null
            val lot = item.optInt("lot_size", item.optInt("minimum_lot", 0))
            if (key.isBlank() || !strike.isFinite() || strike <= 0.0 || lot <= 0) return null
            return UpstoxPlusHistoricalClient.ExpiredContract(
                instrumentKey = key,
                expiry = expiry,
                strike = strike,
                optionType = type,
                lotSize = lot,
                tradingSymbol = item.optString("trading_symbol"),
            )
        }

        /**
         * Market identity must be explicit in underlying/name/symbol/path metadata.
         * NSE_FO/BSE_FO instrument-key prefixes identify an exchange segment, not NIFTY/SENSEX.
         */
        fun inferMarket(item: JSONObject, pathHint: String = ""): MarketIndex? {
            val explicit = listOf(
                item.optString("underlying_key"),
                item.optString("underlying_symbol"),
                item.optString("underlying_name"),
                item.optString("name"),
                item.optString("trading_symbol"),
                pathHint,
            ).joinToString("|").uppercase()
            return when {
                "SENSEX" in explicit -> MarketIndex.SENSEX
                "NIFTY 50" in explicit || "NIFTY50" in explicit || "NIFTY-50" in explicit ||
                    "NIFTY_50" in explicit || "NIFTY" in explicit -> MarketIndex.NIFTY
                else -> null
            }
        }

        private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
    }
}
