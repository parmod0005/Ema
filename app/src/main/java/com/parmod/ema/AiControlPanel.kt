package com.parmod.ema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import com.parmod.ema.ai.AiConnectionMode
import com.parmod.ema.ai.AiRunMode
import com.parmod.ema.ai.LocalCredentialVault
import com.parmod.ema.ai.SignalEngineMode
import com.parmod.ema.model.DashboardState
import com.parmod.ema.model.SignalAction

@Composable
fun AiControlPanel(state: DashboardState, vm: TradingViewModel) {
    val context = LocalContext.current
    val vault = remember(context) { LocalCredentialVault(context) }
    val initialCredentials = remember(vault) { vault.read() }
    val health = state.aiBridgeHealth
    val decision = state.aiDecision

    var revealSecrets by remember { mutableStateOf(false) }
    var bridgeUrl by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }
    var openAiKey by remember { mutableStateOf(initialCredentials.openAiApiKey) }
    var openAiModel by remember { mutableStateOf(initialCredentials.openAiModel) }
    var upstoxApiKey by remember { mutableStateOf(initialCredentials.upstoxApiKey) }
    var upstoxApiSecret by remember { mutableStateOf(initialCredentials.upstoxApiSecret) }
    var upstoxAccessToken by remember { mutableStateOf(initialCredentials.upstoxAccessToken) }
    var upstoxRedirectUri by remember { mutableStateOf(initialCredentials.upstoxRedirectUri) }
    var vaultMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (initialCredentials.hasOpenAi) {
            vm.configureDirectOpenAi(initialCredentials.openAiApiKey, initialCredentials.openAiModel)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SIGNAL BRAIN", fontWeight = FontWeight.Bold)
                Text(
                    if (health.reachable) "● AI ONLINE" else if (health.configured) "● AI READY" else "● NOT CONFIGURED",
                    color = if (health.reachable || health.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text("AI CONNECTION", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AiChoice(
                    "DIRECT OPENAI",
                    state.aiConnectionMode == AiConnectionMode.DIRECT_OPENAI,
                    Modifier.weight(1f),
                ) { vm.setAiConnectionMode(AiConnectionMode.DIRECT_OPENAI) }
                AiChoice(
                    "BRIDGE SERVER",
                    state.aiConnectionMode == AiConnectionMode.BRIDGE_SERVER,
                    Modifier.weight(1f),
                ) { vm.setAiConnectionMode(AiConnectionMode.BRIDGE_SERVER) }
            }

            if (state.aiConnectionMode == AiConnectionMode.DIRECT_OPENAI) {
                Text("PRIVATE DEVICE CREDENTIALS VAULT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                SecretField("OpenAI API key", openAiKey, revealSecrets) { openAiKey = it.trim() }
                OutlinedTextField(
                    value = openAiModel,
                    onValueChange = { openAiModel = it.trim() },
                    label = { Text("OpenAI model") },
                    placeholder = { Text("gpt-5") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecretField("Upstox API key", upstoxApiKey, revealSecrets) { upstoxApiKey = it.trim() }
                SecretField("Upstox API secret", upstoxApiSecret, revealSecrets) { upstoxApiSecret = it.trim() }
                SecretField("Upstox access token", upstoxAccessToken, revealSecrets) { upstoxAccessToken = it.trim() }
                OutlinedTextField(
                    value = upstoxRedirectUri,
                    onValueChange = { upstoxRedirectUri = it.trim() },
                    label = { Text("Upstox redirect URI") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { revealSecrets = !revealSecrets },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (revealSecrets) "HIDE CREDENTIALS" else "REVEAL CREDENTIALS") }

                Button(
                    onClick = {
                        val credentials = LocalCredentialVault.Credentials(
                            openAiApiKey = openAiKey,
                            openAiModel = openAiModel,
                            upstoxApiKey = upstoxApiKey,
                            upstoxApiSecret = upstoxApiSecret,
                            upstoxAccessToken = upstoxAccessToken,
                            upstoxRedirectUri = upstoxRedirectUri,
                        )
                        vault.save(credentials)
                        vm.configureDirectOpenAi(credentials.openAiApiKey, credentials.openAiModel)
                        revealSecrets = false
                        vaultMessage = "Credentials encrypted with Android Keystore"
                    },
                    enabled = openAiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("SAVE ENCRYPTED CREDENTIALS") }

                OutlinedButton(
                    onClick = {
                        vault.clearAll()
                        openAiKey = ""
                        openAiModel = "gpt-5"
                        upstoxApiKey = ""
                        upstoxApiSecret = ""
                        upstoxAccessToken = ""
                        upstoxRedirectUri = ""
                        vm.configureDirectOpenAi("", "gpt-5")
                        revealSecrets = false
                        vaultMessage = "All locally stored credentials deleted"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("DELETE ALL CREDENTIALS") }

                if (vaultMessage.isNotBlank()) {
                    Text(vaultMessage, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "Personal-device mode: secrets are encrypted at rest but may still be exposed on a rooted or compromised phone.",
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                if (!health.configured) {
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = { bridgeUrl = it.trim() },
                        label = { Text("AI Bridge HTTPS URL") },
                        placeholder = { Text("https://your-vardhani-bridge.example") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField("Bridge device token", deviceToken, revealSecrets) { deviceToken = it.trim() }
                    Button(
                        onClick = { vm.configureAiBridge(bridgeUrl, deviceToken) },
                        enabled = bridgeUrl.startsWith("https://") && deviceToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("CONNECT AI BRIDGE") }
                } else {
                    OutlinedButton(
                        onClick = {
                            vm.configureAiBridge("", "")
                            bridgeUrl = ""
                            deviceToken = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("DISCONNECT AI BRIDGE") }
                }
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AiChoice("NATIVE", state.signalEngineMode == SignalEngineMode.NATIVE, Modifier.weight(1f)) { vm.setSignalEngineMode(SignalEngineMode.NATIVE) }
                AiChoice("AI BRAIN", state.signalEngineMode == SignalEngineMode.AI_BRAIN, Modifier.weight(1f)) { vm.setSignalEngineMode(SignalEngineMode.AI_BRAIN) }
                AiChoice("HYBRID", state.signalEngineMode == SignalEngineMode.HYBRID, Modifier.weight(1f)) { vm.setSignalEngineMode(SignalEngineMode.HYBRID) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AiChoice("SHADOW", state.aiRunMode == AiRunMode.SHADOW, Modifier.weight(1f)) { vm.setAiRunMode(AiRunMode.SHADOW) }
                AiChoice("PAPER", state.aiRunMode == AiRunMode.PAPER, Modifier.weight(1f)) { vm.setAiRunMode(AiRunMode.PAPER) }
            }

            HorizontalDivider()
            val latency = health.lastLatencyMillis?.let { "${it} ms" } ?: "—"
            val provider = if (state.aiConnectionMode == AiConnectionMode.DIRECT_OPENAI) "Direct OpenAI (${state.directOpenAiModel})" else "Bridge Server"
            Text("Provider: $provider", style = MaterialTheme.typography.labelSmall)
            Text("Status: ${health.message} · latency $latency", style = MaterialTheme.typography.labelSmall)

            if (decision == null) {
                Text("AI decision: waiting for a valid market snapshot", style = MaterialTheme.typography.bodySmall)
            } else {
                val action = when (decision.action) {
                    SignalAction.BUY_CE -> "BUY CALL"
                    SignalAction.BUY_PE -> "BUY PUT"
                    SignalAction.WAIT -> "WAIT"
                }
                Text("AI: $action · ${decision.confidence}/100 · ${decision.regime.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Model ${decision.modelVersion} · prompt ${decision.promptVersion}", style = MaterialTheme.typography.labelSmall)
                decision.reasons.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2) }
            }
            Text("Final route: ${state.aiFinalReason}", style = MaterialTheme.typography.labelSmall)
            if (state.aiRunMode == AiRunMode.SHADOW) {
                Text("Shadow mode records AI analysis but cannot open a trade.", style = MaterialTheme.typography.labelSmall)
            }
            Text("Live broker order placement remains disabled.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, reveal: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AiChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 10.sp) }
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 10.sp) }
}
