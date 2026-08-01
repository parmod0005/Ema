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
    var loginExpanded by remember { mutableStateOf(!state.isConnected) }

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
                CompactConnectionPanel(
                    token = token,
                    expiry = expiry,
                    connected = state.isConnected,
                    expanded = loginExpanded,
                    onExpandedChange = { loginExpanded = it },
                    onTokenChange = { token = it },
                    onExpiryChange = { expiry = it },
                    onConnect = { vm.connectLive(token, expiry) },
                    onDemo = vm::connectDemo,
                    onDisconnect = vm::disconnect,
                )
            }
            item { CompactSelectors(state, vm) }
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
private fun CompactConnectionPanel(
    token: String,
    expiry: String,
    connected: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTokenChange: (String) -> Unit,
    onExpiryChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Upstox market data", fontWeight = FontWeight.Bold)
                    Text(if (connected) "Tick stream connected" else "Authentication required", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onExpandedChange(!expanded) }) { Text(if (expanded) "HIDE" else "LOGIN") }
            }
            if (expanded && !connected) {
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Access token") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = expiry,
                    onValueChange = onExpiryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Expiry (temporary fallback)") },
                    supportingText = { Text("Automatic expiry discovery is being integrated") },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!connected) {
                    Button(onClick = onConnect, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("CONNECT") }
                    OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("DEMO") }
                } else {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("DISCONNECT") }
                }
            }
        }
    }
}

@Composable
private fun CompactSelectors(state: DashboardState, vm: TradingViewModel) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 390.dp
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SegmentedButton("NIFTY", state.index == MarketIndex.NIFTY, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.NIFTY) }
                SegmentedButton("SENSEX", state.index == MarketIndex.SENSEX, Modifier.weight(1f)) { vm.selectIndex(MarketIndex.SENSEX) }
                SegmentedButton("PAPER", true, Modifier.weight(1f)) { vm.setExecutionMode(ExecutionMode.PAPER) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SegmentedButton(if (compact) "MAN" else "MANUAL", state.tradingMode == TradingMode.MANUAL, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.MANUAL) }
                SegmentedButton("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) { vm.setTradingMode(TradingMode.AUTO) }
                OutlinedButton(
                    onClick = { vm.setExecutionMode(ExecutionMode.LIVE) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(if (compact) "ORDERS OFF" else "LIVE ORDERS OFF", maxLines = 1, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun SegmentedButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false, modifier = modifier, contentPadding = PaddingValues(horizontal = 5.dp)) {
        Text(label, maxLines = 1, fontSize = 12.sp)
    } else OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 5.dp)) {
        Text(label, maxLines = 1, fontSize = 12.sp)
    }
}

@Composable
private fun SummaryAndSignal(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
            val compact = maxWidth < 380.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryBlock(state)
                    HorizontalDivider()
                    SignalBlock(state)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(0.9f)) { SummaryBlock(state) }
                    VerticalDivider(Modifier.height(86.dp))
                    Box(Modifier.weight(1.4f)) { SignalBlock(state) }
                }
            }
        }
    }
}

@Composable
private fun SummaryBlock(state: DashboardState) {
    Column {
        Text(state.index.name, style = MaterialTheme.typography.labelMedium)
        Text(formatPrice(state.spotPrice), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Paper P&L ${formatPrice(state.pnl)}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SignalBlock(state: DashboardState) {
    val signal = state.signal
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(signal.action.name.replace('_', ' '), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("${signal.trend.name} · ${signal.confidence}/100", style = MaterialTheme.typography.labelMedium)
        Text("E ${formatOptional(signal.entry)}  SL ${formatOptional(signal.stopLoss)}  T ${formatOptional(signal.target)}", style = MaterialTheme.typography.labelSmall)
        Text(signal.reasons.firstOrNull() ?: "Waiting", style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun TradeControls(state: DashboardState, vm: TradingViewModel) {
    if (state.tradingMode == TradingMode.MANUAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = vm::buyCe, modifier = Modifier.weight(1f), enabled = state.isConnected, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("PAPER BUY CE", maxLines = 1) }
            Button(onClick = vm::buyPe, modifier = Modifier.weight(1f), enabled = state.isConnected, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("PAPER BUY PE", maxLines = 1) }
        }
    } else {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = MaterialTheme.shapes.small) {
            Text("AUTO PAPER ARMED · minimum confidence 80", Modifier.padding(9.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PositionStrip(state: DashboardState, vm: TradingViewModel) {
    val p = state.position ?: return
    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.strike.toInt()} ${p.side} ×${p.quantity}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text("${formatPrice(p.currentPrice)}  ${formatPrice(p.pnl)}", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp)
            TextButton(onClick = vm::exitPosition) { Text("EXIT") }
        }
    }
}

private data class ChainRow(val strike: Double, val ce: OptionQuote?, val pe: OptionQuote?, val isAtm: Boolean)

@Composable
private fun OptionChainTable(options: List<OptionQuote>) {
    val rows = remember(options) {
        options.groupBy { it.strike }.toSortedMap().map { (strike, quotes) ->
            ChainRow(strike, quotes.firstOrNull { it.type == "CE" }, quotes.firstOrNull { it.type == "PE" }, quotes.any { it.isAtm })
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text("OPTION CHAIN · LIVE TICKS", Modifier.padding(10.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            ChainHeader()
            if (rows.isEmpty()) {
                Text("Connect live data to load nearest-expiry contracts", Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            } else rows.forEach { ChainTableRow(it) }
        }
    }
}

@Composable
private fun ChainHeader() {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 6.dp)) {
        HeaderCell("CE\nLTP", 1f)
        HeaderCell("OI / ΔOI\nΔ / Γ", 1.25f)
        HeaderCell("STRIKE", 0.9f)
        HeaderCell("PE\nLTP", 1f)
        HeaderCell("OI / ΔOI\nΔ / Γ", 1.25f)
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun ChainTableRow(row: ChainRow) {
    val background = if (row.isAtm) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(background).border(0.25.dp, MaterialTheme.colorScheme.outlineVariant).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuotePriceCell(row.ce, 1f)
        QuoteDetailCell(row.ce, 1.25f)
        Text(
            row.strike.toInt().toString() + if (row.isAtm) "\nATM" else "",
            Modifier.weight(0.9f),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            lineHeight = 12.sp,
        )
        QuotePriceCell(row.pe, 1f)
        QuoteDetailCell(row.pe, 1.25f)
    }
}

@Composable
private fun RowScope.QuotePriceCell(q: OptionQuote?, weight: Float) {
    Text(q?.let { "%.2f".format(Locale.US, it.ltp) } ?: "—", Modifier.weight(weight), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
}

@Composable
private fun RowScope.QuoteDetailCell(q: OptionQuote?, weight: Float) {
    val text = q?.let { "${compactLong(it.openInterest)} / ${signedLong(it.changeInOpenInterest)}\n${"%.2f".format(Locale.US, it.delta)} / ${"%.4f".format(Locale.US, it.gamma)}" } ?: "—\n—"
    Text(text, Modifier.weight(weight), textAlign = TextAlign.Center, fontSize = 9.sp, lineHeight = 11.sp)
}

private fun compactLong(value: Long): String = when {
    kotlin.math.abs(value) >= 100_000 -> String.format(Locale.US, "%.1fL", value / 100_000.0)
    kotlin.math.abs(value) >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
private fun signedLong(value: Long): String = (if (value > 0) "+" else "") + compactLong(value)
private fun formatPrice(value: Double): String = String.format(Locale.US, "₹%,.2f", value)
private fun formatOptional(value: Double?): String = value?.let(::formatPrice) ?: "—"
