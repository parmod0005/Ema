package com.parmod.ema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.parmod.ema.training.HistoricalDataViewModel
import com.parmod.ema.training.HistoricalMarketScope
import com.parmod.ema.training.PrelabelledTrainingWindowPlan

class HistoricalDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    HistoricalDataScreen(openAiLab = { startActivity(Intent(this, MetaBrainLabActivity::class.java)) })
                }
            }
        }
    }
}

@Composable
private fun HistoricalDataScreen(
    vm: HistoricalDataViewModel = viewModel(),
    openAiLab: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val corpus = state.summary
    val storage = state.storage
    val catalog = state.catalogue
    val importCatalogue = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importOldCatalogue(uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("VARDHANI HISTORICAL DATA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Persistent read-only Upstox Plus corpus · NIFTY + SENSEX · 1-minute CE/PE OHLC + volume + OI + aligned index context · verified resume",
            style = MaterialTheme.typography.labelMedium,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DOWNLOADED CORPUS", fontWeight = FontWeight.Bold)
                Row {
                    DownloadMetric("Contracts", corpus.optionContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("Option rows", corpus.rowsAccepted.toString(), Modifier.weight(1f))
                    DownloadMetric("Deduped", corpus.duplicatesRemoved.toString(), Modifier.weight(1f))
                }
                Row {
                    DownloadMetric("NIFTY", corpus.niftyContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("SENSEX", corpus.sensexContracts.toString(), Modifier.weight(1f))
                    DownloadMetric("CE / PE", "${corpus.ceContracts}/${corpus.peContracts}", Modifier.weight(1f))
                }
                Row {
                    DownloadMetric("NIFTY index 1m", state.niftyUnderlyingRows.toString(), Modifier.weight(1f))
                    DownloadMetric("SENSEX index 1m", state.sensexUnderlyingRows.toString(), Modifier.weight(1f))
                }
                Text("Local option coverage ${corpus.fromDate ?: "--"} → ${corpus.toDate ?: "--"}", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (corpus.bothMarketsPresent && state.niftyUnderlyingRows > 0 && state.sensexUnderlyingRows > 0) {
                        "✓ BOTH-MARKET OPTION + UNDERLYING CORPUS AVAILABLE"
                    } else {
                        "BOTH training needs verified NIFTY + SENSEX options and matching 1-minute index context"
                    },
                    color = if (corpus.bothMarketsPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
                corpus.errors.takeLast(3).forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("EXPIRED CONTRACT CATALOGUE", fontWeight = FontWeight.Bold)
                Row {
                    DownloadMetric("NIFTY expiries", catalog.niftyExpiries.toString(), Modifier.weight(1f))
                    DownloadMetric("SENSEX expiries", catalog.sensexExpiries.toString(), Modifier.weight(1f))
                    DownloadMetric("Contracts", catalog.contracts.toString(), Modifier.weight(1f))
                }
                Text("Catalogue coverage ${catalog.fromDate ?: "--"} → ${catalog.toDate ?: "--"}", style = MaterialTheme.typography.labelSmall)
                Text(
                    "Fresh Upstox Plus discovery is merged with this catalogue. Older verified instrument keys remain reusable even if today's expiry-discovery response is shorter.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { importCatalogue.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isRunning && !state.isImportingCatalogue,
                ) {
                    Text(if (state.isImportingCatalogue) "IMPORTING…" else "IMPORT OLD UPSTOX ARCHIVE / contracts.json")
                }
                Text(
                    "Import reads only real contracts.json metadata from a prior Upstox archive/ZIP. It does not guess expiry dates or instrument keys, and it does not change any model.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PHONE STORAGE", fontWeight = FontWeight.Bold)
                Row {
                    DownloadMetric("Corpus", formatBytes(storage.corpusBytes), Modifier.weight(1f))
                    DownloadMetric("Free", formatBytes(storage.freeBytes), Modifier.weight(1f))
                    DownloadMetric("Safety floor", formatBytes(storage.minimumFreeBytes), Modifier.weight(1f))
                }
                Text(
                    if (storage.canDownload) "Storage healthy" else "STORAGE PROTECTION ACTIVE · new historical downloads paused; verified data retained",
                    color = if (storage.canDownload) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DOWNLOAD CONFIGURATION", fontWeight = FontWeight.Bold)
                Text("MARKET", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoricalMarketScope.entries.forEach { scope ->
                        DownloadChoice(scope.label, state.selectedScope == scope, Modifier.weight(1f), !state.isRunning && !state.isImportingCatalogue) { vm.selectScope(scope) }
                    }
                }

                Text("REQUESTED WINDOW", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 3, 6, 12, PrelabelledTrainingWindowPlan.FULL).forEach { months ->
                        DownloadChoice(
                            PrelabelledTrainingWindowPlan.label(months),
                            state.selectedMonths == months,
                            Modifier.weight(1f),
                            !state.isRunning && !state.isImportingCatalogue,
                        ) { vm.selectMonths(months) }
                    }
                }
                Text(
                    "12M/FULL use every verified expiry in the persistent catalogue plus fresh Upstox Plus discovery. If catalogue coverage is shorter than requested, VARDHANI reports the gap instead of inventing data.",
                    style = MaterialTheme.typography.labelSmall,
                )

                Text("OPTION STRIKE DENSITY / EXPIRY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HistoricalCorpusDownloadManager.ALLOWED_STRIKE_RADII.sorted().forEach { radius ->
                        DownloadChoice(
                            "${radius * 2 + 1} STRIKES",
                            state.strikeRadius == radius,
                            Modifier.weight(1f),
                            !state.isRunning && !state.isImportingCatalogue,
                        ) { vm.selectStrikeRadius(radius) }
                    }
                }
                Text(
                    "Strike bands are anchored to a NIFTY/SENSEX close observed no later than the start of each option research window. 11 strikes/expiry is the balanced default; CE + PE are kept when available.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DOWNLOAD / RESUME", fontWeight = FontWeight.Bold)
                if (state.isRunning) {
                    LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                    Text("${state.stage} · ${state.completed}/${state.total.coerceAtLeast(1)}", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text(state.message, style = MaterialTheme.typography.labelSmall)
                    Row {
                        DownloadMetric("Cache", state.cacheHits.toString(), Modifier.weight(1f))
                        DownloadMetric("Network", state.networkRequests.toString(), Modifier.weight(1f))
                    }
                    OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("STOP DOWNLOAD SAFELY") }
                } else {
                    Button(vm::downloadOrResume, Modifier.fillMaxWidth(), enabled = storage.canDownload && !state.isImportingCatalogue) {
                        Text("DOWNLOAD / RESUME ${state.selectedScope.label} ${state.windowLabel}")
                    }
                    Text(state.message, style = MaterialTheme.typography.labelSmall)
                    Row {
                        DownloadMetric("Cache", state.cacheHits.toString(), Modifier.weight(1f))
                        DownloadMetric("Network", state.networkRequests.toString(), Modifier.weight(1f))
                        DownloadMetric("Errors", state.errors.toString(), Modifier.weight(1f))
                    }
                }

                if (state.availableFrom != null || state.availableTo != null) {
                    Text("Verified catalogue/discovery coverage ${state.availableFrom ?: "?"} → ${state.availableTo ?: "?"}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                if (state.sourceCoverageLimited) {
                    Text("⚠ Requested window is longer than verified catalogue/discovery coverage; missing history was not fabricated.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (state.strikeReferenceFallbacks > 0) {
                    Text("⚠ ${state.strikeReferenceFallbacks} expiry plan(s) lacked a causal index reference and used deterministic centre-strike fallback; this is recorded.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRAINING HANDOFF", fontWeight = FontWeight.Bold)
                Text(
                    "After download: AI LAB → DOWNLOADED → NIFTY, SENSEX or BOTH → select window → RUN. Direction comes from aligned 1-minute NIFTY/SENSEX bars; outcomes come from actual CE/PE premium paths.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(openAiLab, Modifier.fillMaxWidth(), enabled = !state.isRunning && !state.isImportingCatalogue && corpus.trainable) { Text("OPEN AI LAB") }
                OutlinedButton(vm::refresh, Modifier.fillMaxWidth(), enabled = !state.isRunning && !state.isImportingCatalogue) { Text("REFRESH CORPUS / CATALOGUE / STORAGE") }
                OutlinedButton(vm::clearDownloaded, Modifier.fillMaxWidth(), enabled = !state.isRunning && !state.isImportingCatalogue && corpus.optionContracts > 0) { Text("CLEAR DOWNLOADED CANDLES (KEEP CATALOGUE)") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DATA SAFETY", fontWeight = FontWeight.Bold)
                Text("• Read-only market-data APIs only; no broker order endpoint is involved.", style = MaterialTheme.typography.bodySmall)
                Text("• Every saved option contract is fully decoded, validated and SHA-256 fingerprinted before its resume marker is trusted.", style = MaterialTheme.typography.bodySmall)
                Text("• NIFTY/SENSEX 1-minute index context is stored separately and aligned only up to signal time.", style = MaterialTheme.typography.bodySmall)
                Text("• Corrupt contract files are quarantined instead of silently entering training.", style = MaterialTheme.typography.bodySmall)
                Text("• Interrupted downloads retain verified data; catalogue metadata remains available for later re-download.", style = MaterialTheme.typography.bodySmall)
                Text("• Historical D30/depth is never fabricated; unavailable depth features remain zero.", style = MaterialTheme.typography.bodySmall)
                Text("• Downloaded data is independent from the old imported NIFTY corpus and LIVE ARCHIVE.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DownloadChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) {
        Text(label, fontSize = 9.sp, textAlign = TextAlign.Center)
    } else OutlinedButton(onClick, modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 3.dp)) {
        Text(label, fontSize = 9.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DownloadMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}
