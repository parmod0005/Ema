package com.parmod.ema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.training.HistoricalCorpusDownloadManager
import com.parmod.ema.training.HistoricalCorpusTrainingViewModel
import com.parmod.ema.training.HistoricalMarketScope
import com.parmod.ema.training.PrelabelledTrainingWindowPlan

class HistoricalDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    HistoricalDataScreen(
                        openAiLab = {
                            startActivity(Intent(this, MetaBrainLabActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoricalDataScreen(
    vm: HistoricalCorpusTrainingViewModel = viewModel(),
    openAiLab: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val corpus = state.downloadedSummary
    val busy = state.isDownloading || state.isRunning || state.isImporting

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("VARDHANI HISTORICAL DATA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Persistent read-only Upstox expired-option downloader · NIFTY + SENSEX · 1-minute CE/PE candles · resumable phone corpus",
            style = MaterialTheme.typography.labelMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DOWNLOADED CORPUS", fontWeight = FontWeight.Bold)
                Row {
                    DownloadMetric("Contracts", corpus.optionContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("Rows", corpus.rowsAccepted.toString(), Modifier.weight(1f))
                    DownloadMetric("Deduped", corpus.duplicatesRemoved.toString(), Modifier.weight(1f))
                }
                Row {
                    DownloadMetric("NIFTY", corpus.niftyContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("SENSEX", corpus.sensexContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("CE / PE", "${corpus.ceContracts}/${corpus.peContracts}", Modifier.weight(1f))
                }
                Text(
                    "Coverage ${corpus.fromDate ?: "--"} → ${corpus.toDate ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    if (corpus.bothMarketsPresent) "✓ BOTH-MARKET HISTORICAL CORPUS READY" else "Download BOTH to enable downloaded NIFTY + SENSEX joint training",
                    color = if (corpus.bothMarketsPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DOWNLOAD CONFIGURATION", fontWeight = FontWeight.Bold)
                Text("MARKET", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoricalMarketScope.entries.forEach { scope ->
                        DownloadChoice(scope.label, state.selectedMarketScope == scope, Modifier.weight(1f), !busy) { vm.selectMarketScope(scope) }
                    }
                }

                Text("HISTORY WINDOW", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 3, 6, 12, PrelabelledTrainingWindowPlan.FULL).forEach { months ->
                        DownloadChoice(
                            PrelabelledTrainingWindowPlan.label(months),
                            state.selectedMonths == months,
                            Modifier.weight(1f),
                            !busy,
                        ) { vm.selectMonths(months) }
                    }
                }

                Text("OPTION STRIKE DENSITY / EXPIRY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII.sorted().forEach { radius ->
                        val strikes = radius * 2 + 1
                        DownloadChoice(
                            "$strikes STRIKES",
                            state.downloadStrikeRadius == radius,
                            Modifier.weight(1f),
                            !busy,
                        ) { vm.selectDownloadStrikeRadius(radius) }
                    }
                }
                Text(
                    "Each selected strike downloads both available CE and PE contracts. 11 strikes/expiry is the balanced default; 21 is broader but takes substantially more time/storage.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DOWNLOAD / RESUME", fontWeight = FontWeight.Bold)
                if (state.isDownloading) {
                    LinearProgressIndicator({ state.downloadProgress }, Modifier.fillMaxWidth())
                    Text(
                        "${state.downloadStage} · ${state.downloadCompleted}/${state.downloadTotal.coerceAtLeast(1)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                    Text(state.downloadMessage, style = MaterialTheme.typography.labelSmall)
                    Row {
                        DownloadMetric("Cache", state.downloadCacheHits.toString(), Modifier.weight(1f))
                        DownloadMetric("Network", state.downloadNetworkRequests.toString(), Modifier.weight(1f))
                    }
                    OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("STOP DOWNLOAD SAFELY") }
                } else {
                    Button(vm::downloadHistoricalCorpus, Modifier.fillMaxWidth(), enabled = !busy) {
                        Text("DOWNLOAD / RESUME ${state.selectedMarketScope.label} ${state.windowLabel}")
                    }
                    Text(state.downloadMessage, style = MaterialTheme.typography.labelSmall)
                    Row {
                        DownloadMetric("Cache", state.downloadCacheHits.toString(), Modifier.weight(1f))
                        DownloadMetric("Network", state.downloadNetworkRequests.toString(), Modifier.weight(1f))
                        DownloadMetric("Errors", state.downloadErrors.toString(), Modifier.weight(1f))
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRAINING HANDOFF", fontWeight = FontWeight.Bold)
                Text(
                    "After download, AI LAB can use source DOWNLOADED for NIFTY, SENSEX or BOTH. Training reuses the saved phone corpus without another network download. Production remains frozen unless normal governance and manual promotion pass.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(openAiLab, Modifier.fillMaxWidth(), enabled = !state.isDownloading) { Text("OPEN AI LAB") }
                OutlinedButton(vm::refreshLocalSummary, Modifier.fillMaxWidth(), enabled = !busy) { Text("REFRESH CORPUS COUNTS") }
                OutlinedButton(
                    vm::clearDownloadedCorpus,
                    Modifier.fillMaxWidth(),
                    enabled = !busy && corpus.optionContracts > 0,
                ) { Text("CLEAR DOWNLOADED HISTORICAL DATA") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DATA SAFETY", fontWeight = FontWeight.Bold)
                Text("• Download is read-only; no broker order endpoint is involved.", style = MaterialTheme.typography.bodySmall)
                Text("• Each contract is written atomically and deduplicated by candle timestamp.", style = MaterialTheme.typography.bodySmall)
                Text("• A request is marked complete only after its local contract file verifies successfully.", style = MaterialTheme.typography.bodySmall)
                Text("• Interrupted downloads retain completed contracts and resume by skipping verified requests.", style = MaterialTheme.typography.bodySmall)
                Text("• Downloaded history is isolated from your existing NIFTY-only pre-labelled corpus and from LIVE ARCHIVE data.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DownloadChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 9.sp, textAlign = TextAlign.Center) }
    else OutlinedButton(onClick, modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 9.sp, textAlign = TextAlign.Center) }
}

@Composable
private fun DownloadMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
