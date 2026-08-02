package com.parmod.ema.ai

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performs a read-only authentication check against OpenAI.
 * It does not submit market data and cannot create or execute a trade.
 */
class OpenAiConnectionTester(
    private val apiKey: String,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 12_000,
) {
    data class Result(
        val success: Boolean,
        val latencyMillis: Long,
        val message: String,
    )

    fun test(): Result {
        if (apiKey.isBlank()) return Result(false, 0L, "OpenAI API key is empty")
        val started = System.currentTimeMillis()
        val connection = URL("https://api.openai.com/v1/models").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val latency = System.currentTimeMillis() - started
            if (code in 200..299) {
                val count = runCatching { JSONObject(body).optJSONArray("data")?.length() ?: 0 }.getOrDefault(0)
                Result(true, latency, "Authentication passed · $count models visible")
            } else {
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { body.take(160) }
                Result(false, latency, "OpenAI HTTP $code · $detail")
            }
        } catch (error: Exception) {
            Result(false, System.currentTimeMillis() - started, error.message ?: "OpenAI connection failed")
        } finally {
            connection.disconnect()
        }
    }
}
