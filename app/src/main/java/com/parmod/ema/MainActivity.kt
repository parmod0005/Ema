package com.parmod.ema

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.backtest.BacktestRangeSelector
import com.parmod.ema.backtest.BacktestViewModel
import com.parmod.ema.data.UpstoxOptionDiscoveryClient
import com.parmod.ema.model.*
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
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf("") }
    var expiries by remember { mutableStateOf(emptyList<String>()) }
    var expiryMenu by remember { mutableStateOf(false) }

    fun connect(index: MarketIndex = state.index) {
        if (token.isBlank() || connecting) return
        connecting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { UpstoxOptionDiscoveryClient(token.trim()).discover(index) }
            }.onSuccess {
                expiries = it.expiries
                expiry = it.nearestExpiry
                vm.connectLive(token.trim(), it.nearestExpiry)
            }.onFailure { vm.disconnect() }
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
                            Text("AI Options Intelligence", fontSize = 10.sp)
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
                    state, token, { token = it.trim() }, connecting, expiry, expiries, expiryMenu,
                    { expiryMenu = it },
                    {
                        expiry = it
                        expiryMenu = false
                        vm.connectLive(token, it)
                    },
                    { connect() }, vm::connectDemo, vm::disconnect,
                )
            }
            item { MarketModePanel(state, vm) }
            item { AiControlPanel(state, vm) }
            item { SignalCard(state) }
            item { TradingControls(state, vm) }
            state.position?.let { item { PositionCard(state, vm) } }
            if (state.appMode == AppMode.BACKTEST) {
                item { BacktestCard(token, state.index, backtest, backtestVm) }
            }
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
                    label = { Text("Paste Upstox access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Used only for Upstox live and historical data") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onConnect, Modifier.weight(1f), enabled = token.isNotBlank() && !connecting) {
                        Text(if (connecting) "CONNECTING…" else "CONNECT LIVE")
                    }
                    OutlinedButton(onDemo, Modifier.weight(1f)) { Text("DEMO") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${state.index.name} connected", fontWeight = FontWeight.Bold)
                        Text("Expiry ${expiry.ifBlank { "automatic" }}", style = MaterialTheme.typography.labelSmall)
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
            Choice("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
            Button(
                { vm.setLiveTradingEnabled(!state.liveTradingEnabled) }, Modifier.weight(1.2f),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.liveTradingEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary),
            ) { Text(if (state.liveTradingEnabled) "LIVE ORDERS ON" else "LIVE ORDERS OFF", fontSize = 9.sp) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
    else OutlinedButton(onClick, modifier, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 10.sp) }
}

@Composable
private fun SignalCard(state: DashboardState) {
    val signal = when (state.signal.action) {
        SignalAction.BUY_CE -> "BUY CALL"
        SignalAction.BUY_PE -> "BUY PUT"
        SignalAction.WAIT -> "WAIT"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Metric("CAPITAL", money(state.startingCapital), Modifier.weight(1f))
                Metric("EQUITY", money(state.equity), Modifier.weight(1f))
                Metric("P&L", money(state.realizedPnl + state.pnl), Modifier.weight(1f))
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.index.name, style = MaterialTheme.typography.labelMedium)
                    Text(money(state.spotPrice), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.End) {
                    Text(signal, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${state.signal.trend.name} · ${state.signal.confidence}/100", style = MaterialTheme.typography.labelMedium)
                    Text(state.signal.reasons.firstOrNull() ?: "Waiting", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, fontSize = 9.sp)
    Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

@Composable
private fun TradingControls(state: DashboardState, vm: TradingViewModel) {
    if (state.appMode != AppMode.LIVE_MARKET) return
    if (state.tradingMode == TradingMode.MANUAL) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(vm::buyCe, Modifier.weight(1f), enabled = state.isConnected) { Text("BUY CALL") }
        Button(vm::buyPe, Modifier.weight(1f), enabled = state.isConnected) { Text("BUY PUT") }
    } else Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Text("AUTO MODE · AI Shadow cannot execute · Paper requires approved routing", Modifier.padding(9.dp), textAlign = TextAlign.Center, fontSize = 10.sp)
    }
}

@Composable
private fun PositionCard(state: DashboardState, vm: TradingViewModel) {
    val p = state.position ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT TRADE", fontSize = 9.sp)
                Text("${p.strike.toInt()} ${p.side} ×${p.quantity}", fontWeight = FontWeight.Bold)
                Text("SL ${"%.2f".format(p.stopPrice)} · TG ${"%.2f".format(p.targetPrice)}", fontSize = 9.sp)
            }
            Text("${money(p.currentPrice)}\n${money(p.pnl)}", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 11.sp)
            TextButton(vm::exitPosition) { Text("EXIT") }
        }
    }
}

