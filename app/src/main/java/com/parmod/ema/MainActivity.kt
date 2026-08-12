package com.parmod.ema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.backtest.BacktestRangeSelector
import com.parmod.ema.backtest.BacktestViewModel
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.model.*
import com.parmod.ema.service.VardhaniMarketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { VardhaniApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VardhaniApp(
    vm: TradingViewModel = viewModel(),
    backtestVm: BacktestViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val backtest by backtestVm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context) { LocalCredentialVault(context) }
    var token by remember { mutableStateOf(vault.read().upstoxAccessToken) }
    var connecting by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf("") }
    var expiries by remember { mutableStateOf(emptyList<String>()) }
    var expiryMenu by remember { mutableStateOf(false) }

    fun startBackground(index: MarketIndex, selectedExpiry: String) {
        if (token.isBlank() || selectedExpiry.isBlank()) return
        val intent = Intent(context, VardhaniMarketService::class.java).apply {
            action = VardhaniMarketService.ACTION_START
            putExtra(VardhaniMarketService.EXTRA_EXPIRY, selectedExpiry)
            putExtra(VardhaniMarketService.EXTRA_INDEX, index.name)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopBackground() {
        context.startService(Intent(context, VardhaniMarketService::class.java).setAction(VardhaniMarketService.ACTION_STOP))
    }

    fun connect(index: MarketIndex = state.index) {
        if (token.isBlank() || connecting) return
        connecting = true
        vault.updateUpstoxAccessToken(token)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { UpstoxOptionDiscoveryClient(token.trim()).discover(index) } }
                .onSuccess {
                    expiries = it.expiries
                    expiry = it.nearestExpiry
                    vm.connectLive(token.trim(), it.nearestExpiry)
                    startBackground(index, it.nearestExpiry)
                }
                .onFailure {
                    stopBackground()
                    vm.disconnect()
                }
            connecting = false
        }
    }

    LaunchedEffect(state.index) {
        if (token.isNotBlank() && state.connectionMode == ConnectionMode.UPSTOX) connect(state.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.vardhani_logo), "VARDHANI", Modifier.size(36.dp), tint = Color.Unspecified)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("VARDHANI", fontWeight = FontWeight.Bold)
                            Text("DUAL ENGINE · LIVE PAPER", fontSize = 10.sp)
                        }
                    }
                },
                actions = {
                    Text(
                        if (state.isConnected) "● LIVE" else "● OFFLINE",
                        color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            item {
                ConnectionPanel(
                    state = state,
                    token = token,
                    onToken = { token = it.trim() },
                    connecting = connecting,
                    expiry = expiry,
                    expiries = expiries,
                    expanded = expiryMenu,
                    onExpanded = { expiryMenu = it },
                    onExpiry = {
                        expiry = it
                        expiryMenu = false
                        vm.connectLive(token, it)
                        startBackground(state.index, it)
                    },
                    onConnect = { connect() },
                    onDemo = { stopBackground(); vm.connectDemo() },
                    onDisconnect = { stopBackground(); vm.disconnect() },
                )
            }
            item { UpstoxCredentialsPanel { saved -> if (saved.isNotBlank()) token = saved.trim() } }
            item { MarketModePanel(state, vm) }
            item { CombinedSummary(state) }
            item { EngineCard(state.engine1, state, vm) }
            item { EngineCard(state.engine2, state, vm) }
            if (state.appMode == AppMode.BACKTEST) item { BacktestCard(token, state.index, backtest, backtestVm) }
            item { Text(state.message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp)) }
            if (state.appMode == AppMode.LIVE_MARKET) item { OptionChain(state.optionChain) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: DashboardState,
    token: String,
    onToken: (String) -> Unit,
    connecting: Boolean,
    expiry: String,
    expiries: List<String>,
    expanded: Boolean,
    onExpanded: (Boolean) -> Unit,
    onExpiry: (String) -> Unit,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.isConnected) {
                OutlinedTextField(
                    token, onToken, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Upstox access token") }, visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onConnect, Modifier.weight(1f), enabled = token.isNotBlank() && !connecting) { Text(if (connecting) "CONNECTING…" else "CONNECT LIVE") }
                    OutlinedButton(onDemo, Modifier.weight(1f)) { Text("DEMO") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${state.index.name} connected", fontWeight = FontWeight.Bold)
                        Text("Expiry ${expiry.ifBlank { "automatic" }} · one feed → two engines", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onDisconnect) { Text("DISCONNECT") }
                }
                if (expiries.size > 1) Box {
                    OutlinedButton({ onExpanded(true) }, Modifier.fillMaxWidth()) { Text("Expiry: $expiry") }
                    DropdownMenu(expanded, { onExpanded(false) }) {
                        expiries.forEach { value -> DropdownMenuItem({ Text(value) }, { onExpiry(value) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketModePanel(state: DashboardState, vm: TradingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("NIFTY", state.index == MarketIndex.NIFTY, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.NIFTY) }
            Choice("SENSEX", state.index == MarketIndex.SENSEX, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.SENSEX) }
            Choice("LIVE", state.appMode == AppMode.LIVE_MARKET, Modifier.weight(1f)) { vm.setAppMode(AppMode.LIVE_MARKET) }
            Choice("BACKTEST", state.appMode == AppMode.BACKTEST, Modifier.weight(1f)) { vm.setAppMode(AppMode.BACKTEST) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("MANUAL", state.tradingMode == TradingMode.MANUAL, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.MANUAL) }
            Choice("AUTO BOTH", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
            OutlinedButton({ vm.setLiveTradingEnabled(false) }, Modifier.weight(1.2f)) { Text("PAPER ONLY", fontSize = 9.sp) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
    else OutlinedButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
}

@Composable
private fun CombinedSummary(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row {
                Metric("SPOT", money(state.spotPrice), Modifier.weight(1f))
                Metric("COMBINED EQ", money(state.combinedEquity), Modifier.weight(1f))
                Metric("COMBINED P&L", money(state.combinedRealizedPnl + state.combinedOpenPnl), Modifier.weight(1f))
            }
            Text(
                if (state.riskLocked) "PAPER RISK LOCK · ${state.riskReason}" else "Both engines execute independently from the same live feed",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.riskLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EngineCard(engine: EngineState, state: DashboardState, vm: TradingViewModel) {
    val signalText = when (engine.signal.action) {
        SignalAction.BUY_CE -> "BUY CALL"
        SignalAction.BUY_PE -> "BUY PUT"
        SignalAction.WAIT -> "WAIT"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(engine.name, fontWeight = FontWeight.Bold)
            Text(engine.signal.setup, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(signalText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${engine.signal.trend} · ${engine.signal.confidence}/100", style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text("P&L ${money(engine.totalPnl)}", fontWeight = FontWeight.Bold)
                    Text("${engine.performance.trades} trades · ${"%.1f".format(engine.performance.winRate)}% win", style = MaterialTheme.typography.labelSmall)
                }
            }
            Row {
                Metric("PF", formatPf(engine.performance.profitFactor), Modifier.weight(1f))
                Metric("REALIZED", money(engine.performance.realizedPnl), Modifier.weight(1f))
                Metric("MAX DD", money(engine.performance.maxDrawdown), Modifier.weight(1f))
            }
            Text(engine.signal.reasons.take(3).joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 3)

            engine.position?.let { p ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OPEN · ${p.strike.toInt()} ${p.side} ×${p.quantity}", fontWeight = FontWeight.Bold)
                        Text("Entry ${money(p.entryPrice)} · Now ${money(p.currentPrice)}", style = MaterialTheme.typography.labelSmall)
                        Text("SL ${money(p.stopPrice)} · TG ${money(p.targetPrice)}", style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(p.pnl), fontWeight = FontWeight.Bold)
                        TextButton({ vm.exitEngine(engine.id) }) { Text("EXIT") }
                    }
                }
            }

            if (state.appMode == AppMode.LIVE_MARKET && state.tradingMode == TradingMode.MANUAL && engine.position == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ vm.manualBuy(engine.id, PositionSide.CE) }, Modifier.weight(1f), enabled = state.isConnected && !state.riskLocked) { Text("PAPER CALL") }
                    Button({ vm.manualBuy(engine.id, PositionSide.PE) }, Modifier.weight(1f), enabled = state.isConnected && !state.riskLocked) { Text("PAPER PUT") }
                }
            }
            Text(engine.message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, fontSize = 9.sp)
    Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

@Composable
private fun BacktestCard(token: String, index: MarketIndex, state: BacktestViewModel.UiState, vm: BacktestViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ENGINE 1 HISTORICAL BACKTEST · ${index.name}", fontWeight = FontWeight.Bold)
            Text("Existing historical replay currently evaluates Engine 1. Engine 2 live paper results are tracked separately.", style = MaterialTheme.typography.labelSmall)
            BacktestRangeSelector(state.selectedMonths, !state.isRunning, vm::selectMonths)
            if (state.isRunning) {
                LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                Text("${state.completed}/${state.total.coerceAtLeast(1)} · ${state.message}", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("CANCEL") }
            } else Button({ vm.run(token, index) }, Modifier.fillMaxWidth(), enabled = token.isNotBlank()) { Text("FETCH / RESUME & RUN") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            state.result?.let { result ->
                Row {
                    Metric("TRADES", result.report.trades.toString(), Modifier.weight(1f))
                    Metric("WIN", "%.1f%%".format(result.report.winRate * 100), Modifier.weight(1f))
                    Metric("NET", money(result.report.netPnl), Modifier.weight(1f))
                }
                Text("PF ${"%.2f".format(result.report.profitFactor)} · Test PF ${"%.2f".format(result.testReport.profitFactor)} · DD ${money(result.maxAccountDrawdown)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class ChainRow(val strike: Double, val ce: OptionQuote?, val pe: OptionQuote?, val atm: Boolean)

@Composable
private fun OptionChain(options: List<OptionQuote>) {
    val rows = remember(options) {
        options.groupBy { it.strike }.toSortedMap().map { (strike, q) ->
            ChainRow(strike, q.firstOrNull { it.type == "CE" }, q.firstOrNull { it.type == "PE" }, q.any { it.isAtm })
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text("OPTION CHAIN · LIVE", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            rows.take(11).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().then(if (row.atm) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier).padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.ce?.ltp?.let(::money) ?: "—", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp)
                    Text(row.strike.toInt().toString(), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = if (row.atm) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp)
                    Text(row.pe?.ltp?.let(::money) ?: "—", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun money(value: Double): String = if (value == 0.0) "₹0.00" else "₹" + String.format(Locale.US, "%,.2f", value)
private fun formatPf(value: Double): String = if (value.isInfinite()) "∞" else String.format(Locale.US, "%.2f", value)
