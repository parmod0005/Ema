package com.parmod.ema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.parmod.ema.ai.LocalCredentialVault

/**
 * Always-visible Upstox credential editor for the personal-device build.
 * Values are entered after installation and persisted only through the
 * Android Keystore-backed LocalCredentialVault.
 */
@Composable
fun UpstoxCredentialsPanel(onAccessTokenSaved: (String) -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { LocalCredentialVault(context) }
    val initial = remember(vault) { vault.read() }

    var apiKey by remember { mutableStateOf(initial.upstoxApiKey) }
    var apiSecret by remember { mutableStateOf(initial.upstoxApiSecret) }
    var accessToken by remember { mutableStateOf(initial.upstoxAccessToken) }
    var redirectUri by remember { mutableStateOf(initial.upstoxRedirectUri) }
    var reveal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("UPSTOX CREDENTIALS", fontWeight = FontWeight.Bold)
            Text(
                "Required for live data. API key and secret are stored encrypted on this phone and are not compiled into the APK.",
                style = MaterialTheme.typography.labelSmall,
            )
            CredentialField("Upstox API key", apiKey, reveal) { apiKey = it.trim() }
            CredentialField("Upstox API secret", apiSecret, reveal) { apiSecret = it.trim() }
            CredentialField("Upstox access token", accessToken, reveal) { accessToken = it.trim() }
            OutlinedTextField(
                value = redirectUri,
                onValueChange = { redirectUri = it.trim() },
                label = { Text("Upstox redirect URI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { reveal = !reveal },
                    modifier = Modifier.weight(1f),
                ) { Text(if (reveal) "HIDE" else "REVEAL") }
                Button(
                    onClick = {
                        val old = vault.read()
                        vault.save(
                            old.copy(
                                upstoxApiKey = apiKey,
                                upstoxApiSecret = apiSecret,
                                upstoxAccessToken = accessToken,
                                upstoxRedirectUri = redirectUri,
                            ),
                        )
                        onAccessTokenSaved(accessToken)
                        reveal = false
                        message = "Upstox credentials encrypted and saved"
                    },
                    enabled = accessToken.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("SAVE") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    reveal: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
