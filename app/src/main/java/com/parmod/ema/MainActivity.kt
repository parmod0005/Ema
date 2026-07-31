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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                Surface(modifier = Modifier.fillMaxSize()) {
                    EmaApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmaApp() {
    var state by remember { mutableStateOf(previewState()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMA Options") },
                actions = {
                    Text(
                        text = if (state.isConnected) "LIVE" else "OFFLINE",
                        modifier = Modifier.padding(end = 16.dp),
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                SelectorRow(
                    state = state,
                    onIndexChange = { state = state.copy(index = it) },
                    onTradingModeChange = { state = state.copy(tradingMode = it) },
                    onExecutionModeChange = { state = state.copy(executionMode = it) },
                )
            }
            item { MarketSummary(state) }
            item { SignalCard(state) }
            item {
                Text(
                    text = "Option chain",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(state.optionChain) { quote -> OptionRow(quote) }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun SelectorRow(
    state: DashboardState,
    onIndexChange: (MarketIndex) -> Unit,
    onTradingModeChange: (TradingMode) -> Unit,
    onExecutionModeChange: (ExecutionMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton("NIFTY", state.index == MarketIndex.NIFTY) { onIndexChange(MarketIndex.NIFTY) }
            ToggleButton("SENSEX", state.index == MarketIndex.SENSEX) { onIndexChange(MarketIndex.SENSEX) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleButton("MANUAL", state.tradingMode == TradingMode.MANUAL) { onTradingModeChange(TradingMode.MANUAL) }
            ToggleButton("AUTO", state.tradingMode == TradingMode.AUTO) { onTradingModeChange(TradingMode.AUTO) }
            ToggleButton("PAPER", state.executionMode == ExecutionMode.PAPER) { onExecutionModeChange(ExecutionMode.PAPER) }
            ToggleButton("LIVE", state.executionMode == ExecutionMode.LIVE) { onExecutionModeChange(ExecutionMode.LIVE) }
        }
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !selected) { Text(label) }
}

@Composable
private fun MarketSummary(state: DashboardState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(state.index.name, style = MaterialTheme.typography.labelLarge)
                Text(formatPrice(state.spotPrice), style = MaterialTheme.typography.headlineMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("P&L", style = MaterialTheme.typography.labelLarge)
                Text(formatPrice(state.pnl), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun SignalCard(state: DashboardState) {
    val signal = state.signal
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(signal.action.name.replace('_', ' '), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${signal.trend.name} · Confidence ${signal.confidence}/100")
            Text("Entry ${formatOptional(signal.entry)}   SL ${formatOptional(signal.stopLoss)}   Target ${formatOptional(signal.target)}")
            signal.reasons.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun OptionRow(quote: OptionQuote) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "${quote.strike.toInt()} ${quote.type}${if (quote.isAtm) " · ATM" else ""}",
                    fontWeight = FontWeight.Bold,
                )
                Text("LTP ${formatPrice(quote.ltp)}")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("OI ${quote.openInterest}  ΔOI ${quote.changeInOpenInterest}")
                Text("Δ ${formatDecimal(quote.delta)}  Γ ${formatDecimal(quote.gamma)}")
            }
        }
    }
}

private fun formatPrice(value: Double): String = String.format(Locale.US, "₹%,.2f", value)
private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.4f", value)
private fun formatOptional(value: Double?): String = value?.let(::formatPrice) ?: "—"

private fun previewState(): DashboardState {
    val strikes = (24300..24800 step 50).flatMap { strike ->
        listOf(
            OptionQuote(strike.toDouble(), "CE", 120.0, 100_000L, 2_500L, 0.52, 0.0018, strike == 24550),
            OptionQuote(strike.toDouble(), "PE", 115.0, 95_000L, -1_200L, -0.48, 0.0019, strike == 24550),
        )
    }
    return DashboardState(
        spotPrice = 24_552.35,
        optionChain = strikes,
    )
}
