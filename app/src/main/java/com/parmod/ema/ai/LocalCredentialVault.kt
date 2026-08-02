package com.parmod.ema.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Personal-device credential storage for VARDHANI.
 *
 * Values are entered after installation and encrypted using an Android
 * Keystore-backed master key. Nothing is compiled into the APK or committed
 * to source control. A compromised/rooted device may still expose secrets
 * while the app is using them, so this mode is intentionally personal-only.
 */
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
        val openAiApiKey: String = "",
        val openAiModel: String = "gpt-5",
        val upstoxApiKey: String = "",
        val upstoxApiSecret: String = "",
        val upstoxAccessToken: String = "",
        val upstoxRedirectUri: String = "",
    ) {
        val hasOpenAi: Boolean get() = openAiApiKey.isNotBlank()
        val hasUpstoxLoginCredentials: Boolean
            get() = upstoxApiKey.isNotBlank() && upstoxApiSecret.isNotBlank()
    }

    fun read(): Credentials = Credentials(
        openAiApiKey = preferences.getString(KEY_OPENAI_API_KEY, "").orEmpty(),
        openAiModel = preferences.getString(KEY_OPENAI_MODEL, "gpt-5").orEmpty().ifBlank { "gpt-5" },
        upstoxApiKey = preferences.getString(KEY_UPSTOX_API_KEY, "").orEmpty(),
        upstoxApiSecret = preferences.getString(KEY_UPSTOX_API_SECRET, "").orEmpty(),
        upstoxAccessToken = preferences.getString(KEY_UPSTOX_ACCESS_TOKEN, "").orEmpty(),
        upstoxRedirectUri = preferences.getString(KEY_UPSTOX_REDIRECT_URI, "").orEmpty(),
    )

    fun save(credentials: Credentials) {
        preferences.edit()
            .putString(KEY_OPENAI_API_KEY, credentials.openAiApiKey.trim())
            .putString(KEY_OPENAI_MODEL, credentials.openAiModel.trim().ifBlank { "gpt-5" })
            .putString(KEY_UPSTOX_API_KEY, credentials.upstoxApiKey.trim())
            .putString(KEY_UPSTOX_API_SECRET, credentials.upstoxApiSecret.trim())
            .putString(KEY_UPSTOX_ACCESS_TOKEN, credentials.upstoxAccessToken.trim())
            .putString(KEY_UPSTOX_REDIRECT_URI, credentials.upstoxRedirectUri.trim())
            .apply()
    }

    fun updateUpstoxAccessToken(token: String) {
        preferences.edit().putString(KEY_UPSTOX_ACCESS_TOKEN, token.trim()).apply()
    }

    fun clearOpenAi() {
        preferences.edit().remove(KEY_OPENAI_API_KEY).remove(KEY_OPENAI_MODEL).apply()
    }

    fun clearAll() = preferences.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "vardhani_private_credentials"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_UPSTOX_API_KEY = "upstox_api_key"
        private const val KEY_UPSTOX_API_SECRET = "upstox_api_secret"
        private const val KEY_UPSTOX_ACCESS_TOKEN = "upstox_access_token"
        private const val KEY_UPSTOX_REDIRECT_URI = "upstox_redirect_uri"
    }
}
