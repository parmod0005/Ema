package com.parmod.ema.data

import com.parmod.ema.model.UpstoxComplianceRegistry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Current Upstox user-level static-IP registration preflight for live order APIs. */
class UpstoxComplianceClient(private val accessToken: String) {
    data class StaticIpStatus(
        val primaryIp: String,
        val secondaryIp: String,
    ) {
        val configured: Boolean get() = primaryIp.isNotBlank() || secondaryIp.isNotBlank()
    }

    fun verifyRegisteredStaticIp(force: Boolean = false): StaticIpStatus {
        require(accessToken.isNotBlank()) { "Upstox access token is required" }
        if (!force && UpstoxComplianceRegistry.staticIpCheckFresh() && UpstoxComplianceRegistry.hasRegisteredStaticIp()) {
            val cached = UpstoxComplianceRegistry.registeredStaticIpSummary().split(" / ")
            return StaticIpStatus(cached.getOrNull(0).orEmpty(), cached.getOrNull(1).orEmpty())
        }
        val connection = URL(STATIC_IP_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Upstox static-IP preflight HTTP $code: ${body.take(220)}")
            val json = JSONObject(body)
            if (!json.optString("status").equals("success", ignoreCase = true)) {
                error("Upstox static-IP preflight did not return success")
            }
            val data = json.getJSONObject("data")
            val status = StaticIpStatus(
                primaryIp = data.optString("primary_ip").trim(),
                secondaryIp = data.optString("secondary_ip").trim(),
            )
            UpstoxComplianceRegistry.updateRegisteredStaticIps(status.primaryIp, status.secondaryIp)
            if (!status.configured) error("No registered Upstox static IP is configured for live API orders")
            status
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val STATIC_IP_URL = "https://api.upstox.com/v2/user/ip"
    }
}
