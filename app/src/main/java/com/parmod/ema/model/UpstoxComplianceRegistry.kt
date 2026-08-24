package com.parmod.ema.model

/**
 * Process-wide order-compliance state. Android storage initializes the configured Algo Name;
 * the order client refreshes registered-static-IP status from Upstox before live orders.
 */
object UpstoxComplianceRegistry {
    @Volatile private var algoName: String = ""
    @Volatile private var registeredPrimaryIp: String = ""
    @Volatile private var registeredSecondaryIp: String = ""
    @Volatile private var staticIpCheckedAt: Long = 0L
    @Volatile private var protectionFault: String = ""

    fun configureAlgoName(value: String) {
        algoName = value.trim()
    }

    fun algoName(): String = algoName
    fun autoLiveAlgoConfigured(): Boolean = algoName.isNotBlank()

    fun updateRegisteredStaticIps(primary: String?, secondary: String?) {
        registeredPrimaryIp = primary.orEmpty().trim()
        registeredSecondaryIp = secondary.orEmpty().trim()
        staticIpCheckedAt = System.currentTimeMillis()
    }

    fun hasRegisteredStaticIp(): Boolean = registeredPrimaryIp.isNotBlank() || registeredSecondaryIp.isNotBlank()
    fun registeredStaticIpSummary(): String = listOf(registeredPrimaryIp, registeredSecondaryIp).filter { it.isNotBlank() }.joinToString(" / ")
    fun staticIpCheckFresh(now: Long = System.currentTimeMillis()): Boolean =
        staticIpCheckedAt > 0L && now - staticIpCheckedAt <= STATIC_IP_CACHE_MS

    fun setProtectionFault(reason: String) {
        protectionFault = reason.trim().take(220)
    }

    fun clearProtectionFault() {
        protectionFault = ""
    }

    fun protectionHealthy(): Boolean = protectionFault.isBlank()
    fun protectionFaultReason(): String = protectionFault

    private const val STATIC_IP_CACHE_MS = 5 * 60_000L
}
