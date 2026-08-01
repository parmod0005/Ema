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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var isConnecting by remember { mutableStateOf(false) }
    var selectedExpiry by remember { mutableStateOf("") }
    var availableExpiries by remember { mutableStateOf(emptyList<String>()) }
    var expiryExpanded by remember { mutableStateOf(false) }

    fun discoverAndConnect(index: MarketIndex = state.index) {
        if (token.isBlank() || isConnecting) return
        isConnecting = true
        scope.launch {
            try {
                val discovery = withContext(Dispatchers.IO) {
                    UpstoxOptionDiscoveryClient(token.trim()).discover(index)
                }
                availableExpiries = discovery.expiries
                selectedExpiry = discovery.nearestExpiry
                vm.connectLive(token.trim(), discovery.nearestExpiry)
            } catch (_: Exception) {
                vm.disconnect()
            } finally {
                isConnecting = false
            }
        }
    }

    LaunchedEffect(state.index) {
        if (token.isNotBlank() && state.connectionMode == ConnectionMode.UPSTOX) discoverAndConnect(state.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.vardhani_logo),
                            contentDescription = "VARDHANI",
                            modifier = Modifier.size(36.dp),
                            tint = Color.Unspecified,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("VARDHANI", fontWeight = FontWeight.Bold)
                            Text("Institutional Options", fontSize = 10.sp)
                        }
                    }
                },
                actions = {
                    Text(
                        if (state.isConnected) "● LIVE" else "● OFFLINE",
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
                ConnectionCard(
                    state = state,
                    token = token,
                    onTokenChange = { token = it.trim() },
                    isConnecting = isConnecting,
                    selectedExpiry = selectedExpiry,
                    availableExpiries = availableExpiries,
                    expiryExpanded = expiryExpanded,
                    onExpiryExpanded = { expiryExpanded = it },
                    onExpirySelected = {
                        selectedExpiry = it
                        expiryExpanded = false
                        vm.connectLive(token, it)
                    },
                    onConnect = { discoverAndConnect() },
                    onDemo = vm::connectDemo,
                    onDisconnect = vm::disconnect,
                )
            }
            item { ModePanel(state, vm) }
            item { AccountAndSignal(state) }
            item { TradeControls(state, vm) }
            state.position?.let { item { PositionStrip(state, vm) } }
            if (state.appMode == AppMode.BACKTEST) {
                item {
                    BacktestPanel(
                        token = token,
                        index = state.index,
                        state = backtest,
                        onRun = { backtestVm.run(token, state.index) },
                        onCancel = backtestVm::cancel,
                        onClear = backtestVm::clearResult,
                    )
                }
            }
            item { Text(state.message, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)) }
            if (state.appMode == AppMode.LIVE_MARKET) item { OptionChainTable(state.optionChain) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: DashboardState,
    token: String,
    onTokenChange: (String) -> Unit,
    isConnecting: Boolean,
    selectedExpiry: String,
    availableExpiries: List<String>,
    expiryExpanded: Boolean,
    onExpiryExpanded: (Boolean) -> Unit,
    onExpirySelected: (String) -> Unit,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.isConnected) {
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Paste Upstox access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Used for live data and Upstox Plus historical research") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = token.isNotBlank() && !isConnecting) {
                        Text(if (isConnecting) "CONNECTING…" else "CONNECT LIVE")
                    }
                    OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f)) { Text("DEMO") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${state.index.name} connected", fontWeight = FontWeight.Bold)
                        Text("Expiry ${selectedExpiry.ifBlank { "automatic" }}", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(onClick = onDisconnect) { Text("DISCONNECT") }
                }
                if (availableExpiries.size > 1) {
                    Box {
                        OutlinedButton(onClick = { onExpiryExpanded(true) }, modifier = Modifier.fillMaxWidth()) { Text("Expiry: $selectedExpiry") }
                        DropdownMenu(expanded = expiryExpanded, onDismissRequest = { onExpiryExpanded(false) }) {
                            availableExpiries.forEach { expiry ->
                                DropdownMenuItem(text = { Text(expiry) }, onClick = { onExpirySelected(expiry) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePanel(state: DashboardState, vm: TradingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("NIFTY", state.index == MarketIndex.NIFTY, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.NIFTY) }
            Choice("SENSEX", state.index == MarketIndex.SENSEX, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.SENSEX) }
            Choice("LIVE", state.appMode == AppMode.LIVE_MARKET, Modifier.weight(1f)) { vm.setAppMode(AppMode.LIVE_MARKET) }
            Choice("BACKTEST", state.appMode == AppMode.BACKTEST, Modifier.weight(1f)) { vm.setAppMode(AppMode.BACKTEST) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Choice("MANUAL", state.tradingMode == TradingMode.MANUAL, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.MANUAL) }
            Choice("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
            Button(
                onClick = { vm.setLiveTradingEnabled(!state.liveTradingEnabled) },
                modifier = Modifier.weight(1.2f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.liveTradingEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                ),
            ) { Text(if (state.liveTradingEnabled) "LIVE ORDERS ON" else "LIVE ORDERS OFF", fontSize = 10.sp) }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false, modifier = modifier, contentPadding = PaddingValues(horizontal = 3.dp)) {
        Text(label, fontSize = 10.sp)
    } else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 3.dp)) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun AccountAndSignal(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Metric("CAPITAL", price(state.startingCapital), Modifier.weight(1f))
                Metric("EQUITY", price(state.equity), Modifier.weight(1f))
                Metric("P&L", price(state.realizedPnl + state.pnl), Modifier.weight(1f))
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.index.name, style = MaterialTheme.typography.labelMedium)
                    Text(price(state.spotPrice), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.End) {
                    val signalText = when (state.signal.action) {
                        SignalAction.BUY_CE -> "BUY CALL"
                        SignalAction.BUY_PE -> "BUY PUT"
                        SignalAction.WAIT -> "WAIT"
                    }
                    Text(signalText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("${state.signal.trend.name} · ${state.signal.confidence}/100", style = MaterialTheme.typography.labelMedium)
                    Text(state.signal.reasons.firstOrNull() ?: "Waiting", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, fontSize = 9.sp)
    Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}

@Composable
private fun TradeControls(state: DashboardState, vm: TradingViewModel) {
    if (state.appMode != AppMode.LIVE_MARKET) return
    if (state.tradingMode == TradingMode.MANUAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = vm::buyCe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("BUY CALL") }
            Button(onClick = vm::buyPe, modifier = Modifier.weight(1f), enabled = state.isConnected) { Text("BUY PUT") }
        }
    } else Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Text("AUTO MODE ARMED · entries and exits follow confirmed signals", Modifier.padding(9.dp), textAlign = TextAlign.Center, fontSize = 11.sp)
    }
}

@Composable
private fun PositionStrip(state: DashboardState, vm: TradingViewModel) {
    val p = state.position ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CURRENT TRADE", fontSize = 9.sp)
                Text("${p.strike.toInt()} ${p.side} ×${p.quantity}", fontWeight = FontWeight.Bold)
            }
            Text("${price(p.currentPrice)}\n${price(p.pnl)}", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp)
            TextButton(onClick = vm::exitPosition) { Text("EXIT") }
        }
    }
}

