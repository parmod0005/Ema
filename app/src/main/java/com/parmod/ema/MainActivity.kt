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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { VardhaniApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun VardhaniApp(vm: TradingViewModel = viewModel(), backtestVm: BacktestViewModel = viewModel()) {
    val sampledState = remember(vm) { vm.state.sample(UI_REFRESH_MS) }
    val state by sampledState.collectAsState(initial = vm.state.value)
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
    fun stopBackground() { context.startService(Intent(context, VardhaniMarketService::class.java).setAction(VardhaniMarketService.ACTION_STOP)) }
    fun connect(index: MarketIndex = state.index) {
        if (token.isBlank() || connecting) return
        connecting = true
        vault.updateUpstoxAccessToken(token)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { UpstoxOptionDiscoveryClient(token.trim()).discover(index) } }
                .onSuccess {
                    expiries = it.expiries; expiry = it.nearestExpiry
                    vm.connectLive(token.trim(), it.nearestExpiry); startBackground(index, it.nearestExpiry)
                }.onFailure { stopBackground(); vm.disconnect() }
            connecting = false
        }
    }
    LaunchedEffect(state.index) { if (token.isNotBlank() && state.connectionMode == ConnectionMode.UPSTOX) connect(state.index) }

    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.vardhani_logo), "VARDHANI", Modifier.size(36.dp), tint = Color.Unspecified)
                Spacer(Modifier.width(8.dp)); Column { Text("VARDHANI", fontWeight = FontWeight.Bold); Text("3 ENGINE · LIVE PAPER", fontSize = 10.sp) }
            }
        }, actions = {
            Text(if (state.isConnected) "● LIVE" else "● OFFLINE", color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(end = 12.dp))
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item { ConnectionPanel(state, token, { token = it.trim() }, connecting, expiry, expiries, expiryMenu, { expiryMenu = it }, {
                expiry = it; expiryMenu = false; vm.connectLive(token, it); startBackground(state.index, it)
            }, { connect() }, { stopBackground(); vm.connectDemo() }, { stopBackground(); vm.disconnect() }) }
            item { UpstoxCredentialsPanel { saved -> if (saved.isNotBlank()) token = saved.trim() } }
            item { MarketModePanel(state, vm) }
            item { EngineSelectionPanel(state, vm) }
            item { LotSelectorPanel(state, vm) }
            item { CombinedSummary(state) }
            item { EngineCard(state.engine1, state, vm) }
            item { EngineCard(state.engine2, state, vm) }
            item { EngineCard(state.engine3, state, vm) }
            item { TradeLogCard(state.tradeLog) }
            if (state.appMode == AppMode.BACKTEST) item { BacktestCard(token, state.index, backtest, backtestVm) }
            item { Text(state.message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(4.dp)) }
            if (state.appMode == AppMode.LIVE_MARKET) item { OptionChain(state.optionChain) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ConnectionPanel(state: DashboardState, token: String, onToken: (String) -> Unit, connecting: Boolean, expiry: String, expiries: List<String>, expanded: Boolean, onExpanded: (Boolean) -> Unit, onExpiry: (String) -> Unit, onConnect: () -> Unit, onDemo: () -> Unit, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.isConnected) {
            OutlinedTextField(token, onToken, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Upstox access token") }, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onConnect, Modifier.weight(1f), enabled = token.isNotBlank() && !connecting) { Text(if (connecting) "CONNECTING…" else "CONNECT LIVE") }
                OutlinedButton(onDemo, Modifier.weight(1f)) { Text("DEMO") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("${state.index.name} connected", fontWeight = FontWeight.Bold); Text("Expiry ${expiry.ifBlank { "automatic" }} · one feed → three engines", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(onDisconnect) { Text("DISCONNECT") }
            }
            if (expiries.size > 1) Box { OutlinedButton({ onExpanded(true) }, Modifier.fillMaxWidth()) { Text("Expiry: $expiry") }; DropdownMenu(expanded, { onExpanded(false) }) { expiries.forEach { value -> DropdownMenuItem({ Text(value) }, { onExpiry(value) }) } } }
        }
    } }
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
            Choice("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
            OutlinedButton({ vm.setLiveTradingEnabled(false) }, Modifier.weight(1.2f)) { Text("PAPER", fontSize = 9.sp) }
        }
    }
}

