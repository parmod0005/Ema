package com.parmod.ema

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.model.DashboardState
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.TradingMode
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { EmaApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmaApp(vm: TradingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMA Options") },
                actions = {
                    Text(
                        if (state.isConnected) "CONNECTED" else "OFFLINE",
                        modifier = Modifier.padding(end = 16.dp),
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ConnectionRow(state, vm) }
            item { SelectorRow(state, vm) }
            item { MarketSummary(state) }
            item { SignalCard(state) }
            item { TradeControls(state, vm) }
            state.position?.let { item { PositionCard(state, vm) } }
            item { Text(state.message, style = MaterialTheme.typography.bodySmall) }
            item { Text("Option chain · 5 ITM + ATM + 5 OTM", fontWeight = FontWeight.Bold) }
            items(state.optionChain) { quote -> OptionRow(quote) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ConnectionRow(state: DashboardState, vm: TradingViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = vm::connectDemo, enabled = !state.isConnected) { Text("DEMO") }
        OutlinedButton(onClick = vm::disconnect, enabled = state.isConnected) { Text("DISCONNECT") }
        OutlinedButton(onClick = { vm.setExecutionMode(ExecutionMode.LIVE) }) { Text("UPSTOX LIVE") }
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
            ToggleButton("PAPER", state.executionMode == ExecutionMode.PAPER) { vm.setExecutionMode(ExecutionMode.PAPER) }
            ToggleButton("LIVE", state.executionMode == ExecutionMode.LIVE) { vm.setExecutionMode(ExecutionMode.LIVE) }
        }
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = {}, enabled = false) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun MarketSummary(state: DashboardState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.index.name, style = MaterialTheme.typography.labelLarge)
                Text(formatPrice(state.spotPrice), style = MaterialTheme.typography.headlineMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("PAPER P&L", style = MaterialTheme.typography.labelLarge)
                Text(formatPrice(state.pnl), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun SignalCard(state: DashboardState) {
    val signal = state.signal
    Card(modifier = Modifier.fillMaxWidth()) {
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::buyCe, modifier = Modifier.weight(1f)) { Text("BUY ATM CE") }
            Button(onClick = vm::buyPe, modifier = Modifier.weight(1f)) { Text("BUY ATM PE") }
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text("AUTO PAPER armed · trades only at confidence ≥80", modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun PositionCard(state: DashboardState, vm: TradingViewModel) {
    val position = state.position ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${position.strike.toInt()} ${position.side} × ${position.quantity}", fontWeight = FontWeight.Bold)
                Text("Entry ${formatPrice(position.entryPrice)} · LTP ${formatPrice(position.currentPrice)}")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPrice(position.pnl), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = vm::exitPosition) { Text("EXIT") }
            }
        }
    }
}

@Composable
private fun OptionRow(quote: OptionQuote) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${quote.strike.toInt()} ${quote.type}${if (quote.isAtm) " · ATM" else ""}", fontWeight = FontWeight.Bold)
                Text("LTP ${formatPrice(quote.ltp)}")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("OI ${quote.openInterest} · ΔOI ${quote.changeInOpenInterest}")
                Text("Δ ${formatDecimal(quote.delta)} · Γ ${formatDecimal(quote.gamma)}")
            }
        }
    }
}

private fun formatPrice(value: Double): String = String.format(Locale.US, "₹%,.2f", value)
private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun formatOptional(value: Double?): String = value?.let(::formatPrice) ?: "—"
