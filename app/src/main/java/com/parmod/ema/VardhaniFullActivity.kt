package com.parmod.ema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.model.EngineId
import com.parmod.ema.model.EngineState
import com.parmod.ema.model.EngineTimeframeConfig
import com.parmod.ema.model.ExecutionMode
import com.parmod.ema.model.FullDashboardState
import com.parmod.ema.model.FullMarketState
import com.parmod.ema.model.LiveArmMode
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.model.MarketSelection
import com.parmod.ema.model.OptionQuote
import com.parmod.ema.model.PositionSide
import com.parmod.ema.model.SignalTimeframe
import com.parmod.ema.model.TradeLogEntry
import com.parmod.ema.model.TradeStatus
import com.parmod.ema.model.TradingMode
import com.parmod.ema.service.VardhaniMarketService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class VardhaniFullActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FullVardhaniApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun FullVardhaniApp(vm: VardhaniFullViewModel = viewModel()) {
    val sampled = remember(vm) { vm.state.sample(200L) }
    val state by sampled.collectAsState(initial = vm.state.value)
    val context = LocalContext.current
    val vault = remember(context) { LocalCredentialVault(context) }
    var token by remember { mutableStateOf(vault.read().upstoxAccessToken) }
    var requestedArm by remember { mutableStateOf<LiveArmMode?>(null) }

    fun startBackground() {
        val intent = Intent(context, VardhaniMarketService::class.java).apply {
            action = VardhaniMarketService.ACTION_START
            putExtra(VardhaniMarketService.EXTRA_INDEX, state.marketSelection.name)
            putExtra(
                VardhaniMarketService.EXTRA_EXPIRY,
                state.visibleMarkets.joinToString(" | ") { "${it.index.name}:${it.expiry.ifBlank { "AUTO" }}" },
            )
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopBackground() {
        context.startService(
            Intent(context, VardhaniMarketService::class.java)
                .setAction(VardhaniMarketService.ACTION_STOP),
        )
    }

    if (requestedArm != null) {
        val arm = requestedArm!!
        AlertDialog(
            onDismissRequest = { requestedArm = null },
            title = {
                Text(if (arm == LiveArmMode.AUTO_ARMED) "Arm automatic LIVE orders?" else "Arm manual LIVE orders?")
            },
            text = {
                Text(
                    if (arm == LiveArmMode.AUTO_ARMED) {
                        "This enables real Upstox BUY/SELL orders from selected automatic engines after all live risk gates pass. NIFTY/SENSEX/BOTH selection, lots and risk limits below will be used."
                    } else {
                        "This enables real Upstox orders only when you press a manual CE/PE button. Automatic strategies remain unable to place live orders."
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.armLive(arm)
                    requestedArm = null
                    startBackground()
                }) { Text("CONFIRM LIVE ARM") }
            },
            dismissButton = {
                TextButton(onClick = { requestedArm = null }) { Text("CANCEL") }
            },
        )
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
                            Text("FULL DUAL-MARKET TRADING + AI", fontSize = 10.sp)
                        }
                    }
                },
                actions = {
                    Text(
                        when {
                            state.emergencyKill -> "● KILL"
                            state.executionMode == ExecutionMode.LIVE && state.liveArmMode != LiveArmMode.DISARMED -> "● LIVE ARMED"
                            state.executionMode == ExecutionMode.LIVE -> "● LIVE DISARMED"
                            else -> "● PAPER"
                        },
                        color = when {
                            state.emergencyKill -> MaterialTheme.colorScheme.error
                            state.executionMode == ExecutionMode.LIVE && state.liveArmMode != LiveArmMode.DISARMED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FullConnectionCard(
                    state = state,
                    token = token,
                    onToken = { token = it.trim() },
                    onConnect = {
                        if (token.isNotBlank()) {
                            vault.updateUpstoxAccessToken(token)
                            vm.connectUpstox(token)
                            startBackground()
                        }
                    },
                    onDemo = {
                        vm.connectDemo()
                        startBackground()
                    },
                    onDisconnect = {
                        val hasLive = state.markets.values.any { market ->
                            market.engines.any { it.position?.executionMode == ExecutionMode.LIVE }
                        }
                        vm.disconnectAll()
                        if (!hasLive) stopBackground()
                    },
                )
            }
            item {
                UpstoxCredentialsPanel { saved ->
                    if (saved.isNotBlank()) token = saved.trim()
                }
            }
            item { ProductModeCard(state, vm, onArm = { requestedArm = it }) }
            item { EngineConfigCard(state, vm) }
            item { RiskAndLotCard(state, vm) }
            item { ToolCard() }
            item { CombinedStatusCard(state) }

            state.visibleMarkets.forEach { market ->
                item {
                    MarketHeaderCard(
                        market = market,
                        selectedLots = state.lotsFor(market.index),
                        onExpiry = { vm.setExpiry(market.index, it) },
                    )
                }
                item { EngineRuntimeCard(market, market.engine1, state, vm) }
                item { EngineRuntimeCard(market, market.engine2, state, vm) }
                item { EngineRuntimeCard(market, market.engine3, state, vm) }
                item { OptionChainCard(market) }
                item { TradeLogCard(market) }
            }

            item {
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(4.dp),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FullConnectionCard(
    state: FullDashboardState,
    token: String,
    onToken: (String) -> Unit,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state.visibleMarkets.count { it.isConnected }
    Card(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UPSTOX CONNECTION", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            OutlinedTextField(
                value = token,
                onValueChange = onToken,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Upstox access token") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "Selected ${state.marketSelection.name} · connected $connected/${state.visibleMarkets.size}",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onConnect, modifier = Modifier.weight(1f), enabled = token.isNotBlank()) {
                    Text("CONNECT UPSTOX", fontSize = 10.sp)
                }
                OutlinedButton(onClick = onDemo, modifier = Modifier.weight(1f)) {
                    Text("DEMO PAPER", fontSize = 10.sp)
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Text("DISCONNECT", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun ProductModeCard(
    state: FullDashboardState,
    vm: VardhaniFullViewModel,
    onArm: (LiveArmMode) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("MARKET + EXECUTION MODE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChoiceButton("NIFTY", state.marketSelection == MarketSelection.NIFTY, Modifier.weight(1f)) {
                    vm.setMarketSelection(MarketSelection.NIFTY)
                }
                ChoiceButton("SENSEX", state.marketSelection == MarketSelection.SENSEX, Modifier.weight(1f)) {
                    vm.setMarketSelection(MarketSelection.SENSEX)
                }
                ChoiceButton("BOTH", state.marketSelection == MarketSelection.BOTH, Modifier.weight(1f)) {
                    vm.setMarketSelection(MarketSelection.BOTH)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChoiceButton("MANUAL", state.tradingMode == TradingMode.MANUAL, Modifier.weight(1f)) {
                    vm.setTradingMode(TradingMode.MANUAL)
                }
                ChoiceButton("AUTO", state.tradingMode == TradingMode.AUTO, Modifier.weight(1f)) {
                    vm.setTradingMode(TradingMode.AUTO)
                }
                ChoiceButton("PAPER", state.executionMode == ExecutionMode.PAPER, Modifier.weight(1f)) {
                    vm.setExecutionMode(ExecutionMode.PAPER)
                }
                ChoiceButton("LIVE", state.executionMode == ExecutionMode.LIVE, Modifier.weight(1f)) {
                    vm.setExecutionMode(ExecutionMode.LIVE)
                }
            }
            if (state.executionMode == ExecutionMode.LIVE) {
                Text(
                    "LIVE authority: ${state.liveArmMode.name.replace('_', ' ')} · default is DISARMED",
                    color = if (state.liveArmMode == LiveArmMode.DISARMED) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ChoiceButton("DISARM", state.liveArmMode == LiveArmMode.DISARMED, Modifier.weight(1f)) {
                        vm.armLive(LiveArmMode.DISARMED)
                    }
                    OutlinedButton(onClick = { onArm(LiveArmMode.MANUAL_ONLY) }, modifier = Modifier.weight(1f)) {
                        Text("ARM MANUAL", fontSize = 9.sp)
                    }
                    Button(onClick = { onArm(LiveArmMode.AUTO_ARMED) }, modifier = Modifier.weight(1f)) {
                        Text("ARM AUTO", fontSize = 9.sp)
                    }
                }
            }
            if (state.emergencyKill) {
                Button(onClick = { vm.setEmergencyKill(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("RESET EMERGENCY KILL · LIVE STAYS DISARMED")
                }
            } else {
                OutlinedButton(onClick = { vm.setEmergencyKill(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("EMERGENCY KILL / FLATTEN", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EngineConfigCard(state: FullDashboardState, vm: VardhaniFullViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("ENGINE CONFIGURATION", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            EngineConfigRow("E1 TREND", EngineId.ENGINE_1_TREND, state, vm, editableTimeframe = true)
            Divider()
            EngineConfigRow("E2 AVWAP + LIQUIDITY + D30", EngineId.ENGINE_2_AVWAP_LIQUIDITY, state, vm, editableTimeframe = true)
            Divider()
            EngineConfigRow("E3 V7.6 REVERSAL RUNNER", EngineId.ENGINE_3_V76_SCALPER, state, vm, editableTimeframe = false)
        }
    }
}

@Composable
private fun EngineConfigRow(
    label: String,
    id: EngineId,
    state: FullDashboardState,
    vm: VardhaniFullViewModel,
    editableTimeframe: Boolean,
) {
    val enabled = id in state.enabledEngines
    val config = state.engineTimeframes.getValue(id)
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            if (enabled) {
                Button(onClick = { vm.toggleEngine(id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("✓ ON", fontSize = 9.sp)
                }
            } else {
                OutlinedButton(onClick = { vm.toggleEngine(id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("OFF", fontSize = 9.sp)
                }
            }
        }
        if (editableTimeframe) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Trigger / Setup / Bias", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { expanded = true }) {
                        Text("${config.trigger.label} / ${config.setup.label} / ${config.bias.label}", fontSize = 10.sp)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        validTimeframeConfigs().forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text("${candidate.trigger.label} / ${candidate.setup.label} / ${candidate.bias.label}") },
                                onClick = {
                                    expanded = false
                                    vm.setTimeframes(id, candidate)
                                },
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                "Timeframe locked to exact V7.6: ${config.trigger.label} trigger · ${config.setup.label} setup · ${config.bias.label} bias",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun validTimeframeConfigs(): List<EngineTimeframeConfig> = SignalTimeframe.entries.flatMap { trigger ->
    SignalTimeframe.entries.flatMap { setup ->
        SignalTimeframe.entries.mapNotNull { bias ->
            if (trigger.minutes <= setup.minutes && setup.minutes <= bias.minutes) {
                EngineTimeframeConfig(trigger, setup, bias)
            } else {
                null
            }
        }
    }
}

@Composable
private fun RiskAndLotCard(state: FullDashboardState, vm: VardhaniFullViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("LOTS + RISK CONTROLS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            NumberControl("NIFTY lots", state.niftyLots.toDouble(), 1.0, 1.0, state.riskConfig.maxLotsPerOrder.toDouble()) {
                vm.setLots(MarketIndex.NIFTY, it.toInt())
            }
            NumberControl("SENSEX lots", state.sensexLots.toDouble(), 1.0, 1.0, state.riskConfig.maxLotsPerOrder.toDouble()) {
                vm.setLots(MarketIndex.SENSEX, it.toInt())
            }
            NumberControl("Max trades / index / day", state.riskConfig.maxTradesPerIndex.toDouble(), 1.0, 1.0, 100.0) {
                vm.setDailyTradeLimit(it.toInt())
            }
            NumberControl("Daily loss lock ₹", state.riskConfig.dailyLossLimitInr, 500.0, 500.0, 100_000.0) {
                vm.setDailyLossLimit(it)
            }
            NumberControl("Max risk / LIVE trade ₹", state.riskConfig.maxRiskPerTradeInr, 500.0, 500.0, 100_000.0) {
                vm.setMaxRiskPerTrade(it)
            }
            NumberControl("AUTO LIVE min confidence", state.riskConfig.minimumAutoLiveConfidence.toDouble(), 5.0, 50.0, 100.0) {
                vm.setMinimumAutoLiveConfidence(it.toInt())
            }
            Text(
                "Live spread limit ${"%.1f".format(state.riskConfig.maximumSpreadPercent)}% · max tick age ${state.riskConfig.maximumTickAgeMillis} ms",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NumberControl(
    label: String,
    value: Double,
    step: Double,
    minimum: Double,
    maximum: Double,
    onChange: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 11.sp)
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(minimum)) },
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) { Text("−") }
        Text(
            if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value),
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold,
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(maximum)) },
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) { Text("+") }
    }
}

@Composable
private fun ToolCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI + DATA + RESEARCH", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(onClick = { context.startActivity(Intent(context, MetaBrainLabActivity::class.java)) }) {
                    Text("AI TRAINING CENTER", fontSize = 9.sp)
                }
                OutlinedButton(onClick = { context.startActivity(Intent(context, HistoricalDataActivity::class.java)) }) {
                    Text("HISTORICAL DATA", fontSize = 9.sp)
                }
                OutlinedButton(onClick = { context.startActivity(Intent(context, ResearchArchiveActivity::class.java)) }) {
                    Text("RESEARCH ARCHIVE", fontSize = 9.sp)
                }
                OutlinedButton(onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }) {
                    Text("BACKTEST / LEGACY TOOLS", fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun CombinedStatusCard(state: FullDashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("SESSION SUMMARY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                "${state.marketSelection.name} · ${state.tradingMode.name} · ${state.executionMode.name} · ${state.liveArmMode.name.replace('_', ' ')}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
            Text(
                "Realized ₹${money(state.combinedRealizedPnl)} · Open ₹${money(state.combinedOpenPnl)} · Total ₹${money(state.combinedPnl)}",
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MarketHeaderCard(
    market: FullMarketState,
    selectedLots: Int,
    onExpiry: (String) -> Unit,
) {
    var expanded by remember(market.index) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(market.index.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "Spot ${money(market.spotPrice)} · lots $selectedLots · ${if (market.isConnected) "CONNECTED" else "OFFLINE"}",
                        fontSize = 11.sp,
                    )
                }
                Text(
                    if (market.riskLocked) "RISK LOCK" else "D${market.marketDepthLevels} ${market.marketDepthMode}",
                    color = if (market.riskLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
            if (market.availableExpiries.size > 1) {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Expiry: ${market.expiry}")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        market.availableExpiries.forEach { expiry ->
                            DropdownMenuItem(
                                text = { Text(expiry) },
                                onClick = {
                                    expanded = false
                                    onExpiry(expiry)
                                },
                            )
                        }
                    }
                }
            } else {
                Text("Expiry ${market.expiry.ifBlank { "automatic" }}", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "Ticks ${market.ticksReceived} · realized ₹${money(market.realizedPnl)} · open ₹${money(market.openPnl)} · ${market.riskReason}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(market.message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EngineRuntimeCard(
    market: FullMarketState,
    engine: EngineState,
    state: FullDashboardState,
    vm: VardhaniFullViewModel,
) {
    val enabled = engine.id in state.enabledEngines
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(engine.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(if (enabled) "ON" else "OFF", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            Text(
                "${engine.signal.action.name} · confidence ${engine.signal.confidence}% · ${engine.signal.trend.name}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
            Text(engine.signal.setup, style = MaterialTheme.typography.labelSmall)
            engine.signal.reasons.takeLast(3).forEach {
                Text("• $it", style = MaterialTheme.typography.labelSmall)
            }

            val p = engine.position
            if (p != null) {
                Divider()
                Text(
                    "${p.executionMode.name} ${p.side.name} ${p.strike.toInt()} · ${p.quantity} qty · ${p.lots} lots",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
                Text(
                    "Entry ${money(p.entryPrice)} · Current ${money(p.currentPrice)} · SL ${money(p.stopPrice)} · T1 ${money(p.targetPrice)}",
                    fontSize = 10.sp,
                )
                Text(
                    "P&L ₹${money(p.pnl)}${if (p.target1Hit) " · RUNNER" else ""}${if (p.trailingActive) " · TRAILING" else ""}",
                    fontWeight = FontWeight.Bold,
                    color = if (p.pnl >= 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
                OutlinedButton(
                    onClick = { vm.manualExit(market.index, engine.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (p.executionMode == ExecutionMode.LIVE) "EXIT LIVE POSITION" else "EXIT PAPER POSITION")
                }
            } else if (state.tradingMode == TradingMode.MANUAL && enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { vm.manualBuy(market.index, engine.id, PositionSide.CE) },
                        modifier = Modifier.weight(1f),
                    ) { Text("BUY CE", fontSize = 10.sp) }
                    Button(
                        onClick = { vm.manualBuy(market.index, engine.id, PositionSide.PE) },
                        modifier = Modifier.weight(1f),
                    ) { Text("BUY PE", fontSize = 10.sp) }
                }
            }
            Text(
                "Trades ${engine.performance.trades} · WR ${"%.1f".format(engine.performance.winRate)}% · PF ${formatPf(engine.performance.profitFactor)} · Net ₹${money(engine.performance.realizedPnl)}",
                style = MaterialTheme.typography.labelSmall,
            )
            Text(engine.message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OptionChainCard(market: FullMarketState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${market.index.name} LIVE OPTION CHAIN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("ATM ±5 strikes · LTP / bid-ask / OI / ΔOI / delta / lot", style = MaterialTheme.typography.labelSmall)
            market.optionChain
                .groupBy { it.strike }
                .toSortedMap()
                .forEach { (strike, quotes) ->
                    val ce = quotes.firstOrNull { it.type == "CE" }
                    val pe = quotes.firstOrNull { it.type == "PE" }
                    OptionStrikeRow(strike, ce, pe)
                }
        }
    }
}

@Composable
private fun OptionStrikeRow(strike: Double, ce: OptionQuote?, pe: OptionQuote?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ce?.let {
                "CE ${money(it.ltp)}\n${money(it.bid)}/${money(it.ask)}\nOI ${compact(it.openInterest)} Δ ${"%.2f".format(it.delta)}"
            } ?: "—",
            modifier = Modifier.weight(1f),
            fontSize = 9.sp,
            textAlign = TextAlign.Start,
        )
        Text(
            "${strike.toInt()}${if (ce?.isAtm == true || pe?.isAtm == true) " ATM" else ""}",
            modifier = Modifier.width(92.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            pe?.let {
                "PE ${money(it.ltp)}\n${money(it.bid)}/${money(it.ask)}\nOI ${compact(it.openInterest)} Δ ${"%.2f".format(it.delta)}"
            } ?: "—",
            modifier = Modifier.weight(1f),
            fontSize = 9.sp,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun TradeLogCard(market: FullMarketState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${market.index.name} TRADE LOG", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (market.tradeLog.isEmpty()) {
                Text("No trades yet", style = MaterialTheme.typography.labelSmall)
            } else {
                market.tradeLog.takeLast(12).reversed().forEach { trade -> TradeRow(trade) }
            }
        }
    }
}

@Composable
private fun TradeRow(trade: TradeLogEntry) {
    val time = Instant.ofEpochMilli(trade.entryTimeMillis)
        .atZone(ZoneId.of("Asia/Kolkata"))
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    Text(
        "$time · ${trade.executionMode.name} · ${trade.engineId.name.removePrefix("ENGINE_")} · ${trade.side.name} ${trade.strike.toInt()} · ${trade.quantity} qty · " +
            if (trade.status == TradeStatus.CLOSED) {
                "CLOSED ₹${money(trade.pnl ?: 0.0)} ${trade.exitReason}"
            } else {
                "OPEN @ ${money(trade.entryPrice)}"
            },
        fontSize = 9.sp,
    )
}

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(label, fontSize = 9.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(label, fontSize = 9.sp)
        }
    }
}

private fun money(value: Double): String = String.format(Locale.US, "%,.2f", value)
private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
    else -> value.toString()
}
private fun formatPf(value: Double): String = if (value.isInfinite()) "∞" else "%.2f".format(value)
