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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.data.LocalCredentialVault
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
                        continueToApp = { token ->
                            LocalCredentialVault(this).updateUpstoxAccessToken(token)
                            startActivity(Intent(this, VardhaniFullActivity::class.java))
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
    continueToApp: (String) -> Unit,
    copyToken: (String) -> Unit,
    loginVm: UpstoxLoginViewModel = viewModel(),
) {
    val login by loginVm.state.collectAsState()
    val context = LocalContext.current
    val vault = remember(context) { LocalCredentialVault(context) }
    val saved = remember(vault) { vault.read() }
    var apiKey by remember { mutableStateOf(saved.upstoxApiKey) }
    var apiSecret by remember { mutableStateOf(saved.upstoxApiSecret) }
    var redirectUri by remember { mutableStateOf(saved.upstoxRedirectUri) }
    var authCode by remember { mutableStateOf("") }
    var discoveredExpiry by remember { mutableStateOf("") }
    var discoveryMessage by remember { mutableStateOf("") }

    LaunchedEffect(login.accessToken) {
        val token = login.accessToken ?: return@LaunchedEffect
        vault.updateUpstoxAccessToken(token)
        discoveryMessage = "Authenticated · checking NIFTY option discovery…"
        runCatching {
            withContext(Dispatchers.IO) {
                UpstoxOptionDiscoveryClient(token).discover(MarketIndex.NIFTY).nearestExpiry
            }
        }.onSuccess {
            discoveredExpiry = it
            discoveryMessage = "Authenticated · NIFTY nearest expiry $it · full dashboard will discover selected markets"
        }.onFailure {
            discoveryMessage = "Token created · option discovery will retry in the full dashboard: ${it.message.orEmpty()}"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("VARDHANI · Upstox OAuth") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Upstox OAuth", fontWeight = FontWeight.Bold)
                    Text(
                        "API key, secret and redirect URI are loaded from the encrypted VARDHANI credential vault. Your Upstox password/TOTP stay on the hosted Upstox login page.",
                    )
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it.trim() },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API key / Client ID") },
                    )
                    OutlinedTextField(
                        apiSecret,
                        { apiSecret = it.trim() },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API secret / Client secret") },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        redirectUri,
                        { redirectUri = it.trim() },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Registered redirect URI") },
                    )
                    Button(
                        onClick = {
                            val old = vault.read()
                            vault.save(
                                old.copy(
                                    upstoxApiKey = apiKey,
                                    upstoxApiSecret = apiSecret,
                                    upstoxRedirectUri = redirectUri,
                                ),
                            )
                            openBrowser(loginVm.authorizationUrl(apiKey, redirectUri))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKey.isNotBlank() && redirectUri.isNotBlank(),
                    ) { Text("SAVE + OPEN UPSTOX LOGIN") }
                    OutlinedTextField(
                        authCode,
                        { authCode = it.trim() },
                        Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Authorization code returned by Upstox") },
                    )
                    Button(
                        onClick = {
                            val old = vault.read()
                            vault.save(
                                old.copy(
                                    upstoxApiKey = apiKey,
                                    upstoxApiSecret = apiSecret,
                                    upstoxRedirectUri = redirectUri,
                                ),
                            )
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
                        Text("Access token has been encrypted in the local VARDHANI vault. It is never committed to GitHub.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { copyToken(token) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("COPY TOKEN") }
                            Button(
                                onClick = { continueToApp(token) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) { Text("OPEN FULL VARDHANI") }
                        }
                    }
                }
            }
        }
    }
}
