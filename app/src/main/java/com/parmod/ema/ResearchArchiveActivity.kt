package com.parmod.ema

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parmod.ema.training.LiveResearchArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class ResearchArchiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiveResearchArchive.initialize(applicationContext)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { ResearchArchiveScreen() } } }
    }
}

@Composable
private fun ResearchArchiveScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(LiveResearchArchive.summary()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Archive ready · normal VARDHANI updates keep this data automatically") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                message = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { LiveResearchArchive.exportZip(it) }
                            ?: error("Could not open selected destination")
                    }
                    "Research archive exported successfully"
                }.getOrElse { "Export failed: ${it.message ?: it::class.java.simpleName}" }
                summary = LiveResearchArchive.summary(); busy = false
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                message = runCatching {
                    val imported = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { LiveResearchArchive.importZip(it) }
                            ?: error("Could not open selected archive")
                    }
                    summary = LiveResearchArchive.summary()
                    "Import complete · $imported new research file(s) preserved · duplicate files skipped by SHA"
                }.getOrElse { "Import failed safely: ${it.message ?: it::class.java.simpleName}" }
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) { if (!busy) summary = LiveResearchArchive.summary(); delay(2000) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("VARDHANI RESEARCH BACKUP", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Persistent NIFTY + SENSEX research corpus · raw market context + exact AI observations + resolved outcomes", style = MaterialTheme.typography.labelMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ARCHIVE STATUS", fontWeight = FontWeight.Bold)
                Row {
                    ArchiveMetric("Sessions", summary.sessions.toString(), Modifier.weight(1f))
                    ArchiveMetric("Size", formatBytes(summary.bytes), Modifier.weight(1f))
                    ArchiveMetric("Files", summary.files.toString(), Modifier.weight(1f))
                }
                Row {
                    ArchiveMetric("Market rows", summary.tickRows.toString(), Modifier.weight(1f))
                    ArchiveMetric("1m bars", summary.minuteRows.toString(), Modifier.weight(1f))
                }
                Row {
                    ArchiveMetric("AI observations", summary.observations.toString(), Modifier.weight(1f))
                    ArchiveMetric("Outcomes", summary.outcomes.toString(), Modifier.weight(1f))
                }
                Row {
                    ArchiveMetric("Training compact", summary.compactTrainingRecords.toString(), Modifier.weight(1f))
                    ArchiveMetric("Raw pruned", summary.prunedRawFiles.toString(), Modifier.weight(1f))
                }
                Text("Archive schema v${summary.schemaVersion} · Feature schema v${summary.featureSchemaVersion} · imported backups ${summary.importedArchives}", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PHONE STORAGE PROTECTION", fontWeight = FontWeight.Bold)
                Row {
                    ArchiveMetric("Quota", formatBytes(summary.quotaBytes), Modifier.weight(1f))
                    ArchiveMetric("Free phone", formatBytes(summary.freeBytes), Modifier.weight(1f))
                    ArchiveMetric("Raw capture", if (summary.rawCaptureEnabled) "ON" else "PAUSED", Modifier.weight(1f))
                }
                Text(
                    "Raw ticks/D30 are losslessly GZIP-compressed first. Old low-value raw files can be pruned only after verified trainer-ready compaction. AI observations/outcomes and compact training records are never quota-pruned.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("STORAGE QUOTA", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1, 2, 4, 8).forEach { gb ->
                        val selected = summary.quotaBytes == gb.toLong() * 1024L * 1024L * 1024L
                        if (selected) Button({}, Modifier.weight(1f), enabled = false) { Text("$gb GB", fontSize = 9.sp) }
                        else OutlinedButton(
                            onClick = {
                                LiveResearchArchive.setStorageQuotaGiB(gb)
                                summary = LiveResearchArchive.summary(); message = "Storage quota set to $gb GB · maintenance scheduled"
                            },
                            modifier = Modifier.weight(1f), enabled = !busy,
                        ) { Text("$gb GB", fontSize = 9.sp) }
                    }
                }
                Text("RAW RETENTION BEFORE QUOTA PRUNING", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(7, 14, 30).forEach { days ->
                        OutlinedButton(
                            onClick = {
                                LiveResearchArchive.setRawRetentionDays(days)
                                message = "Raw retention set to $days days · high-value sessions remain protected"
                            },
                            modifier = Modifier.weight(1f), enabled = !busy,
                        ) { Text("$days DAYS", fontSize = 9.sp) }
                    }
                }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            message = runCatching {
                                val status = withContext(Dispatchers.IO) { LiveResearchArchive.runStorageMaintenance() }
                                summary = LiveResearchArchive.summary(); status.message
                            }.getOrElse { "Storage maintenance failed safely: ${it.message ?: it::class.java.simpleName}" }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy,
                ) { Text("RUN SAFE STORAGE MAINTENANCE") }
                Text(summary.storageMessage, style = MaterialTheme.typography.labelSmall)
                if (!summary.rawCaptureEnabled) {
                    Text("RAW STORAGE PROTECTION ACTIVE · 1m bars + AI observations + outcomes continue", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PORTABLE BACKUP", fontWeight = FontWeight.Bold)
                Text("Normal in-place updates keep the archive. Export a ZIP before uninstalling, changing phones, or changing APK signing certificate.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { exporter.launch("VARDHANI_RESEARCH_${LocalDate.now()}.zip") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("EXPORT RESEARCH ARCHIVE ZIP") }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("IMPORT RESEARCH ARCHIVE ZIP") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("DATA LINEAGE RULES", fontWeight = FontWeight.Bold)
                Text("• Exact duplicate files are skipped by SHA-256; training replay also dedupes observation IDs + canonical market/time/instrument identity.", style = MaterialTheme.typography.bodySmall)
                Text("• Every training row retains archive schema, feature schema and original normalized model vector.", style = MaterialTheme.typography.bodySmall)
                Text("• Raw D30/ticks are hash-verified after lossless compression before the uncompressed copy can be removed.", style = MaterialTheme.typography.bodySmall)
                Text("• Quota pruning requires a verified compact training file and never targets compact observations/outcomes.", style = MaterialTheme.typography.bodySmall)
                Text("• High-value/extreme/order-flow-invalidated sessions are protected from automatic raw pruning.", style = MaterialTheme.typography.bodySmall)
                Text("• No Upstox access token or broker credential is written to this archive.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ArchiveMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GB".format(bytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024.0)
    else -> "$bytes B"
}
