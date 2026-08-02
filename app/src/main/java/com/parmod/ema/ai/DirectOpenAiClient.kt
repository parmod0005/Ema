package com.parmod.ema.ai

import com.parmod.ema.model.SignalAction
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Personal-device OpenAI client. The API key is supplied at runtime from the
 * Keystore-backed vault and is never built into the APK.
 *
 * This client contains no broker order methods. It only returns a structured
 * market-analysis decision which is still subject to VARDHANI's local safety
 * router and Shadow/Paper restrictions.
 */
class DirectOpenAiClient(
    private val apiKey: String,
    private val model: String = "gpt-5",
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 15_000,
) {
    data class Response(val decision: AiTradeDecision, val latencyMillis: Long)

    fun analyze(snapshot: AiMarketSnapshot): Response {
        require(apiKey.isNotBlank()) { "OpenAI API key is required" }
        val started = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("model", model.ifBlank { "gpt-5" })
            put("store", false)
            put("instructions", SYSTEM_PROMPT)
            put("input", snapshot.toOpenAiJson().toString())
            put("text", JSONObject().put("format", decisionFormat()))
        }
        val json = request(payload.toString())
        val outputText = json.optString("output_text").takeIf { it.isNotBlank() }
            ?: extractOutputText(json)
            ?: error("OpenAI response did not contain structured output")
        val decision = parseDecision(JSONObject(outputText))
        require(decision.snapshotId == snapshot.snapshotId) { "OpenAI response snapshot mismatch" }
        return Response(decision, System.currentTimeMillis() - started)
    }

    private fun request(body: String): JSONObject {
        val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("OpenAI HTTP $code: ${responseBody.take(240)}")
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
            instrumentKey = json.nullableString("instrumentKey"),
            strike = json.nullableDouble("strike"),
            optionType = json.nullableString("optionType"),
            entryMin = json.nullableDouble("entryMin"),
            entryMax = json.nullableDouble("entryMax"),
            stopLoss = json.nullableDouble("stopLoss"),
            target = json.nullableDouble("target"),
            trigger = json.optJSONObject("trigger")?.let {
                ConditionalTrigger(
                    spotAbove = it.nullableDouble("spotAbove"),
                    spotBelow = it.nullableDouble("spotBelow"),
                    minimumVolumeRatio = it.nullableDouble("minimumVolumeRatio"),
                    maximumSpreadPct = it.nullableDouble("maximumSpreadPct"),
                )
            },
            maximumSpotMovePct = json.optDouble("maximumSpotMovePct", 0.20),
            reasons = json.stringList("reasons"),
            riskFlags = json.stringList("riskFlags"),
            modelVersion = json.getString("modelVersion"),
            promptVersion = json.getString("promptVersion"),
        )
    }

    private fun extractOutputText(json: JSONObject): String? {
        val output = json.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "output_text") {
                    item.optString("text").takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return null
    }

    companion object {
        private const val SYSTEM_PROMPT = """You are the VARDHANI market-analysis brain for Indian index options. Analyze only the supplied snapshot. Return BUY_CE, BUY_PE, or WAIT. Prioritize capital preservation, data freshness, liquidity, multi-timeframe structure, volume, option-chain positioning, Greeks, expiry risk, and event risk. Never invent missing data. When evidence conflicts, liquidity is poor, risk is high, or confidence is below 80, return WAIT. Do not claim certainty or guaranteed profit. The Android app independently enforces risk limits and execution safety."""

        private fun decisionFormat() = JSONObject().apply {
            put("type", "json_schema")
            put("name", "vardhani_trade_decision")
            put("strict", true)
            put("schema", JSONObject(DECISION_SCHEMA))
        }

        private val DECISION_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("schemaVersion", "decisionId", "snapshotId", "decidedAtMillis", "validForMillis", "action", "confidence", "regime", "instrumentKey", "strike", "optionType", "entryMin", "entryMax", "stopLoss", "target", "trigger", "maximumSpotMovePct", "reasons", "riskFlags", "modelVersion", "promptVersion"),
            "properties" to mapOf(
                "schemaVersion" to mapOf("type" to "integer", "const" to 1),
                "decisionId" to mapOf("type" to "string"),
                "snapshotId" to mapOf("type" to "string"),
                "decidedAtMillis" to mapOf("type" to "integer"),
                "validForMillis" to mapOf("type" to "integer", "minimum" to 1000, "maximum" to 300000),
                "action" to mapOf("type" to "string", "enum" to listOf("BUY_CE", "BUY_PE", "WAIT")),
                "confidence" to mapOf("type" to "integer", "minimum" to 0, "maximum" to 100),
                "regime" to mapOf("type" to "string", "enum" to MarketRegime.entries.map { it.name }),
                "instrumentKey" to mapOf("type" to listOf("string", "null")),
                "strike" to mapOf("type" to listOf("number", "null")),
                "optionType" to mapOf("type" to listOf("string", "null"), "enum" to listOf("CE", "PE", null)),
                "entryMin" to mapOf("type" to listOf("number", "null")),
                "entryMax" to mapOf("type" to listOf("number", "null")),
                "stopLoss" to mapOf("type" to listOf("number", "null")),
                "target" to mapOf("type" to listOf("number", "null")),
                "trigger" to mapOf(
                    "type" to listOf("object", "null"),
                    "additionalProperties" to false,
                    "required" to listOf("spotAbove", "spotBelow", "minimumVolumeRatio", "maximumSpreadPct"),
                    "properties" to mapOf(
                        "spotAbove" to mapOf("type" to listOf("number", "null")),
                        "spotBelow" to mapOf("type" to listOf("number", "null")),
                        "minimumVolumeRatio" to mapOf("type" to listOf("number", "null")),
                        "maximumSpreadPct" to mapOf("type" to listOf("number", "null")),
                    ),
                ),
                "maximumSpotMovePct" to mapOf("type" to "number", "minimum" to 0.01, "maximum" to 2.0),
                "reasons" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 8),
                "riskFlags" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "maxItems" to 8),
                "modelVersion" to mapOf("type" to "string"),
                "promptVersion" to mapOf("type" to "string"),
            ),
        )
    }
}

