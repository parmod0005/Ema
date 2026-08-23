package com.parmod.ema.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Android Keystore-backed local storage for Upstox credentials and order-app metadata. */
class LocalCredentialVault(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    data class Credentials(
        val upstoxApiKey: String = "",
        val upstoxApiSecret: String = "",
        val upstoxAccessToken: String = "",
        val upstoxRedirectUri: String = "",
        val upstoxAlgoName: String = "",
    )

    fun read(): Credentials = Credentials(
        upstoxApiKey = preferences.getString(KEY_UPSTOX_API_KEY, "").orEmpty(),
        upstoxApiSecret = preferences.getString(KEY_UPSTOX_API_SECRET, "").orEmpty(),
        upstoxAccessToken = preferences.getString(KEY_UPSTOX_ACCESS_TOKEN, "").orEmpty(),
        upstoxRedirectUri = preferences.getString(KEY_UPSTOX_REDIRECT_URI, "").orEmpty(),
        upstoxAlgoName = preferences.getString(KEY_UPSTOX_ALGO_NAME, "").orEmpty(),
    )

    fun save(credentials: Credentials) {
        preferences.edit()
            .putString(KEY_UPSTOX_API_KEY, credentials.upstoxApiKey.trim())
            .putString(KEY_UPSTOX_API_SECRET, credentials.upstoxApiSecret.trim())
            .putString(KEY_UPSTOX_ACCESS_TOKEN, credentials.upstoxAccessToken.trim())
            .putString(KEY_UPSTOX_REDIRECT_URI, credentials.upstoxRedirectUri.trim())
            .putString(KEY_UPSTOX_ALGO_NAME, credentials.upstoxAlgoName.trim())
            .apply()
    }

    fun updateUpstoxAccessToken(token: String) {
        preferences.edit().putString(KEY_UPSTOX_ACCESS_TOKEN, token.trim()).apply()
    }

    fun clearAll() = preferences.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "vardhani_private_credentials"
        private const val KEY_UPSTOX_API_KEY = "upstox_api_key"
        private const val KEY_UPSTOX_API_SECRET = "upstox_api_secret"
        private const val KEY_UPSTOX_ACCESS_TOKEN = "upstox_access_token"
        private const val KEY_UPSTOX_REDIRECT_URI = "upstox_redirect_uri"
        private const val KEY_UPSTOX_ALGO_NAME = "upstox_algo_name"
    }
}