@Composable
private fun BacktestPanel(
    token: String,
    index: MarketIndex,
    state: BacktestViewModel.UiState,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("3-MONTH ${index.name} BACKTEST", fontWeight = FontWeight.Bold)
            Text(
                "Fetches Upstox Plus expired option contracts and one-minute historical candles, then replays the production entry, stop, target, reversal and cooldown rules.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.isRunning) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("${state.completed}/${state.total.coerceAtLeast(1)} · ${state.message}", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("CANCEL BACKTEST") }
            } else {
                Button(onClick = onRun, enabled = token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("FETCH 3 MONTHS & RUN")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            state.result?.let { result ->
                HorizontalDivider()
                Text("${result.fromDate} to ${result.toDate}", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth()) {
                    BacktestMetric("TRADES", result.report.trades.toString(), Modifier.weight(1f))
                    BacktestMetric("WIN RATE", percent(result.report.winRate), Modifier.weight(1f))
                    BacktestMetric("NET P&L", price(result.report.netPnl), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    BacktestMetric("PROFIT FACTOR", decimal(result.report.profitFactor), Modifier.weight(1f))
                    BacktestMetric("DRAWDOWN", price(result.report.maxDrawdown), Modifier.weight(1f))
                    BacktestMetric("PRECISION", percent(result.report.signalPrecision), Modifier.weight(1f))
                }
                Text(
                    "${result.expiries} expiries · ${result.contractsTested} contracts · ${result.candlesProcessed} candles · ${result.errors.size} errors",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (result.errors.isNotEmpty()) {
                    Text("First error: ${result.errors.first()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                }
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("CLEAR RESULTS") }
            }
        }
    }
}

@Composable
private fun BacktestMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.Center)
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
                Header("CE LTP", 1f)
                Header("CE OI/ΔOI", 1.2f)
                Header("STRIKE", .9f)
                Header("PE LTP", 1f)
                Header("PE OI/ΔOI", 1.2f)
            }
            if (rows.isEmpty()) Text("Connect live data to load the option chain", Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center)
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (row.atm) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .border(.25.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(vertical = 6.dp),
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

@Composable
private fun RowScope.Header(text: String, weight: Float) = Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 10.sp, fontWeight = FontWeight.Bold)

@Composable
private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false) = Text(
    text,
    Modifier.weight(weight),
    textAlign = TextAlign.Center,
    fontSize = 10.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
)

private fun compact(v: Long) = when {
    kotlin.math.abs(v) >= 100_000 -> "%.1fL".format(Locale.US, v / 100_000.0)
    kotlin.math.abs(v) >= 1_000 -> "%.1fK".format(Locale.US, v / 1_000.0)
    else -> v.toString()
}
private fun signed(v: Long) = (if (v > 0) "+" else "") + compact(v)
private fun price(v: Double) = "₹%,.2f".format(Locale.US, v)
private fun percent(v: Double) = "%.1f%%".format(Locale.US, v * 100.0)
private fun decimal(v: Double) = if (v.isInfinite()) "∞" else "%.2f".format(Locale.US, v)
