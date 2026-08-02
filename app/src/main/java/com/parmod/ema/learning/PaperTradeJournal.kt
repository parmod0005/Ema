package com.parmod.ema.learning

import android.content.Context
import com.parmod.ema.ai.MarketRegime
import com.parmod.ema.model.PositionSide
import org.json.JSONArray
import org.json.JSONObject

/** Local-only persistent journal for completed paper trades. */
class PaperTradeJournal(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun readAll(): List<AdaptivePaperLearningEngine.PaperTradeOutcome> {
        val raw = preferences.getString(KEY_OUTCOMES, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(item.toOutcome())
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(outcome: AdaptivePaperLearningEngine.PaperTradeOutcome) {
        val updated = (readAll() + outcome).takeLast(MAX_OUTCOMES)
        preferences.edit().putString(KEY_OUTCOMES, JSONArray(updated.map { it.toJson() }).toString()).apply()
    }

    @Synchronized
    fun clear() = preferences.edit().remove(KEY_OUTCOMES).apply()

    private fun AdaptivePaperLearningEngine.PaperTradeOutcome.toJson() = JSONObject().apply {
        put("openedAtMillis", openedAtMillis)
        put("closedAtMillis", closedAtMillis)
        put("provider", provider)
        put("modelVersion", modelVersion)
        put("promptVersion", promptVersion)
        put("regime", regime.name)
        put("side", side.name)
        put("confidence", confidence)
        put("entryPrice", entryPrice)
        put("exitPrice", exitPrice)
        put("quantity", quantity)
        put("pnl", pnl)
        put("maximumAdverseExcursionPct", maximumAdverseExcursionPct)
        put("maximumFavourableExcursionPct", maximumFavourableExcursionPct)
        put("exitReason", exitReason)
    }

    private fun JSONObject.toOutcome() = AdaptivePaperLearningEngine.PaperTradeOutcome(
        openedAtMillis = getLong("openedAtMillis"),
        closedAtMillis = getLong("closedAtMillis"),
        provider = optString("provider", "UNKNOWN"),
        modelVersion = optString("modelVersion", "unknown"),
        promptVersion = optString("promptVersion", "unknown"),
        regime = runCatching { MarketRegime.valueOf(optString("regime", "UNKNOWN")) }.getOrDefault(MarketRegime.UNKNOWN),
        side = PositionSide.valueOf(getString("side")),
        confidence = getInt("confidence"),
        entryPrice = getDouble("entryPrice"),
        exitPrice = getDouble("exitPrice"),
        quantity = getInt("quantity"),
        pnl = getDouble("pnl"),
        maximumAdverseExcursionPct = optDouble("maximumAdverseExcursionPct", 0.0),
        maximumFavourableExcursionPct = optDouble("maximumFavourableExcursionPct", 0.0),
        exitReason = optString("exitReason", "UNKNOWN"),
    )

    companion object {
        private const val FILE_NAME = "vardhani_paper_trade_journal"
        private const val KEY_OUTCOMES = "completed_outcomes"
        private const val MAX_OUTCOMES = 2_000
    }
}
