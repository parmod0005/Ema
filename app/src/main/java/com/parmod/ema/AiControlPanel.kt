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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parmod.ema.ai.AiRunMode
import com.parmod.ema.ai.SignalEngineMode
import com.parmod.ema.model.DashboardState
import com.parmod.ema.model.SignalAction

@Composable
fun AiControlPanel(state: DashboardState, vm: TradingViewModel) {
    val health = state.aiBridgeHealth
    val decision = state.aiDecision
    var bridgeUrl by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SIGNAL BRAIN", fontWeight = FontWeight.Bold)
                Text(
                    if (health.reachable) "● AI ONLINE" else if (health.configured) "● AI OFFLINE" else "● NOT CONFIGURED",
                    color = if (health.reachable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!health.configured) {
                OutlinedTextField(
                    value = bridgeUrl,
                    onValueChange = { bridgeUrl = it.trim() },
                    label = { Text("AI Bridge HTTPS URL") },
                    placeholder = { Text("https://your-vardhani-bridge.example") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceToken,
                    onValueChange = { deviceToken = it.trim() },
                    label = { Text("Bridge device token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.configureAiBridge(bridgeUrl, deviceToken) },
                    enabled = bridgeUrl.startsWith("https://") && deviceToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("CONNECT AI BRIDGE") }
            } else {
                OutlinedButton(onClick = { vm.configureAiBridge("", ""); bridgeUrl = ""; deviceToken = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("DISCONNECT AI BRIDGE")
                }
            }

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
            Text("Bridge: ${health.message} · latency $latency", style = MaterialTheme.typography.labelSmall)

            if (decision == null) {
                Text("AI decision: waiting for a bridge snapshot", style = MaterialTheme.typography.bodySmall)
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
            if (state.aiRunMode == AiRunMode.SHADOW) Text("Shadow mode records AI analysis but cannot open a trade.", style = MaterialTheme.typography.labelSmall)
            Text("Bridge credentials stay in app memory and are not written into the APK.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AiChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 10.sp) }
    else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 10.sp) }
}
