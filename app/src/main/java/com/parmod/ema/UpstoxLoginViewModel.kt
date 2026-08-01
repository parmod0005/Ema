package com.parmod.ema

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parmod.ema.data.UpstoxOAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class UpstoxLoginState(
    val isExchanging: Boolean = false,
    val accessToken: String? = null,
    val message: String = "Enter the API credentials from your Upstox Developer App",
    val stateNonce: String = UUID.randomUUID().toString(),
)

class UpstoxLoginViewModel : ViewModel() {
    private val client = UpstoxOAuthClient()
    private val _state = MutableStateFlow(UpstoxLoginState())
    val state: StateFlow<UpstoxLoginState> = _state.asStateFlow()

    fun authorizationUrl(apiKey: String, redirectUri: String): String =
        client.authorizationUrl(apiKey, redirectUri, _state.value.stateNonce)

    fun exchangeAuthorizationCode(
        authorizationCode: String,
        apiKey: String,
        apiSecret: String,
        redirectUri: String,
        onToken: (String) -> Unit,
    ) {
        if (_state.value.isExchanging) return
        _state.value = _state.value.copy(isExchanging = true, message = "Exchanging single-use authorization code…")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.exchangeCode(authorizationCode, apiKey, apiSecret, redirectUri)
                }
                _state.value = _state.value.copy(
                    isExchanging = false,
                    accessToken = result.accessToken,
                    message = "Authenticated${result.userName?.let { " as $it" } ?: ""}. Access token is held in memory only.",
                )
                onToken(result.accessToken)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    isExchanging = false,
                    message = error.message?.take(240) ?: "Upstox authentication failed",
                    stateNonce = UUID.randomUUID().toString(),
                )
            }
        }
    }

    fun clearToken() {
        _state.value = UpstoxLoginState(message = "Session cleared")
    }
}