@Composable
private fun EngineSelectionPanel(state: DashboardState, vm: TradingViewModel) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AUTO ENGINE SELECTION", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text("Select any one, any two, or all three. Each selected engine trades independently.", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            EngineToggle("E1 TREND", EngineId.ENGINE_1_TREND, state, vm, Modifier.weight(1f))
            EngineToggle("E2 AVWAP", EngineId.ENGINE_2_AVWAP_LIQUIDITY, state, vm, Modifier.weight(1f))
            EngineToggle("E3 V7.6", EngineId.ENGINE_3_V76_SCALPER, state, vm, Modifier.weight(1f))
        }
        Text("Selected: ${state.enabledEngines.size}/3", style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable
private fun EngineToggle(label: String, id: EngineId, state: DashboardState, vm: TradingViewModel, modifier: Modifier) {
    val selected = id in state.enabledEngines
    if (selected) Button({ vm.toggleEngine(id) }, modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("✓ $label", fontSize = 9.sp) }
    else OutlinedButton({ vm.toggleEngine(id) }, modifier, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, fontSize = 9.sp) }
}

@Composable
private fun LotSelectorPanel(state: DashboardState, vm: TradingViewModel) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("LOT SELECTOR", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        LotRow("NIFTY", state.niftyLots, { vm.setLots(MarketIndex.NIFTY, it) }, "65 qty/lot")
        LotRow("SENSEX", state.sensexLots, { vm.setLots(MarketIndex.SENSEX, it) }, "20 qty/lot")
        Text("Current ${state.index.name}: ${state.selectedLots} lot(s)", style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable
private fun LotRow(name: String, lots: Int, set: (Int) -> Unit, suffix: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$name · $suffix", Modifier.weight(1f), fontSize = 11.sp)
        OutlinedButton({ set(lots - 1) }, enabled = lots > 1, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−") }
        Text(lots.toString(), Modifier.width(42.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        OutlinedButton({ set(lots + 1) }, enabled = lots < 20, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+") }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
    else OutlinedButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
}

@Composable
private fun CombinedSummary(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row { Metric("SPOT", money(state.spotPrice), Modifier.weight(1f)); Metric("COMBINED EQ", money(state.combinedEquity), Modifier.weight(1f)); Metric("COMBINED P&L", money(state.combinedRealizedPnl + state.combinedOpenPnl), Modifier.weight(1f)) }
        Text(if (state.riskLocked) "PAPER RISK LOCK · ${state.riskReason}" else "3 engines independent · ${state.enabledEngines.size} selected for AUTO", style = MaterialTheme.typography.labelSmall, color = if (state.riskLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
private fun EngineCard(engine: EngineState, state: DashboardState, vm: TradingViewModel) {
    val signalText = when (engine.signal.action) { SignalAction.BUY_CE -> "BUY CALL"; SignalAction.BUY_PE -> "BUY PUT"; SignalAction.WAIT -> "WAIT" }
    val enabled = engine.id in state.enabledEngines
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row { Text(engine.name, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(if (enabled) "AUTO ON" else "AUTO OFF", color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        Text(engine.signal.setup, style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(signalText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium); Text("${engine.signal.trend} · ${engine.signal.confidence}/100", style = MaterialTheme.typography.labelSmall) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text("P&L ${money(engine.totalPnl)}", fontWeight = FontWeight.Bold); Text("${engine.performance.trades} trades · ${"%.1f".format(engine.performance.winRate)}% win", style = MaterialTheme.typography.labelSmall) }
        }
        Row { Metric("PF", formatPf(engine.performance.profitFactor), Modifier.weight(1f)); Metric("REALIZED", money(engine.performance.realizedPnl), Modifier.weight(1f)); Metric("MAX DD", money(engine.performance.maxDrawdown), Modifier.weight(1f)) }
        Text(engine.signal.reasons.take(4).joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 4)
        engine.position?.let { p ->
            HorizontalDivider(); Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OPEN · ${p.strike.toInt()} ${p.side} ×${p.quantity} · ${p.lots}L", fontWeight = FontWeight.Bold)
                    Text("Entry ${money(p.entryPrice)} · Now ${money(p.currentPrice)}", style = MaterialTheme.typography.labelSmall)
                    Text("SL ${money(p.stopPrice)} · T1 ${money(p.targetPrice)}${if (p.target1Hit) " HIT" else ""}", style = MaterialTheme.typography.labelSmall)
                    if (p.strategy.isNotBlank()) Text("${p.strategy} · held ${(System.currentTimeMillis()-p.openedAtMillis)/60000}/${p.maxHoldMinutes}m", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.End) { Text(money(p.pnl), fontWeight = FontWeight.Bold); TextButton({ vm.exitEngine(engine.id) }) { Text("EXIT") } }
            }
        }
        if (state.appMode == AppMode.LIVE_MARKET && state.tradingMode == TradingMode.MANUAL && engine.position == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ vm.manualBuy(engine.id, PositionSide.CE) }, Modifier.weight(1f), enabled = state.isConnected && !state.riskLocked) { Text("PAPER CALL") }
                Button({ vm.manualBuy(engine.id, PositionSide.PE) }, Modifier.weight(1f), enabled = state.isConnected && !state.riskLocked) { Text("PAPER PUT") }
            }
        }
        Text(engine.message, style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable
private fun TradeLogCard(log: List<TradeLogEntry>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TRADE LOG", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("${log.size} record(s)", style = MaterialTheme.typography.labelSmall)
            }
            if (log.isEmpty()) {
                Text("No trades yet. Entries and exits from all three engines will appear here.", style = MaterialTheme.typography.labelSmall)
            } else {
                log.takeLast(10).asReversed().forEach { t ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${shortEngine(t.engineId)} · ${t.index} · ${t.strike.toInt()} ${t.side} ×${t.quantity}", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text("IN ${timeText(t.entryTimeMillis)} @ ${money(t.entryPrice)} · spot ${money(t.entrySpot)}", style = MaterialTheme.typography.labelSmall)
                            if (t.status == TradeStatus.CLOSED) {
                                Text("OUT ${t.exitTimeMillis?.let(::timeText) ?: "—"} @ ${t.exitPrice?.let(::money) ?: "—"} · ${t.exitReason}", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text("OPEN · ${t.setup}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(if (t.status == TradeStatus.OPEN) "OPEN" else t.pnl?.let(::money) ?: "₹0.00", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

private fun shortEngine(id: EngineId): String = when (id) {
    EngineId.ENGINE_1_TREND -> "E1"
    EngineId.ENGINE_2_AVWAP_LIQUIDITY -> "E2"
    EngineId.ENGINE_3_V76_SCALPER -> "E3"
}

private fun timeText(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.of("Asia/Kolkata"))
    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))

@Composable private fun Metric(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontSize = 9.sp); Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp) }

@Composable
private fun BacktestCard(token: String, index: MarketIndex, state: BacktestViewModel.UiState, vm: BacktestViewModel) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ENGINE 1 HISTORICAL BACKTEST · ${index.name}", fontWeight = FontWeight.Bold)
        Text("Existing historical replay currently evaluates Engine 1. Live paper results are tracked separately for all engines.", style = MaterialTheme.typography.labelSmall)
        BacktestRangeSelector(state.selectedMonths, !state.isRunning, vm::selectMonths)
        if (state.isRunning) { LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth()); Text("${state.completed}/${state.total.coerceAtLeast(1)} · ${state.message}", style = MaterialTheme.typography.labelSmall); OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("CANCEL") } }
        else Button({ vm.run(token, index) }, Modifier.fillMaxWidth(), enabled = token.isNotBlank()) { Text("FETCH / RESUME & RUN") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        state.result?.let { result -> Row { Metric("TRADES", result.report.trades.toString(), Modifier.weight(1f)); Metric("WIN", "%.1f%%".format(result.report.winRate * 100), Modifier.weight(1f)); Metric("NET", money(result.report.netPnl), Modifier.weight(1f)) }; Text("PF ${"%.2f".format(result.report.profitFactor)} · Test PF ${"%.2f".format(result.testReport.profitFactor)} · DD ${money(result.maxAccountDrawdown)}", style = MaterialTheme.typography.labelSmall) }
    } }
}

private data class ChainRow(val strike: Double, val ce: OptionQuote?, val pe: OptionQuote?, val atm: Boolean)
@Composable
private fun OptionChain(options: List<OptionQuote>) {
    val rows = remember(options) { options.groupBy { it.strike }.toSortedMap().map { (strike, q) -> ChainRow(strike, q.firstOrNull { it.type == "CE" }, q.firstOrNull { it.type == "PE" }, q.any { it.isAtm }) } }
    Card(Modifier.fillMaxWidth()) { Column { Text("OPTION CHAIN · LIVE", Modifier.padding(10.dp), fontWeight = FontWeight.Bold); rows.take(11).forEach { row ->
        Row(Modifier.fillMaxWidth().then(if (row.atm) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.ce?.ltp?.let(::money) ?: "—", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp); Text(row.strike.toInt().toString(), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = if (row.atm) FontWeight.Bold else FontWeight.Normal, fontSize = 10.sp); Text(row.pe?.ltp?.let(::money) ?: "—", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 10.sp)
        }
    } } }
}

private fun money(value: Double): String = if (value == 0.0) "₹0.00" else "₹" + String.format(Locale.US, "%,.2f", value)
private fun formatPf(value: Double): String = if (value.isInfinite()) "∞" else String.format(Locale.US, "%.2f", value)
private const val UI_REFRESH_MS = 250L