@Composable
private fun BacktestCard(token: String, index: MarketIndex, state: BacktestViewModel.UiState, vm: BacktestViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UPSTOX PLUS BACKTEST · ${index.name}", fontWeight = FontWeight.Bold)
            BacktestRangeSelector(state.selectedMonths, !state.isRunning, vm::selectMonths)
            if (state.isRunning) {
                LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                Text("${state.completed}/${state.total.coerceAtLeast(1)} · ${state.message}", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("CANCEL") }
            } else Button({ vm.run(token, index) }, Modifier.fillMaxWidth(), enabled = token.isNotBlank()) { Text("FETCH DATA & RUN") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            state.result?.let { result ->
                HorizontalDivider()
                Row {
                    Metric("TRADES", result.report.trades.toString(), Modifier.weight(1f))
                    Metric("WIN", "%.1f%%".format(result.report.winRate * 100), Modifier.weight(1f))
                    Metric("NET", money(result.report.netPnl), Modifier.weight(1f))
                }
                Text("PF ${"%.2f".format(result.report.profitFactor)} · Test PF ${"%.2f".format(result.testReport.profitFactor)} · DD ${money(result.maxAccountDrawdown)}", style = MaterialTheme.typography.labelSmall)
                Text("${result.contractsTested} contracts · ${result.candlesProcessed} candles · ${result.errors.size} permanent errors", style = MaterialTheme.typography.labelSmall)
                if (result.errors.isNotEmpty()) Text("INCOMPLETE DATASET — results are provisional", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                OutlinedButton(vm::clearResult, Modifier.fillMaxWidth()) { Text("CLEAR RESULTS") }
            }
        }
    }
}

private data class ChainRow(val strike: Double, val ce: OptionQuote?, val pe: OptionQuote?, val atm: Boolean)

@Composable
private fun OptionChain(options: List<OptionQuote>) {
    val rows = remember(options) { options.groupBy { it.strike }.toSortedMap().map { (strike, q) -> ChainRow(strike, q.firstOrNull { it.type == "CE" }, q.firstOrNull { it.type == "PE" }, q.any { it.isAtm }) } }
    Card(Modifier.fillMaxWidth()) {
        Column {
            Text("OPTION CHAIN · LIVE TICKS", Modifier.padding(10.dp), fontWeight = FontWeight.Bold)
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp)) {
                Header("CE LTP", 1f); Header("CE OI", 1f); Header("STRIKE", 1f); Header("PE LTP", 1f); Header("PE OI", 1f)
            }
            if (rows.isEmpty()) Text("Connect live data to load the option chain", Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center)
            rows.forEach { row ->
                Row(Modifier.background(if (row.atm) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(vertical = 6.dp)) {
                    Cell(row.ce?.ltp?.let { "%.2f".format(it) } ?: "—", 1f)
                    Cell(row.ce?.openInterest?.let(::compact) ?: "—", 1f)
                    Cell(row.strike.toInt().toString() + if (row.atm) "\nATM" else "", 1f, true)
                    Cell(row.pe?.ltp?.let { "%.2f".format(it) } ?: "—", 1f)
                    Cell(row.pe?.openInterest?.let(::compact) ?: "—", 1f)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable private fun RowScope.Header(text: String, weight: Float) = Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 9.sp, fontWeight = FontWeight.Bold)
@Composable private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false) = Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 10.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
private fun compact(v: Long) = when { kotlin.math.abs(v) >= 100_000 -> "%.1fL".format(v / 100_000.0); kotlin.math.abs(v) >= 1_000 -> "%.1fK".format(v / 1_000.0); else -> v.toString() }
private fun money(v: Double) = "₹%,.2f".format(Locale.US, v)
