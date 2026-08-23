package com.parmod.ema

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parmod.ema.data.LocalCredentialVault
import com.parmod.ema.data.UpstoxOrderClient
import com.parmod.ema.data.UpstoxRecoveryClient
import com.parmod.ema.model.TradingRecoveryRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Broker reconciliation gate shown only when the previous Android process ended while
 * a VARDHANI LIVE position was still recorded as open. No new trading is enabled here.
 */
class LiveRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LiveRecoveryScreen()
                }
            }
        }
    }
}

@Composable
private fun LiveRecoveryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = remember(context) { LocalCredentialVault(context) }
    var token by remember { mutableStateOf(vault.read().upstoxAccessToken) }
    var records by remember { mutableStateOf(TradingRecoveryRegistry.startupOpenLivePositions()) }
    var brokerPositions by remember { mutableStateOf<Map<String, UpstoxRecoveryClient.Position>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember {
        mutableStateOf(
            if (records.isEmpty()) "Recovery ledger is clear" else "Recovered ${records.size} LIVE position(s) · broker verification required",
        )
    }

    fun refreshBroker() {
        if (busy) return
        token = vault.read().upstoxAccessToken
        if (token.isBlank()) {
            message = "Upstox access token is missing or expired · authenticate before recovery"
            return
        }
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { UpstoxRecoveryClient(token).getPositions() }
            }.onSuccess { positions ->
                brokerPositions = positions.filter { it.quantity != 0 }.associateBy { it.instrumentKey }
                records.forEach { record ->
                    if (record.instrumentKey.isNotBlank()) {
                        val broker = brokerPositions[record.instrumentKey]
                        if (broker == null || broker.quantity == 0) {
                            TradingRecoveryRegistry.markRecoveredResolved(record.key)
                        }
                    }
                }
                records = TradingRecoveryRegistry.startupOpenLivePositions()
                message = if (records.isEmpty()) {
                    "Broker reconciliation complete · no unresolved VARDHANI LIVE position"
                } else {
                    "Broker checked · ${records.size} recovered LIVE position(s) still require action"
                }
            }.onFailure { error ->
                message = "Broker verification failed: ${error.message?.take(180)}"
            }
            busy = false
        }
    }

    fun flatten(record: TradingRecoveryRegistry.Record) {
        if (busy) return
        token = vault.read().upstoxAccessToken
        if (token.isBlank()) {
            message = "Authenticate with Upstox before flattening the recovered position"
            return
        }
        val broker = brokerPositions[record.instrumentKey]
        if (broker == null || broker.quantity == 0) {
            TradingRecoveryRegistry.markRecoveredResolved(record.key)
            records = TradingRecoveryRegistry.startupOpenLivePositions()
            message = "Broker already flat for ${record.instrumentKey}"
            return
        }
        if (broker.quantity < 0) {
            message = "Unexpected short broker position for ${record.instrumentKey}; VARDHANI will not guess an offsetting order"
            return
        }
        val quantity = min(record.currentQuantity.coerceAtLeast(0), broker.quantity)
        if (quantity <= 0) {
            message = "Recovered quantity could not be verified"
            return
        }
        busy = true
        message = "Flattening recovered ${record.index.name} ${record.side.name} · $quantity qty…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = UpstoxOrderClient(token)
                    val placement = client.placeMarketOrder(
                        instrumentKey = record.instrumentKey,
                        quantity = quantity,
                        transactionType = UpstoxOrderClient.TransactionType.SELL,
                        tag = "VRD-REC-${record.index.name.take(3)}-${System.currentTimeMillis().toString().takeLast(8)}".take(40),
                    )
                    client.awaitExecution(placement, quantity)
                }
            }.onSuccess { execution ->
                if (execution.filledQuantity >= quantity) {
                    TradingRecoveryRegistry.markRecoveredResolved(record.key)
                    message = "Recovered position flattened · ${execution.filledQuantity} qty"
                } else {
                    message = "Recovery SELL partial ${execution.filledQuantity}/$quantity · position remains locked"
                }
                records = TradingRecoveryRegistry.startupOpenLivePositions()
                runCatching {
                    withContext(Dispatchers.IO) { UpstoxRecoveryClient(token).getPositions() }
                }.onSuccess { positions ->
                    brokerPositions = positions.filter { it.quantity != 0 }.associateBy { it.instrumentKey }
                }
            }.onFailure { error ->
                message = "Recovery SELL failed: ${error.message?.take(180)}"
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (records.isNotEmpty() && token.isNotBlank()) refreshBroker()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("VARDHANI LIVE RECOVERY", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "A previous app process ended with LIVE broker exposure recorded. New LIVE entries remain blocked until the broker position is reconciled.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(message, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = { refreshBroker() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(if (busy) "CHECKING…" else "REFRESH BROKER", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, OAuthLauncherActivity::class.java)) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("UPSTOX OAUTH", fontSize = 10.sp) }
                    }
                }
            }
        }

        records.forEach { record ->
            item {
                val broker = brokerPositions[record.instrumentKey]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "${record.index.name} · ${record.engineId.name} · ${record.side.name} ${record.strike.toInt()}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text("Recorded ${record.currentQuantity} qty · entry ₹${"%.2f".format(record.entryPrice)} · last ₹${"%.2f".format(record.currentPrice)}")
                        Text("Instrument ${record.instrumentKey.ifBlank { "UNAVAILABLE" }}", style = MaterialTheme.typography.labelSmall)
                        Text(
                            if (broker == null) {
                                "Broker position: not yet verified"
                            } else {
                                "Broker position: ${broker.quantity} qty · ${broker.tradingSymbol} · LTP ₹${"%.2f".format(broker.lastPrice)} · P&L ₹${"%.2f".format(broker.pnl)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Button(
                            onClick = { flatten(record) },
                            enabled = !busy && record.instrumentKey.isNotBlank() && broker != null && broker.quantity > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("FLATTEN RECOVERED VARDHANI QUANTITY")
                        }
                    }
                }
            }
        }

        item {
            if (records.isEmpty()) {
                Button(
                    onClick = {
                        context.startActivity(Intent(context, VardhaniFullActivity::class.java))
                        (context as? ComponentActivity)?.finish()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("OPEN VARDHANI") }
            } else {
                Text(
                    "The normal dashboard stays locked until every recovered LIVE record is broker-flat or explicitly reconciled by a successful recovery SELL.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
