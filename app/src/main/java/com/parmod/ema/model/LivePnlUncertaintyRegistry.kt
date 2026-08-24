package com.parmod.ema.model

/**
 * Process-lifetime marker for a LIVE trade whose realized P&L became uncertain.
 *
 * This is not the crash ledger. TradingRecoveryRegistry persists the final null-P&L state
 * for restart safety. This registry keeps the identity alive while the running process
 * transitions from an open position to a closed trade row, preventing later code from
 * converting an unpriced broker reduction into an apparently precise final P&L.
 */
internal object LivePnlUncertaintyRegistry {
    private val uncertain = linkedSetOf<String>()

    @Synchronized
    fun mark(position: PaperPosition) {
        if (position.executionMode == ExecutionMode.LIVE) uncertain += key(
            mode = position.executionMode,
            brokerEntryOrderId = position.brokerEntryOrderId,
            openedAtMillis = position.openedAtMillis,
        )
    }

    @Synchronized
    fun isMarked(position: PaperPosition): Boolean = key(
        mode = position.executionMode,
        brokerEntryOrderId = position.brokerEntryOrderId,
        openedAtMillis = position.openedAtMillis,
    ) in uncertain

    @Synchronized
    fun isMarked(entry: TradeLogEntry): Boolean = key(
        mode = entry.executionMode,
        brokerEntryOrderId = entry.brokerEntryOrderId,
        openedAtMillis = entry.entryTimeMillis,
    ) in uncertain

    @Synchronized
    fun clear(position: PaperPosition) {
        uncertain -= key(
            mode = position.executionMode,
            brokerEntryOrderId = position.brokerEntryOrderId,
            openedAtMillis = position.openedAtMillis,
        )
    }

    @Synchronized
    fun clear(entry: TradeLogEntry) {
        uncertain -= key(
            mode = entry.executionMode,
            brokerEntryOrderId = entry.brokerEntryOrderId,
            openedAtMillis = entry.entryTimeMillis,
        )
    }

    private fun key(
        mode: ExecutionMode,
        brokerEntryOrderId: String,
        openedAtMillis: Long,
    ): String = if (mode == ExecutionMode.LIVE && brokerEntryOrderId.isNotBlank()) {
        "LIVE:${brokerEntryOrderId.trim()}"
    } else {
        "${mode.name}:$openedAtMillis"
    }
}