private fun AiMarketSnapshot.toOpenAiJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("snapshotId", snapshotId)
    put("generatedAtMillis", generatedAtMillis)
    put("index", index.name)
    put("expiry", expiry)
    put("spot", spot)
    put("nativeAction", nativeAction.name)
    put("nativeConfidence", nativeConfidence)
    fun bars(items: List<CompactBar>) = JSONArray(items.map { bar -> JSONObject().apply {
        put("epochMillis", bar.epochMillis); put("open", bar.open); put("high", bar.high)
        put("low", bar.low); put("close", bar.close); put("volume", bar.volume)
    } })
    put("bars1m", bars(bars1m)); put("bars5m", bars(bars5m)); put("bars15m", bars(bars15m))
    put("optionChain", JSONArray(optionChain.map { quote -> JSONObject().apply {
        put("instrumentKey", quote.instrumentKey); put("strike", quote.strike); put("type", quote.type)
        put("ltp", quote.ltp); put("openInterest", quote.openInterest); put("changeInOpenInterest", quote.changeInOpenInterest)
        put("delta", quote.delta); put("gamma", quote.gamma); put("lastTickMillis", quote.lastTickMillis)
    } }))
    put("risk", JSONObject().apply {
        put("capital", risk.capital); put("realizedPnl", risk.realizedPnl); put("openSide", risk.openSide?.name)
        put("openEntryPrice", risk.openEntryPrice); put("dailyTrades", risk.dailyTrades); put("dailyLossLocked", risk.dailyLossLocked)
    })
    put("news", JSONArray(news.map { item -> JSONObject().apply {
        put("headline", item.headline); put("source", item.source); put("publishedAtMillis", item.publishedAtMillis)
        put("sentimentScore", item.sentimentScore); put("relevanceScore", item.relevanceScore)
    } }))
}

private fun JSONObject.nullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.nullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
private fun JSONObject.stringList(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
}
