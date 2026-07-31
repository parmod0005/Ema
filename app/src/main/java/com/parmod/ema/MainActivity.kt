package com.parmod.ema

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.model.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { EmaApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmaApp(vm: TradingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var token by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMA Options") },
                actions = { Text(if (state.isConnected) "UPSTOX LIVE" else "OFFLINE", Modifier.padding(end = 16.dp), fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LiveConnectionCard(
                    token = token,
                    expiry = expiry,
                    connected = state.isConnected,
                    onTokenChange = { token = it },
                    onExpiryChange = { expiry = it },
                    onConnect = { vm.connectLive(token, expiry) },
                    onDemo = vm::connectDemo,
                    onDisconnect = vm::disconnect,
                )
            }
            item { SelectorRow(state, vm) }
            item { MarketSummary(state) }
            item { SignalCard(state) }
            item { TradeControls(state, vm) }
            state.position?.let { item { PositionCard(state, vm) } }
            item { Text(state.message, style = MaterialTheme.typography.bodySmall) }
            item { Text("Option chain · 5 ITM + ATM + 5 OTM", fontWeight = FontWeight.Bold) }
            items(state.optionChain) { OptionRow(it) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun LiveConnectionCard(
    token: String,
    expiry: String,
    connected: Boolean,
    onTokenChange: (String) -> Unit,
    onExpiryChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Live market data", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Upstox access token") },
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = expiry,
                onValueChange = onExpiryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Option expiry YYYY-MM-DD") },
                supportingText = { Text("Token stays only in app memory; it is not committed or saved") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !connected) { Text("CONNECT LIVE") }
                OutlinedButton(onClick = onDemo, enabled = !connected) { Text("DEMO") }
                OutlinedButton(onClick = onDisconnect, enabled = connected) { Text("DISCONNECT") }
            }
        }
    }
}

@Composable
private fun SelectorRow(state: DashboardState, vm: TradingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton("NIFTY", state.index == MarketIndex.NIFTY) { vm.selectIndex(MarketIndex.NIFTY) }
            ToggleButton("SENSEX", state.index == MarketIndex.SENSEX) { vm.selectIndex(MarketIndex.SENSEX) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton("MANUAL", state.tradingMode == TradingMode.MANUAL) { vm.setTradingMode(TradingMode.MANUAL) }
            ToggleButton("AUTO", state.tradingMode == TradingMode.AUTO) { vm.setTradingMode(TradingMode.AUTO) }
            ToggleButton("PAPER", true) { vm.setExecutionMode(ExecutionMode.PAPER) }
            OutlinedButton(onClick = { vm.setExecutionMode(ExecutionMode.LIVE) }) { Text("LIVE ORDERS LOCKED") }
        }
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun MarketSummary(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text(state.index.name, style = MaterialTheme.typography.labelLarge); Text(formatPrice(state.spotPrice), style = MaterialTheme.typography.headlineMedium) }
            Column(horizontalAlignment = Alignment.End) { Text("LIVE-DATA PAPER P&L", style = MaterialTheme.typography.labelLarge); Text(formatPrice(state.pnl), style = MaterialTheme.typography.titleLarge) }
        }
    }
}

@Composable
private fun SignalCard(state: DashboardState) {
    val signal = state.signal
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(signal.action.name.replace('_', ' '), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${signal.trend.name} · Confidence ${signal.confidence}/100")
            Text("Entry ${formatOptional(signal.entry)} · SL ${formatOptional(signal.stopLoss)} · Target ${formatOptional(signal.target)}")
            signal.reasons.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun TradeControls(state: DashboardState, vm: TradingViewModel) {
    if (state.tradingMode == TradingMode.MANUAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::buyCe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("PAPER BUY ATM CE") }
            Button(onClick = vm::buyPe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("PAPER BUY ATM PE") }
        }
    } else Card(Modifier.fillMaxWidth()) { Text("AUTO PAPER armed · live-data trades at confidence ≥80", Modifier.padding(12.dp)) }
}

@Composable
private fun PositionCard(state: DashboardState, vm: TradingViewModel) {
    val p = state.position ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("${p.strike.toInt()} ${p.side} × ${p.quantity}", fontWeight = FontWeight.Bold); Text("Entry ${formatPrice(p.entryPrice)} · Live LTP ${formatPrice(p.currentPrice)}") }
            Column(horizontalAlignment = Alignment.End) { Text(formatPrice(p.pnl), fontWeight = FontWeight.Bold); OutlinedButton(onClick = vm::exitPosition) { Text("EXIT") } }
        }
    }
}

@Composable
private fun OptionRow(q: OptionQuote) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("${q.strike.toInt()} ${q.type}${if (q.isAtm) " · ATM" else ""}", fontWeight = FontWeight.Bold); Text("LTP ${formatPrice(q.ltp)}") }
            Column(horizontalAlignment = Alignment.End) { Text("OI ${q.openInterest} · ΔOI ${q.changeInOpenInterest}"); Text("Δ ${formatDecimal(q.delta)} · Γ ${formatDecimal(q.gamma)}") }
        }
    }
}

private fun formatPrice(value: Double): String = String.format(Locale.US, "₹%,.2f", value)
private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun formatOptional(value: Double?): String = value?.let(::formatPrice) ?: "—"
