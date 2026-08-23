package com.parmod.ema.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Process-independent trading ledger bridge.
 *
 * The model layer knows nothing about Android storage. [Backend] is installed by the
 * Application and persists records. Trade/position model instances report themselves here,
 * which means the existing execution runtime gains crash-safe bookkeeping without coupling
 * signal engines to Android APIs.
 */
object TradingRecoveryRegistry {
    data class Record(
        val key: String,
        val index: MarketIndex,
        val engineId: EngineId,
        val side: PositionSide,
        val strike: Double,
        val entryPrice: Double,
        val entryTimeMillis: Long,
        val executionMode: ExecutionMode,
        val status: TradeStatus,
        val originalQuantity: Int,
        val currentQuantity: Int,
        val lots: Int,
        val lotSize: Int,
        val instrumentKey: String,
        val currentPrice: Double,
        val stopPrice: Double,
        val targetPrice: Double,
        val highestPrice: Double,
        val target1Hit: Boolean,
        val realizedPartialPnl: Double,
        val strategy: String,
        val indexInvalidation: Double,
        val brokerEntryOrderId: String,
        val brokerExitOrderId: String,
        val exitPrice: Double?,
        val exitTimeMillis: Long?,
        val pnl: Double?,
        val exitReason: String,
        val recoveryResolved: Boolean = false,
        val updatedAtMillis: Long = System.currentTimeMillis(),
    )

    data class PositionFragment(
        val identity: String,
        val side: PositionSide,
        val strike: Double,
        val originalQuantity: Int,
        val currentQuantity: Int,
        val lotSize: Int,
        val lots: Int,
        val entryPrice: Double,
        val currentPrice: Double,
        val highestPrice: Double,
        val stopPrice: Double,
        val targetPrice: Double,
        val openedAtMillis: Long,
        val strategy: String,
        val indexInvalidation: Double,
        val target1Hit: Boolean,
        val realizedPartialPnl: Double,
        val instrumentKey: String,
        val executionMode: ExecutionMode,
        val brokerEntryOrderId: String,
    )

    data class Snapshot(
        val records: List<Record> = emptyList(),
        val savedAtMillis: Long = 0L,
    )

    interface Backend {
        fun load(): Snapshot
        fun save(snapshot: Snapshot)
    }

    private val zone = ZoneId.of("Asia/Kolkata")
    private var backend: Backend? = null
    private val records = linkedMapOf<String, Record>()
    private val fragments = linkedMapOf<String, PositionFragment>()
    private var startupRecords: List<Record> = emptyList()
    private var initialized = false
    private var lastPersistAtMillis = 0L

    @Synchronized
    fun initialize(storage: Backend) {
        if (initialized) return
        backend = storage
        val loaded = runCatching { storage.load() }.getOrElse { Snapshot() }
        val cutoff = LocalDate.now(zone).minusDays(7)
        loaded.records
            .filter { recordDate(it.entryTimeMillis) >= cutoff }
            .forEach { records[it.key] = it }
        startupRecords = records.values.toList()
        initialized = true
        persistLocked(force = true)
    }

    @Synchronized
    fun observePosition(position: PaperPosition) {
        if (!initialized) return
        val identity = identity(position.executionMode, position.brokerEntryOrderId, position.openedAtMillis)
        val fragment = PositionFragment(
            identity = identity,
            side = position.side,
            strike = position.strike,
            originalQuantity = position.initialQuantity,
            currentQuantity = position.quantity,
            lotSize = position.lotSize,
            lots = position.lots,
            entryPrice = position.entryPrice,
            currentPrice = position.currentPrice,
            highestPrice = position.highestPrice,
            stopPrice = position.stopPrice,
            targetPrice = position.targetPrice,
            openedAtMillis = position.openedAtMillis,
            strategy = position.strategy,
            indexInvalidation = position.indexInvalidation,
            target1Hit = position.target1Hit,
            realizedPartialPnl = position.realizedPartialPnl,
            instrumentKey = position.instrumentKey,
            executionMode = position.executionMode,
            brokerEntryOrderId = position.brokerEntryOrderId,
        )
        fragments[identity] = fragment
        val matching = records.values.firstOrNull { recordIdentity(it) == identity && it.status == TradeStatus.OPEN }
        if (matching != null) {
            val safetyCriticalChange =
                matching.currentQuantity != fragment.currentQuantity ||
                    matching.target1Hit != fragment.target1Hit ||
                    matching.lots != fragment.lots ||
                    matching.lotSize != fragment.lotSize ||
                    matching.instrumentKey != fragment.instrumentKey ||
                    matching.realizedPartialPnl != fragment.realizedPartialPnl
            records[matching.key] = merge(matching, fragment)
            // Ordinary LTP/high/stop copies can occur many times per second and are
            // throttled. Quantity, T1, contract and realized-partial changes must become
            // durable immediately so a crash cannot resurrect already-exited exposure.
            persistLocked(force = safetyCriticalChange)
        }
    }

