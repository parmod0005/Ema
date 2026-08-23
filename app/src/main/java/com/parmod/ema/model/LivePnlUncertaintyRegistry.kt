package com.parmod.ema.model

/**
 * Process-lifetime marker for an open LIVE position whose realized P&L became uncertain.
 *
 * This is not the crash ledger. If the process dies while such a position is still open,
 * TradingRecoveryRegistry's unresolved-LIVE recovery gate remains authoritative. This
 * marker only prevents the running process from later converting an unpriced partial exit
 * into an apparently precise final P&L.
 */
internal object LivePnlUncertaintyRegistry {
    private val uncertain = linkedSetOf<String>()

    @Synchronized
    fun mark(position: PaperPosition) {
        if (position.executionMode == ExecutionMode.LIVE) uncertain += key(position)
    }

    @Synchronized
    fun isMarked(position: PaperPosition): Boolean = key(position) in uncertain

    @Synchronized
    fun clear(position: PaperPosition) {
        uncertain -= key(position)
    }

    private fun key(position: PaperPosition): String =
        if (position.executionMode == ExecutionMode.LIVE && position.brokerEntryOrderId.isNotBlank()) {
            "LIVE:${position.brokerEntryOrderId.trim()}"
        } else {
            "${position.executionMode.name}:${position.openedAtMillis}"
        }
}
