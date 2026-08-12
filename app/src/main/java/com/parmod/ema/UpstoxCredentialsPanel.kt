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
import com.parmod.ema.data.LocalCredentialVault

@Composable
fun UpstoxCredentialsPanel(onAccessTokenSaved: (String) -> Unit) {
    val context = LocalContext.current
    val vault = remember(context) { LocalCredentialVault(context) }
    val initial = remember(vault) { vault.read() }

    var apiKey by remember { mutableStateOf(initial.upstoxApiKey) }
    var apiSecret by remember { mutableStateOf(initial.upstoxApiSecret) }
    var redirectUri by remember { mutableStateOf(initial.upstoxRedirectUri) }
    var expanded by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("UPSTOX APP CREDENTIALS", fontWeight = FontWeight.Bold)
                    Text(
                        if (apiKey.isNotBlank() && apiSecret.isNotBlank()) "API key and secret saved securely" else "Optional API key, secret and redirect URI",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "CLOSE" else "EDIT") }
            }

            if (expanded) {
                Text("The access token is entered only in the connection card above. Values are encrypted with Android Keystore.", style = MaterialTheme.typography.labelSmall)
                CredentialField("Upstox API key", apiKey, reveal) { apiKey = it.trim() }
                CredentialField("Upstox API secret", apiSecret, reveal) { apiSecret = it.trim() }
                OutlinedTextField(
                    value = redirectUri,
                    onValueChange = { redirectUri = it.trim() },
                    label = { Text("Upstox redirect URI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { reveal = !reveal }, modifier = Modifier.weight(1f)) { Text(if (reveal) "HIDE" else "REVEAL") }
                    Button(
                        onClick = {
                            val old = vault.read()
                            vault.save(old.copy(upstoxApiKey = apiKey, upstoxApiSecret = apiSecret, upstoxRedirectUri = redirectUri))
                            onAccessTokenSaved(old.upstoxAccessToken)
                            reveal = false
                            expanded = false
                            message = "Upstox app credentials encrypted and saved"
                        },
                        enabled = apiKey.isNotBlank() || apiSecret.isNotBlank() || redirectUri.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("SAVE") }
                }
            }

            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CredentialField(label: String, value: String, reveal: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