    @Synchronized
    fun observeTrade(entry: TradeLogEntry) {
        if (!initialized) return
        val key = tradeKey(entry)
        val identity = identity(entry.executionMode, entry.brokerEntryOrderId, entry.entryTimeMillis)
        val previous = records[key]
        var record = Record(
            key = key,
            index = entry.index,
            engineId = entry.engineId,
            side = entry.side,
            strike = entry.strike,
            entryPrice = entry.entryPrice,
            entryTimeMillis = entry.entryTimeMillis,
            executionMode = entry.executionMode,
            status = entry.status,
            originalQuantity = entry.quantity,
            currentQuantity = if (entry.status == TradeStatus.OPEN) entry.quantity else 0,
            lots = entry.lots,
            lotSize = previous?.lotSize ?: 0,
            instrumentKey = previous?.instrumentKey.orEmpty(),
            currentPrice = previous?.currentPrice ?: entry.entryPrice,
            stopPrice = previous?.stopPrice ?: 0.0,
            targetPrice = previous?.targetPrice ?: 0.0,
            highestPrice = previous?.highestPrice ?: entry.entryPrice,
            target1Hit = previous?.target1Hit ?: false,
            realizedPartialPnl = previous?.realizedPartialPnl ?: 0.0,
            strategy = previous?.strategy ?: entry.setup,
            indexInvalidation = previous?.indexInvalidation ?: 0.0,
            brokerEntryOrderId = entry.brokerEntryOrderId,
            brokerExitOrderId = entry.brokerExitOrderId,
            exitPrice = entry.exitPrice,
            exitTimeMillis = entry.exitTimeMillis,
            pnl = entry.pnl,
            exitReason = entry.exitReason,
            recoveryResolved = previous?.recoveryResolved ?: false,
        )
        fragments[identity]?.let { record = merge(record, it) }
        if (entry.status == TradeStatus.CLOSED) {
            record = record.copy(
                currentQuantity = 0,
                brokerExitOrderId = entry.brokerExitOrderId,
                exitPrice = entry.exitPrice,
                exitTimeMillis = entry.exitTimeMillis,
                pnl = entry.pnl,
                exitReason = entry.exitReason,
                recoveryResolved = true,
                updatedAtMillis = System.currentTimeMillis(),
            )
            fragments.remove(identity)
        }
        records[key] = record
        pruneLocked()
        // Entries and terminal trade-log changes are safety-significant.
        persistLocked(force = true)
    }

    @Synchronized
    fun startupOpenLivePositions(): List<Record> = startupRecords
        .filter { it.executionMode == ExecutionMode.LIVE && it.status == TradeStatus.OPEN && !it.recoveryResolved && it.currentQuantity > 0 }
        .sortedBy { it.entryTimeMillis }

    /** Persisted trades that existed before this process started, grouped by market. */
    @Synchronized
    fun startupTodayTradeCounts(): Map<MarketIndex, Int> {
        val today = LocalDate.now(zone)
        return MarketIndex.entries.associateWith { index ->
            startupRecords.count { it.index == index && recordDate(it.entryTimeMillis) == today }
        }
    }

