package com.parmod.ema.model

/**
 * Process-lifetime bridge from the broker adapter to the dashboard state boundary.
 *
 * The order adapter can observe an actual broker reduction that the caller cannot safely
 * price. The large trading ViewModel must not guess that missing P&L and must not keep a
 * phantom quantity larger than the broker position. This registry carries only quantity
 * reconciliation metadata; it contains no credentials and does not replace the persistent
 * crash ledger.
 */
internal object LiveBrokerReconciliationRegistry {
    data class Snapshot(
        val instrumentKey: String,
        val localPositionQuantity: Int,
        val brokerRemainingQuantity: Int,
        val unpricedClosedQuantity: Int,
        val updatedAtMillis: Long = System.currentTimeMillis(),
    )

    private val snapshots = linkedMapOf<String, Snapshot>()

    @Synchronized
    fun observe(
        instrumentKey: String,
        localPositionQuantity: Int,
        brokerRemainingQuantity: Int,
        unpricedClosedQuantity: Int,
    ) {
        if (instrumentKey.isBlank() || localPositionQuantity <= 0) return
        snapshots[instrumentKey] = Snapshot(
            instrumentKey = instrumentKey,
            localPositionQuantity = localPositionQuantity,
            brokerRemainingQuantity = brokerRemainingQuantity.coerceIn(0, localPositionQuantity),
            unpricedClosedQuantity = unpricedClosedQuantity.coerceAtLeast(0),
        )
    }

    @Synchronized
    fun snapshot(instrumentKey: String): Snapshot? =
        if (instrumentKey.isBlank()) null else snapshots[instrumentKey]

    @Synchronized
    fun clear(instrumentKey: String) {
        if (instrumentKey.isNotBlank()) snapshots.remove(instrumentKey)
    }
}
