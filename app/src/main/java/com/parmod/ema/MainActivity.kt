package com.parmod.ema

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var selectedExpiry by remember { mutableStateOf("") }
    var availableExpiries by remember { mutableStateOf(emptyList<String>()) }
    var expiryExpanded by remember { mutableStateOf(false) }

    fun connectWithToken() {
        if (token.isBlank() || isConnecting) return
        isConnecting = true
        scope.launch {
            try {
                val discovery = withContext(Dispatchers.IO) {
                    UpstoxOptionDiscoveryClient(token.trim()).discover(state.index)
                }
                availableExpiries = discovery.expiries
                selectedExpiry = discovery.nearestExpiry
                vm.connectLive(token.trim(), discovery.nearestExpiry)
            } catch (error: Exception) {
                vm.disconnect()
            } finally {
                isConnecting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMA Options", fontWeight = FontWeight.SemiBold) },
                actions = {
                    Text(
                        if (state.isConnected) "● LIVE DATA" else "● OFFLINE",
                        modifier = Modifier.padding(end = 12.dp),
                        color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Upstox live market data", fontWeight = FontWeight.Bold)
                        if (!state.isConnected) {
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it.trim() },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Paste Upstox access token") },
                                visualTransformation = PasswordVisualTransformation(),
                                supportingText = { Text("Generate token in Upstox, copy it, and paste it here") },
                            )
                            Button(
                                onClick = { connectWithToken() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = token.isNotBlank() && !isConnecting,
                            ) { Text(if (isConnecting) "VERIFYING TOKEN…" else "CONNECT LIVE") }
                            OutlinedButton(onClick = vm::connectDemo, modifier = Modifier.fillMaxWidth()) { Text("DEMO MODE") }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Connected", fontWeight = FontWeight.Bold)
                                    Text("Expiry ${selectedExpiry.ifBlank { "auto" }}", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(onClick = vm::disconnect) { Text("DISCONNECT") }
                            }
                            if (availableExpiries.size > 1) {
                                Box {
                                    OutlinedButton(onClick = { expiryExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Expiry: $selectedExpiry")
                                    }
                                    DropdownMenu(expanded = expiryExpanded, onDismissRequest = { expiryExpanded = false }) {
                                        availableExpiries.forEach { expiry ->
                                            DropdownMenuItem(
                                                text = { Text(expiry) },
                                                onClick = {
                                                    expiryExpanded = false
                                                    selectedExpiry = expiry
                                                    vm.connectLive(token.trim(), expiry)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { SelectorPanel(state, vm) }
            item { SummaryAndSignal(state) }
            item { TradeControls(state, vm) }
            state.position?.let { item { PositionStrip(state, vm) } }
            item { Text(state.message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)) }
            item { OptionChainTable(state.optionChain) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SelectorPanel(state: DashboardState, vm: TradingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("NIFTY", state.index == MarketIndex.NIFTY, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.NIFTY) }
            Choice("SENSEX", state.index == MarketIndex.SENSEX, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.SENSEX) }
            Choice("PAPER", true, Modifier.weight(1f)) { vm.setExecutionMode(ExecutionMode.PAPER) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("MANUAL", state.tradingMode == TradingMode.MANUAL, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.MANUAL) }
            Choice("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
            OutlinedButton(onClick = { vm.setExecutionMode(ExecutionMode.LIVE) }, modifier = Modifier.weight(1f)) { Text("ORDERS OFF", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false, modifier = modifier) { Text(label, fontSize = 12.sp) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun SummaryAndSignal(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(state.index.name, style = MaterialTheme.typography.labelMedium)
                Text(price(state.spotPrice), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Paper P&L ${price(state.pnl)}", style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.weight(1.4f)) {
                Text(state.signal.action.name.replace('_', ' '), fontWeight = FontWeight.Bold)
                Text("${state.signal.trend.name} · ${state.signal.confidence}/100", style = MaterialTheme.typography.labelMedium)
                Text(state.signal.reasons.firstOrNull() ?: "Waiting", style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
    }
}

@Composable
private fun TradeControls(state: DashboardState, vm: TradingViewModel) {
    if (state.tradingMode == TradingMode.MANUAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = vm::buyCe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("PAPER BUY CE") }
            Button(onClick = vm::buyPe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("PAPER BUY PE") }
        }
    } else Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Text("AUTO PAPER ARMED · confidence ≥80", Modifier.padding(9.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun PositionStrip(state: DashboardState, vm: TradingViewModel) {
    val p = state.position ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.strike.toInt()} ${p.side} ×${p.quantity}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text("${price(p.currentPrice)}  ${price(p.pnl)}", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp)
            TextButton(onClick = vm::exitPosition) { Text("EXIT") }
        }
    }
}

private data class ChainRow(val strike: Double, val ce: OptionQuote?, val pe: OptionQuote?, val atm: Boolean)

@Composable
private fun OptionChainTable(options: List<OptionQuote>) {
    val rows = remember(options) {
        options.groupBy { it.strike }.toSortedMap().map { (strike, quotes) ->
            ChainRow(strike, quotes.firstOrNull { it.type == "CE" }, quotes.firstOrNull { it.type == "PE" }, quotes.any { it.isAtm })
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text("OPTION CHAIN · LIVE TICKS", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp)) {
                Header("CE LTP", 1f); Header("CE OI/ΔOI", 1.2f); Header("STRIKE", .9f); Header("PE LTP", 1f); Header("PE OI/ΔOI", 1.2f)
            }
            if (rows.isEmpty()) Text("Connect live data to load the option chain", Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center)
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().background(if (row.atm) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .border(.25.dp, MaterialTheme.colorScheme.outlineVariant).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cell(row.ce?.ltp?.let { "%.2f".format(Locale.US, it) } ?: "—", 1f)
                    Cell(row.ce?.let { "${compact(it.openInterest)}/${signed(it.changeInOpenInterest)}" } ?: "—", 1.2f)
                    Cell(row.strike.toInt().toString() + if (row.atm) "\nATM" else "", .9f, true)
                    Cell(row.pe?.ltp?.let { "%.2f".format(Locale.US, it) } ?: "—", 1f)
                    Cell(row.pe?.let { "${compact(it.openInterest)}/${signed(it.changeInOpenInterest)}" } ?: "—", 1.2f)
                }
            }
        }
    }
}

@Composable private fun RowScope.Header(text: String, weight: Float) = Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 10.sp, fontWeight = FontWeight.Bold)
@Composable private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false) = Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
private fun compact(v: Long) = when { kotlin.math.abs(v) >= 100_000 -> "%.1fL".format(Locale.US, v / 100_000.0); kotlin.math.abs(v) >= 1_000 -> "%.1fK".format(Locale.US, v / 1_000.0); else -> v.toString() }
private fun signed(v: Long) = (if (v > 0) "+" else "") + compact(v)
private fun price(v: Double) = "₹%,.2f".format(Locale.US, v)
