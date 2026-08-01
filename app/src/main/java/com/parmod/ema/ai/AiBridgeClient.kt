package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTPS client for the VARDHANI AI Bridge.
 *
 * The OpenAI API key belongs on the bridge server, never in the Android app.
 * The app sends only a compact market snapshot and receives a versioned,
 * strictly parsed trade decision. This class contains no broker order methods.
 */
class AiBridgeClient(
    private val baseUrl: String,
    private val deviceToken: String,
    private val connectTimeoutMillis: Int = 4_000,
    private val readTimeoutMillis: Int = 8_000,
) {
    data class Response(
        val decision: AiTradeDecision,
        val latencyMillis: Long,
    )

    fun analyze(snapshot: AiMarketSnapshot): Response {
        require(baseUrl.startsWith("https://")) { "AI bridge must use HTTPS" }
        require(deviceToken.isNotBlank()) { "AI bridge device token is required" }
        val started = System.currentTimeMillis()
        val body = snapshot.toJson().toString()
        val json = postJson("${baseUrl.trimEnd('/')}/v1/analyze", body)
        val decision = parseDecision(json.getJSONObject("decision"))
        require(decision.snapshotId == snapshot.snapshotId) { "AI response snapshot mismatch" }
        return Response(decision, System.currentTimeMillis() - started)
    }

    fun health(): AiBridgeHealth {
        if (!baseUrl.startsWith("https://") || deviceToken.isBlank()) return AiBridgeHealth()
        val started = System.currentTimeMillis()
        return try {
            val json = getJson("${baseUrl.trimEnd('/')}/v1/health")
            val latency = System.currentTimeMillis() - started
            AiBridgeHealth(
                configured = true,
                reachable = json.optBoolean("ok", false),
                lastLatencyMillis = latency,
                lastSuccessMillis = System.currentTimeMillis(),
                message = json.optString("message", "AI bridge available"),
            )
        } catch (error: Exception) {
            AiBridgeHealth(
                configured = true,
                reachable = false,
                consecutiveFailures = 1,
                message = error.message?.take(160) ?: "AI bridge unavailable",
            )
        }
    }

    private fun postJson(url: String, body: String): JSONObject = request(url, "POST", body)
    private fun getJson(url: String): JSONObject = request(url, "GET", null)

    private fun request(url: String, method: String, body: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            connection.setRequestProperty("X-Vardhani-Schema", "1")
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("AI bridge HTTP $code: ${responseBody.take(240)}")
            JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseDecision(json: JSONObject): AiTradeDecision {
        val action = when (json.getString("action")) {
            "BUY_CE", "BUY_CALL" -> SignalAction.BUY_CE
            "BUY_PE", "BUY_PUT" -> SignalAction.BUY_PE
            "WAIT" -> SignalAction.WAIT
            else -> error("Unsupported AI action")
        }
        val regime = runCatching { MarketRegime.valueOf(json.optString("regime", "UNKNOWN")) }
            .getOrDefault(MarketRegime.UNKNOWN)
        return AiTradeDecision(
            schemaVersion = json.optInt("schemaVersion", 1),
            decisionId = json.getString("decisionId"),
            snapshotId = json.getString("snapshotId"),
            decidedAtMillis = json.getLong("decidedAtMillis"),
            validForMillis = json.getLong("validForMillis"),
            action = action,
            confidence = json.getInt("confidence"),
            regime = regime,
            instrumentKey = json.optNullableString("instrumentKey"),
            strike = json.optNullableDouble("strike"),
            optionType = json.optNullableString("optionType"),
            entryMin = json.optNullableDouble("entryMin"),
            entryMax = json.optNullableDouble("entryMax"),
            stopLoss = json.optNullableDouble("stopLoss"),
            target = json.optNullableDouble("target"),
            trigger = json.optJSONObject("trigger")?.let {
                ConditionalTrigger(
                    spotAbove = it.optNullableDouble("spotAbove"),
                    spotBelow = it.optNullableDouble("spotBelow"),
                    minimumVolumeRatio = it.optNullableDouble("minimumVolumeRatio"),
                    maximumSpreadPct = it.optNullableDouble("maximumSpreadPct"),
                )
            },
            maximumSpotMovePct = json.optDouble("maximumSpotMovePct", 0.20),
            reasons = json.optStringList("reasons"),
            riskFlags = json.optStringList("riskFlags"),
            modelVersion = json.getString("modelVersion"),
            promptVersion = json.getString("promptVersion"),
        )
    }
}

private fun AiMarketSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("snapshotId", snapshotId)
    put("generatedAtMillis", generatedAtMillis)
    put("index", index.name)
    put("expiry", expiry)
    put("spot", spot)
    put("nativeAction", nativeAction.name)
    put("nativeConfidence", nativeConfidence)
    put("bars1m", JSONArray(bars1m.map { it.toJson() }))
    put("bars5m", JSONArray(bars5m.map { it.toJson() }))
    put("bars15m", JSONArray(bars15m.map { it.toJson() }))
    put("optionChain", JSONArray(optionChain.map { quote -> JSONObject().apply {
        put("instrumentKey", quote.instrumentKey)
        put("strike", quote.strike)
        put("type", quote.type)
        put("ltp", quote.ltp)
        put("openInterest", quote.openInterest)
        put("changeInOpenInterest", quote.changeInOpenInterest)
        put("delta", quote.delta)
        put("gamma", quote.gamma)
        put("lastTickMillis", quote.lastTickMillis)
    } }))
    put("risk", JSONObject().apply {
        put("capital", risk.capital)
        put("realizedPnl", risk.realizedPnl)
        put("openSide", risk.openSide?.name)
        put("openEntryPrice", risk.openEntryPrice)
        put("dailyTrades", risk.dailyTrades)
        put("dailyLossLocked", risk.dailyLossLocked)
    })
    put("news", JSONArray(news.map { item -> JSONObject().apply {
        put("headline", item.headline)
        put("source", item.source)
        put("publishedAtMillis", item.publishedAtMillis)
        put("sentimentScore", item.sentimentScore)
        put("relevanceScore", item.relevanceScore)
    } }))
}

private fun CompactBar.toJson() = JSONObject().apply {
    put("epochMillis", epochMillis)
    put("open", open)
    put("high", high)
    put("low", low)
    put("close", close)
    put("volume", volume)
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONObject.optStringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
}
