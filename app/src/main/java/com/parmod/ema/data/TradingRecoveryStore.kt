package com.parmod.ema.data

import android.content.Context
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.TradeStatus
import com.parmod.ema.model.TradingRecoveryRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private crash ledger for trading state. No Upstox credential or access token is stored here.
 * Writes use SharedPreferences.apply(), so the model-side registry can snapshot without blocking
 * market-data threads on a filesystem fsync.
 */
class TradingRecoveryStore(context: Context) : TradingRecoveryRegistry.Backend {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): TradingRecoveryRegistry.Snapshot {
        val raw = preferences.getString(KEY_SNAPSHOT, null).orEmpty()
        if (raw.isBlank()) return TradingRecoveryRegistry.Snapshot()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("records") ?: JSONArray()
            val records = buildList {
                for (i in 0 until array.length()) {
                    parseRecord(array.optJSONObject(i) ?: continue)?.let(::add)
                }
            }
            TradingRecoveryRegistry.Snapshot(
                records = records,
                savedAtMillis = root.optLong("saved_at", 0L),
            )
        }.getOrElse {
            TradingRecoveryRegistry.Snapshot()
        }
    }

    override fun save(snapshot: TradingRecoveryRegistry.Snapshot) {
        val array = JSONArray()
        snapshot.records.forEach { array.put(toJson(it)) }
        val root = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("saved_at", snapshot.savedAtMillis)
            .put("records", array)
        preferences.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }

    private fun toJson(record: TradingRecoveryRegistry.Record): JSONObject = JSONObject()
        .put("key", record.key)
        .put("index", record.index.name)
        .put("engine", record.engineId.name)
        .put("side", record.side.name)
        .put("strike", record.strike)
        .put("entry_price", record.entryPrice)
        .put("entry_time", record.entryTimeMillis)
        .put("execution", record.executionMode.name)
        .put("status", record.status.name)
        .put("original_qty", record.originalQuantity)
        .put("current_qty", record.currentQuantity)
        .put("lots", record.lots)
        .put("lot_size", record.lotSize)
        .put("instrument", record.instrumentKey)
        .put("current_price", record.currentPrice)
        .put("stop", record.stopPrice)
        .put("target", record.targetPrice)
        .put("highest", record.highestPrice)
        .put("t1", record.target1Hit)
        .put("partial_pnl", record.realizedPartialPnl)
        .put("strategy", record.strategy)
        .put("index_invalidation", record.indexInvalidation)
        .put("broker_entry", record.brokerEntryOrderId)
        .put("broker_exit", record.brokerExitOrderId)
        .put("exit_price", record.exitPrice ?: JSONObject.NULL)
        .put("exit_time", record.exitTimeMillis ?: JSONObject.NULL)
        .put("pnl", record.pnl ?: JSONObject.NULL)
        .put("exit_reason", record.exitReason)
        .put("recovery_resolved", record.recoveryResolved)
        .put("updated_at", record.updatedAtMillis)

    private fun parseRecord(node: JSONObject): TradingRecoveryRegistry.Record? = runCatching {
        TradingRecoveryRegistry.Record(
            key = node.getString("key"),
            index = MarketIndex.valueOf(node.getString("index")),
            engineId = EngineId.valueOf(node.getString("engine")),
            side = PositionSide.valueOf(node.getString("side")),
            strike = node.optDouble("strike", 0.0),
            entryPrice = node.optDouble("entry_price", 0.0),
            entryTimeMillis = node.getLong("entry_time"),
            executionMode = ExecutionMode.valueOf(node.optString("execution", ExecutionMode.PAPER.name)),
            status = TradeStatus.valueOf(node.optString("status", TradeStatus.CLOSED.name)),
            originalQuantity = node.optInt("original_qty", 0),
            currentQuantity = node.optInt("current_qty", 0),
            lots = node.optInt("lots", 0),
            lotSize = node.optInt("lot_size", 0),
            instrumentKey = node.optString("instrument"),
            currentPrice = node.optDouble("current_price", 0.0),
            stopPrice = node.optDouble("stop", 0.0),
            targetPrice = node.optDouble("target", 0.0),
            highestPrice = node.optDouble("highest", 0.0),
            target1Hit = node.optBoolean("t1", false),
            realizedPartialPnl = node.optDouble("partial_pnl", 0.0),
            strategy = node.optString("strategy"),
            indexInvalidation = node.optDouble("index_invalidation", 0.0),
            brokerEntryOrderId = node.optString("broker_entry"),
            brokerExitOrderId = node.optString("broker_exit"),
            exitPrice = node.optNullableDouble("exit_price"),
            exitTimeMillis = node.optNullableLong("exit_time"),
            pnl = node.optNullableDouble("pnl"),
            exitReason = node.optString("exit_reason"),
            recoveryResolved = node.optBoolean("recovery_resolved", false),
            updatedAtMillis = node.optLong("updated_at", node.getLong("entry_time")),
        )
    }.getOrNull()

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    companion object {
        private const val FILE_NAME = "vardhani_trading_recovery"
        private const val KEY_SNAPSHOT = "snapshot_v1"
        private const val SCHEMA_VERSION = 1
    }
}
