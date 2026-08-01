package com.parmod.ema.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Implements the official Upstox OAuth 2.0 authorization-code exchange.
 * Credentials are supplied at runtime and are never embedded in the APK.
 */
class UpstoxOAuthClient {
    data class TokenResult(
        val accessToken: String,
        val userId: String?,
        val userName: String?,
        val email: String?,
    )

    fun authorizationUrl(clientId: String, redirectUri: String, state: String): String {
        require(clientId.isNotBlank()) { "API key is required" }
        require(redirectUri.isNotBlank()) { "Redirect URI is required" }
        return buildString {
            append("https://api.upstox.com/v2/login/authorization/dialog")
            append("?response_type=code")
            append("&client_id=").append(encode(clientId.trim()))
            append("&redirect_uri=").append(encode(redirectUri.trim()))
            append("&state=").append(encode(state))
        }
    }

    fun exchangeCode(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
    ): TokenResult {
        require(code.isNotBlank()) { "Authorization code is required" }
        require(clientId.isNotBlank()) { "API key is required" }
        require(clientSecret.isNotBlank()) { "API secret is required" }
        require(redirectUri.isNotBlank()) { "Redirect URI is required" }

        val body = listOf(
            "code" to code.trim(),
            "client_id" to clientId.trim(),
            "client_secret" to clientSecret.trim(),
            "redirect_uri" to redirectUri.trim(),
            "grant_type" to "authorization_code",
        ).joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

        val connection = URL("https://api.upstox.com/v2/login/authorization/token")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) error("Upstox token exchange failed ($status): $responseText")

            val json = JSONObject(responseText)
            val token = json.optString("access_token")
            if (token.isBlank()) error("Upstox did not return an access token")
            TokenResult(
                accessToken = token,
                userId = json.optString("user_id").takeIf(String::isNotBlank),
                userName = json.optString("user_name").takeIf(String::isNotBlank),
                email = json.optString("email").takeIf(String::isNotBlank),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
