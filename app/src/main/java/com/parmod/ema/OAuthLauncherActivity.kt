package com.parmod.ema

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.model.MarketIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OAuthLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    OAuthScreen(
                        openBrowser = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                        continueToApp = { token, expiry ->
                            getSharedPreferences("ema_secure_session", MODE_PRIVATE)
                                .edit()
                                .putString("access_token", token)
                                .putString("nearest_expiry", expiry)
                                .apply()
                            startActivity(Intent(this, MainActivity::class.java).apply {
                                putExtra("access_token", token)
                                putExtra("nearest_expiry", expiry)
                            })
                            finish()
                        },
                        copyToken = { token ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Upstox access token", token))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OAuthScreen(
    openBrowser: (String) -> Unit,
    continueToApp: (String, String) -> Unit,
    copyToken: (String) -> Unit,
    loginVm: UpstoxLoginViewModel = viewModel(),
) {
    val login by loginVm.state.collectAsState()
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var redirectUri by remember { mutableStateOf("") }
    var authCode by remember { mutableStateOf("") }
    var discoveredExpiry by remember { mutableStateOf("") }
    var discoveryMessage by remember { mutableStateOf("") }

    LaunchedEffect(login.accessToken) {
        val token = login.accessToken ?: return@LaunchedEffect
        discoveryMessage = "Detecting nearest NIFTY expiry…"
        runCatching {
            withContext(Dispatchers.IO) {
                UpstoxOptionDiscoveryClient(token).discover(MarketIndex.NIFTY).nearestExpiry
            }
        }.onSuccess {
            discoveredExpiry = it
            discoveryMessage = "Nearest expiry: $it"
        }.onFailure {
            discoveryMessage = it.message ?: "Expiry discovery failed"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("EMA · Upstox Login") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Real Upstox OAuth", fontWeight = FontWeight.Bold)
                    Text("Use the API key, API secret and exact redirect URI from your Upstox Developer App. Your Upstox password and TOTP are entered only on the hosted Upstox page.")
                    OutlinedTextField(apiKey, { apiKey = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key / Client ID") })
                    OutlinedTextField(apiSecret, { apiSecret = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API secret / Client secret") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(redirectUri, { redirectUri = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Registered redirect URI") })
                    Button(
                        onClick = { openBrowser(loginVm.authorizationUrl(apiKey, redirectUri)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKey.isNotBlank() && redirectUri.isNotBlank(),
                    ) { Text("OPEN UPSTOX LOGIN") }
                    OutlinedTextField(authCode, { authCode = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Authorization code returned by Upstox") })
                    Button(
                        onClick = {
                            loginVm.exchangeAuthorizationCode(authCode, apiKey, apiSecret, redirectUri) { }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !login.isExchanging && authCode.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank() && redirectUri.isNotBlank(),
                    ) { Text(if (login.isExchanging) "AUTHENTICATING…" else "GENERATE ACCESS TOKEN") }
                    Text(login.message, style = MaterialTheme.typography.bodySmall)
                    if (discoveryMessage.isNotBlank()) Text(discoveryMessage, style = MaterialTheme.typography.bodySmall)
                }
            }

            login.accessToken?.let { token ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Authenticated", fontWeight = FontWeight.Bold)
                        Text("Token is held in app memory and private app storage; it is never committed to GitHub.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copyToken(token) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("COPY TOKEN") }
                            Button(
                                onClick = { continueToApp(token, discoveredExpiry) },
                                modifier = Modifier.weight(1f),
                                enabled = discoveredExpiry.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("START LIVE APP") }
                        }
                    }
                }
            }
        }
    }
}
