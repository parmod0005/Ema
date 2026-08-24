package com.parmod.ema.data

import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.OptionQuote
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Read-only Upstox bootstrap client. Broker order calls live only in UpstoxOrderClient. */
class UpstoxLiveClient(private val accessToken: String) {
    data class Snapshot(
        val spot: Double,
        val options: List<OptionQuote>,
        val underlyingKey: String,
    )

    fun fetchSnapshot(index: MarketIndex, expiryDate: String): Snapshot {
        require(accessToken.isNotBlank()) { "Access token is required" }
        require(expiryDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Expiry must be YYYY-MM-DD" }
        val underlying = when (index) {
            MarketIndex.NIFTY -> "NSE_INDEX|Nifty 50"
            MarketIndex.SENSEX -> "BSE_INDEX|SENSEX"
        }
        val contracts = UpstoxOptionDiscoveryClient(accessToken)
            .discover(index)
            .contractsByExpiry[expiryDate]
            .orEmpty()
        val lotSizeByInstrument = contracts.associate { it.instrumentKey to it.lotSize }
        val spot = fetchLtp(underlying)
        val chain = fetchOptionChain(underlying, expiryDate, spot, index, lotSizeByInstrument)
        return Snapshot(spot, chain, underlying)
    }

    fun authorizedSocketUrl(): String {
        val json = getJson("https://api.upstox.com/v3/feed/market-data-feed/authorize")
        return json.getJSONObject("data").getString("authorized_redirect_uri")
    }

    private fun fetchLtp(instrumentKey: String): Double {
        val encoded = URLEncoder.encode(instrumentKey, Charsets.UTF_8.name())
        val json = getJson("https://api.upstox.com/v3/market-quote/ltp?instrument_key=$encoded")
        val data = json.getJSONObject("data")
        val item = data.keys().asSequence().map { data.getJSONObject(it) }.firstOrNull() ?: error("No LTP returned")
        return item.getDouble("last_price")
    }

    private fun fetchOptionChain(
        underlying: String,
        expiryDate: String,
        spot: Double,
        index: MarketIndex,
        lotSizeByInstrument: Map<String, Int>,
    ): List<OptionQuote> {
        val encoded = URLEncoder.encode(underlying, Charsets.UTF_8.name())
        val json = getJson("https://api.upstox.com/v2/option/chain?instrument_key=$encoded&expiry_date=$expiryDate")
        val rows = json.getJSONArray("data")
        val step = if (index == MarketIndex.NIFTY) 50 else 100
        val atm = kotlin.math.round(spot / step).toInt() * step
        val result = mutableListOf<OptionQuote>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val strike = row.getDouble("strike_price")
            if (strike < atm - 5 * step || strike > atm + 5 * step) continue
            parseOption(row.optJSONObject("call_options"), strike, "CE", strike.toInt() == atm, lotSizeByInstrument)?.let(result::add)
            parseOption(row.optJSONObject("put_options"), strike, "PE", strike.toInt() == atm, lotSizeByInstrument)?.let(result::add)
        }
        if (result.isEmpty()) error("No option-chain rows for selected expiry")
        return result.sortedWith(compareBy<OptionQuote> { it.strike }.thenBy { it.type })
    }

    private fun parseOption(
        node: JSONObject?,
        strike: Double,
        type: String,
        isAtm: Boolean,
        lotSizeByInstrument: Map<String, Int>,
    ): OptionQuote? {
        node ?: return null
        val market = node.optJSONObject("market_data") ?: return null
        val greeks = node.optJSONObject("option_greeks") ?: JSONObject()
        val oi = market.optLong("oi", 0L)
        val previousOi = market.optLong("prev_oi", oi)
        val instrumentKey = node.optString("instrument_key")
        return OptionQuote(
            strike = strike,
            type = type,
            ltp = market.optDouble("ltp", 0.0),
            openInterest = oi,
            changeInOpenInterest = oi - previousOi,
            delta = greeks.optDouble("delta", 0.0),
            gamma = greeks.optDouble("gamma", 0.0),
            isAtm = isAtm,
            instrumentKey = instrumentKey,
            bid = market.optDouble("bid_price", 0.0),
            ask = market.optDouble("ask_price", 0.0),
            volume = market.optLong("volume", 0L),
            lotSize = lotSizeByInstrument[instrumentKey] ?: 0,
        )
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Upstox HTTP $code: $body")
            JSONObject(body)
        } finally { connection.disconnect() }
    }
}