    /** Persisted closed P&L that existed before this process started, grouped by market. */
    @Synchronized
    fun startupTodayRealizedPnl(): Map<MarketIndex, Double> {
        val today = LocalDate.now(zone)
        return MarketIndex.entries.associateWith { index ->
            startupRecords.asSequence()
                .filter { it.index == index && it.status == TradeStatus.CLOSED && recordDate(it.entryTimeMillis) == today }
                .sumOf { it.pnl ?: 0.0 }
        }
    }

    /**
     * Conservative restart protection for the existing LiveExecutionGuard, which receives
     * the current market's in-memory count but not the market id. This only consults the
     * baseline loaded at process start, so normal current-process NIFTY/SENSEX limits remain
     * independent. After a crash, either market may be blocked slightly early, never late.
     */
    @Synchronized
    fun restartBaselineTradeCount(): Int = startupTodayTradeCounts().values.maxOrNull() ?: 0

    @Synchronized
    fun restartBaselineLossLock(limitInr: Double): Boolean =
        startupTodayRealizedPnl().values.any { it <= -limitInr }

    @Synchronized
    fun hasUnresolvedStartupLivePosition(): Boolean = startupOpenLivePositions().isNotEmpty()

    @Synchronized
    fun markRecoveredResolved(key: String) {
        val current = records[key] ?: return
        records[key] = current.copy(recoveryResolved = true, updatedAtMillis = System.currentTimeMillis())
        startupRecords = startupRecords.map {
            if (it.key == key) it.copy(recoveryResolved = true, updatedAtMillis = System.currentTimeMillis()) else it
        }
        persistLocked(force = true)
    }

    @Synchronized
    fun clearResolvedHistoryBefore(date: LocalDate) {
        records.entries.removeAll { (_, record) -> record.recoveryResolved && recordDate(record.entryTimeMillis) < date }
        persistLocked(force = true)
    }

    private fun merge(base: Record, fragment: PositionFragment): Record = base.copy(
        originalQuantity = fragment.originalQuantity,
        currentQuantity = if (base.status == TradeStatus.OPEN) fragment.currentQuantity else 0,
        lots = fragment.lots,
        lotSize = fragment.lotSize,
        instrumentKey = fragment.instrumentKey,
        currentPrice = fragment.currentPrice,
        stopPrice = fragment.stopPrice,
        targetPrice = fragment.targetPrice,
        highestPrice = fragment.highestPrice,
        target1Hit = fragment.target1Hit,
        realizedPartialPnl = fragment.realizedPartialPnl,
        strategy = fragment.strategy,
        indexInvalidation = fragment.indexInvalidation,
        updatedAtMillis = System.currentTimeMillis(),
    )

    private fun tradeKey(entry: TradeLogEntry): String =
        "${entry.index.name}:${entry.engineId.name}:${entry.entryTimeMillis}"

    private fun recordIdentity(record: Record): String =
        identity(record.executionMode, record.brokerEntryOrderId, record.entryTimeMillis)

    private fun identity(mode: ExecutionMode, brokerEntryOrderId: String, openedAtMillis: Long): String =
        if (mode == ExecutionMode.LIVE && brokerEntryOrderId.isNotBlank()) {
            "LIVE:${brokerEntryOrderId.trim()}"
        } else {
            "${mode.name}:$openedAtMillis"
        }

    private fun recordDate(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    private fun pruneLocked() {
        val cutoff = LocalDate.now(zone).minusDays(7)
        records.entries.removeAll { (_, value) -> recordDate(value.entryTimeMillis) < cutoff }
        while (records.size > MAX_RECORDS) records.remove(records.keys.first())
    }

    private fun persistLocked(force: Boolean) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAtMillis < POSITION_SNAPSHOT_INTERVAL_MS) return
        lastPersistAtMillis = now
        runCatching {
            backend?.save(Snapshot(records.values.toList(), now))
        }
    }

    private const val MAX_RECORDS = 2_000
    private const val POSITION_SNAPSHOT_INTERVAL_MS = 1_000L
}
