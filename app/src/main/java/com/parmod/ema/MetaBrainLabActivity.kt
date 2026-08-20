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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.parmod.ema.engine.MetaBrainRuntime
import com.parmod.ema.model.MarketIndex
import com.parmod.ema.training.HistoricalCorpusSource
import com.parmod.ema.training.HistoricalCorpusTrainingViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

class MetaBrainLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MetaBrainRuntime.initialize(applicationContext)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MetaBrainLabScreen() } } }
    }
}

@Composable
private fun MetaBrainLabScreen(trainingVm: HistoricalCorpusTrainingViewModel = viewModel()) {
    var report by remember { mutableStateOf(MetaBrainRuntime.report()) }
    val training by trainingVm.state.collectAsState()
    var message by remember { mutableStateOf("AI TRAINING CENTER ready") }
    var profileMenu by remember { mutableStateOf(false) }
    val corpusPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) trainingVm.importLocalCorpus(uris)
    }

    LaunchedEffect(Unit) {
        while (true) {
            report = MetaBrainRuntime.report()
            delay(1000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("VARDHANI AI TRAINING CENTER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Upstox / local / combined corpus → walk-forward → locked holdout → live shadow → manual promotion", style = MaterialTheme.typography.labelMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MODEL STATUS", fontWeight = FontWeight.Bold)
                Row {
                    LabMetric("Storage", if (report.persistent) "PERSISTENT" else "MEMORY", Modifier.weight(1f))
                    LabMetric("Mode", if (report.gateEnabled) "GATE" else "SHADOW", Modifier.weight(1f))
                }
                Row {
                    LabMetric("Production", "v${report.productionVersion}", Modifier.weight(1f))
                    LabMetric("Candidate", "v${report.candidateVersion}", Modifier.weight(1f))
                }
                Row {
                    LabMetric("Prod samples", report.productionSamples.toString(), Modifier.weight(1f))
                    LabMetric("Cand samples", report.candidateSamples.toString(), Modifier.weight(1f))
                }
                Row {
                    LabMetric("Search", if (report.autoSearchEnabled) "AUTO" else "MANUAL", Modifier.weight(1f))
                    LabMetric("Generation", if (report.candidateAdaptive) "G${report.candidateGeneration}" else "SEED", Modifier.weight(1f))
                }
                Text("Current Candidate: ${report.candidateName}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                if (report.candidateOrigin.isNotBlank()) Text("Origin: ${report.candidateOrigin}", style = MaterialTheme.typography.labelSmall)
                LabMetric("Pending delayed live labels", report.pendingLabels.toString(), Modifier.fillMaxWidth())
                Text("Saved: ${formatTime(report.lastSavedAt)} · promoted: ${formatTime(report.lastPromotedAt)}", style = MaterialTheme.typography.labelSmall)
            }
        }

        HistoricalTrainingCard(
            state = training,
            vm = trainingVm,
            onImport = { corpusPicker.launch(arrayOf("*/*")) },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADAPTIVE LIVE CANDIDATE SEARCH", fontWeight = FontWeight.Bold)
                Text("Use after historical training or as a live-only fallback. Adaptive search evolves bounded LR/L2/TAKE/REJECT settings around the strongest archived live Candidate.", style = MaterialTheme.typography.labelSmall)
                Text("Current: ${report.candidateName}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Box {
                    OutlinedButton({ profileMenu = true }, Modifier.fillMaxWidth()) { Text("Seed profile: ${report.candidateProfile.title}") }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        MetaBrainRuntime.availableProfiles().forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.title) },
                                onClick = {
                                    profileMenu = false
                                    message = MetaBrainRuntime.startCandidate(profile, archiveCurrent = true).second
                                },
                            )
                        }
                    }
                }
                val hp = report.candidateHyperParameters
                Text("LR ${"%.4f".format(hp.learningRate)} · L2 ${"%.5f".format(hp.l2)} · TAKE ≥ ${pct(hp.takeThreshold)} · REJECT ≤ ${pct(hp.rejectThreshold)}", style = MaterialTheme.typography.bodySmall)
                report.bestArchivedScore?.let { Text("Best archived live-search score ${"%.3f".format(it)}", style = MaterialTheme.typography.labelSmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ message = MetaBrainRuntime.evolveBestCandidate().second }, Modifier.weight(1f)) { Text("EVOLVE BEST NOW", fontSize = 9.sp) }
                    OutlinedButton({
                        MetaBrainRuntime.resetCandidateLearning()
                        message = "Candidate reset to frozen Production with current hyperparameters"
                    }, Modifier.weight(1f)) { Text("RESET / RETRAIN", fontSize = 9.sp) }
                }
                OutlinedButton({ message = MetaBrainRuntime.startNextCandidate().second }, Modifier.fillMaxWidth()) { Text("ARCHIVE + NEXT NAMED SEED", fontSize = 9.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUTO ADAPTIVE SEARCH", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("At 150 fresh live labels a failed Candidate is archived, the best validated parent is evolved, exact repeats are avoided, and the next Candidate restarts from frozen Production. A passing Candidate stops search; promotion stays manual.", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = report.autoSearchEnabled,
                        onCheckedChange = { enabled -> message = MetaBrainRuntime.setAutoSearchEnabled(enabled).second },
                    )
                }
            }
        }

        val v = report.validation
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FRESH LIVE UNSEEN VALIDATION", fontWeight = FontWeight.Bold)
                Text("Production and Candidate are scored before Candidate learns each new live outcome. Historical champions must still pass this stage before promotion.", style = MaterialTheme.typography.labelSmall)
                Row {
                    LabMetric("Labels", v.labels.toString(), Modifier.weight(1f))
                    LabMetric("Pending", report.pendingLabels.toString(), Modifier.weight(1f))
                }
                Row {
                    LabMetric("Prod accuracy", pct(v.productionAccuracy), Modifier.weight(1f))
                    LabMetric("Cand accuracy", pct(v.candidateAccuracy), Modifier.weight(1f))
                }
                Row {
                    LabMetric("Prod Brier", "%.4f".format(v.productionBrier), Modifier.weight(1f))
                    LabMetric("Cand Brier", "%.4f".format(v.candidateBrier), Modifier.weight(1f))
                }
                Row {
                    LabMetric("TAKE precision", pct(v.takePrecision), Modifier.weight(1f))
                    LabMetric("REJECT precision", pct(v.rejectPrecision), Modifier.weight(1f))
                }
                Text(
                    if (report.eligibleForPromotion) "✓ PROMOTION READY" else "NOT READY · ${report.promotionReason}",
                    fontWeight = FontWeight.Bold,
                    color = if (report.eligibleForPromotion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        if (report.candidateHistory.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LIVE CANDIDATE LEADERBOARD", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton({ MetaBrainRuntime.clearCandidateHistory(); message = "Live Candidate history cleared" }) { Text("CLEAR") }
                    }
                    report.candidateHistory.take(8).forEachIndexed { index, r ->
                        if (index > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${index + 1} ${r.displayName}${if (r.passed) " · PASS" else ""}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("${r.labels} labels · Acc ${pct(r.candidateAccuracy)} vs ${pct(r.productionAccuracy)} · Brier ${"%.4f".format(r.candidateBrier)}", style = MaterialTheme.typography.labelSmall)
                                val rh = r.hyperParameters
                                Text("LR ${"%.4f".format(rh.learningRate)} · L2 ${"%.5f".format(rh.l2)} · T ${pct(rh.takeThreshold)} · R ${pct(rh.rejectThreshold)}", style = MaterialTheme.typography.labelSmall)
                                Text("TAKE ${pct(r.takePrecision)} · REJECT ${pct(r.rejectPrecision)} · ${formatTime(r.finishedAt)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("%.3f".format(r.score), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRAINING / STORAGE", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ message = if (MetaBrainRuntime.forceSave()) "Model + live validation saved" else "Save failed" }, Modifier.weight(1f)) { Text("SAVE NOW", fontSize = 10.sp) }
                    OutlinedButton({
                        MetaBrainRuntime.resetCandidateLearning()
                        message = "Candidate reset to frozen Production; installed historical Candidate discarded"
                    }, Modifier.weight(1f)) { Text("RESET CANDIDATE", fontSize = 9.sp) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VALIDATION / DEPLOYMENT", fontWeight = FontWeight.Bold)
                Button(
                    onClick = { message = MetaBrainRuntime.promoteCandidate().second },
                    enabled = report.eligibleForPromotion,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("PROMOTE VALIDATED CANDIDATE") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({
                        message = if (MetaBrainRuntime.rollbackProduction()) "Production rolled back; AI gate disabled" else "No rollback snapshot available"
                    }, Modifier.weight(1f), enabled = report.rollbackAvailable) { Text("ROLLBACK", fontSize = 10.sp) }
                    OutlinedButton({ message = MetaBrainRuntime.setGateEnabled(!report.gateEnabled).second }, Modifier.weight(1f)) {
                        Text(if (report.gateEnabled) "DISABLE GATE" else "ENABLE AI GATE", fontSize = 9.sp)
                    }
                }
                Text("Historical training, adaptive search and live learning can modify Candidate only. Production changes only after the promotion gate. Learning or failed Candidates never place or force trades.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TRAINING VALIDITY / FEATURE COVERAGE", fontWeight = FontWeight.Bold)
                Text("Historical corpus: imported or Upstox expired CE/PE premium OHLCV+OI · completed-bar features · next-bar entry · actual 5-bar option-premium MFE/MAE · conservative stop-first ambiguity · slippage + ₹70.80 brokerage/GST · chronological walk-forward · locked holdout.", style = MaterialTheme.typography.bodySmall)
                Text("Local import supports CSV, XLSX, JSON and ZIP containing those formats. Rows are schema-checked, normalized, deduplicated and copied to app-private storage before training.", style = MaterialTheme.typography.bodySmall)
                Text("Historical files do not provide native D30 order-book depth, microprice or walls unless explicitly present in a future compatible schema; current historical depth feature slots remain zero rather than fabricated. Fresh live learning still receives actual D30/order-flow inputs when available.", style = MaterialTheme.typography.bodySmall)
                Text("Live labels remain a fresh 5-minute directional reality check, so a premium-trained historical Candidate must generalize to current market behavior before promotion.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistoricalTrainingCard(
    state: HistoricalCorpusTrainingViewModel.UiState,
    vm: HistoricalCorpusTrainingViewModel,
    onImport: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HISTORICAL CORPUS + AI RESEARCH", fontWeight = FontWeight.Bold)
            Text("One pipeline for Upstox downloads, your own files, or both combined. Every source uses causal premium labels, chronological walk-forward and the same locked holdout before any historical champion can become Candidate.", style = MaterialTheme.typography.labelSmall)

            Text("CORPUS SOURCE", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HistoricalCorpusSource.entries.forEach { source ->
                    TrainingChoice(source.label, state.selectedSource == source, Modifier.weight(1f), !state.isRunning && !state.isImporting) { vm.selectSource(source) }
                }
            }

            Button(onImport, Modifier.fillMaxWidth(), enabled = !state.isRunning && !state.isImporting) {
                Text("IMPORT LOCAL CORPUS · CSV / XLSX / JSON / ZIP", fontSize = 10.sp)
            }

            val local = state.localSummary
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("LOCAL CORPUS QUALITY", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Row {
                        LabMetric("Contracts", local.optionContracts.toString(), Modifier.weight(1f))
                        LabMetric("Candles", local.rowsAccepted.toString(), Modifier.weight(1f))
                        LabMetric("Rejected", local.rowsRejected.toString(), Modifier.weight(1f))
                    }
                    Row {
                        LabMetric("NIFTY", local.niftyContracts.toString(), Modifier.weight(1f))
                        LabMetric("SENSEX", local.sensexContracts.toString(), Modifier.weight(1f))
                        LabMetric("Deduped", local.duplicatesRemoved.toString(), Modifier.weight(1f))
                    }
                    Text("CE ${local.ceContracts} · PE ${local.peContracts} · files ${local.filesImported} · supported ${local.supportedFiles}", style = MaterialTheme.typography.labelSmall)
                    if (local.fromDate != null || local.toDate != null) Text("Coverage ${local.fromDate ?: "?"} → ${local.toDate ?: "?"}", style = MaterialTheme.typography.labelSmall)
                    if (local.inferredLotSizeContracts > 0) Text("⚠ ${local.inferredLotSizeContracts} contract(s) use inferred lot size; cost metrics are approximate for those contracts.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    local.warnings.takeLast(3).forEach { Text("⚠ $it", style = MaterialTheme.typography.labelSmall) }
                    local.errors.takeLast(2).forEach { Text("ERROR · $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(vm::refreshLocalSummary, Modifier.weight(1f), enabled = !state.isRunning && !state.isImporting) { Text("REFRESH", fontSize = 9.sp) }
                        OutlinedButton(vm::clearLocalCorpus, Modifier.weight(1f), enabled = !state.isRunning && !state.isImporting && local.optionContracts > 0) { Text("CLEAR LOCAL", fontSize = 9.sp) }
                    }
                }
            }

            if (state.isImporting) {
                LinearProgressIndicator({ state.importProgress }, Modifier.fillMaxWidth())
                Text("IMPORT · ${state.importCompleted}/${state.importTotal.coerceAtLeast(1)}", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(state.importMessage, style = MaterialTheme.typography.labelSmall)
                OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("CANCEL IMPORT SAFELY") }
            }

            Text("MARKET / WINDOW", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TrainingChoice("NIFTY", state.selectedIndex == MarketIndex.NIFTY, Modifier.weight(1f), !state.isRunning && !state.isImporting) { vm.selectIndex(MarketIndex.NIFTY) }
                TrainingChoice("SENSEX", state.selectedIndex == MarketIndex.SENSEX, Modifier.weight(1f), !state.isRunning && !state.isImporting) { vm.selectIndex(MarketIndex.SENSEX) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(1, 3, 6, 12).forEach { months ->
                    TrainingChoice("${months}M", state.selectedMonths == months, Modifier.weight(1f), !state.isRunning && !state.isImporting) { vm.selectMonths(months) }
                }
            }

            if (state.isRunning) {
                LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                Text("${state.stage} · ${state.completed}/${state.total.coerceAtLeast(1)}", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(state.message, style = MaterialTheme.typography.labelSmall)
                OutlinedButton(vm::cancel, Modifier.fillMaxWidth()) { Text("CANCEL TRAINING SAFELY") }
            } else if (!state.isImporting) {
                Button(vm::runOrResume, Modifier.fillMaxWidth()) { Text("RUN / RESUME ${state.selectedSource.label} HISTORICAL AI TRAINING") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(vm::clearUpstoxCache, Modifier.weight(1f)) { Text("CLEAR UPSTOX CACHE", fontSize = 8.sp) }
                    LabMetric("Cache", state.cacheHits.toString(), Modifier.weight(1f))
                    LabMetric("Network", state.networkRequests.toString(), Modifier.weight(1f))
                }
                Text(state.message, style = MaterialTheme.typography.labelSmall)
            }

            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            state.result?.let { r ->
                HorizontalDivider()
                Row {
                    LabMetric("Samples", r.corpusSamples.toString(), Modifier.weight(1f))
                    LabMetric("Contracts", r.contractsDownloaded.toString(), Modifier.weight(1f))
                    LabMetric("Candidates", r.candidatesEvaluated.toString(), Modifier.weight(1f))
                }
                Row {
                    LabMetric("CE", r.coverage.ceSamples.toString(), Modifier.weight(1f))
                    LabMetric("PE", r.coverage.peSamples.toString(), Modifier.weight(1f))
                    LabMetric("D30 native", r.coverage.nativeDepthSamples.toString(), Modifier.weight(1f))
                }
                Row {
                    LabMetric("E1 proxy", r.coverage.engine1Samples.toString(), Modifier.weight(1f))
                    LabMetric("E2 proxy", r.coverage.engine2Samples.toString(), Modifier.weight(1f))
                    LabMetric("E3 proxy", r.coverage.engine3Samples.toString(), Modifier.weight(1f))
                }
                Row {
                    LabMetric("Avg MFE", pctSigned(r.averageMfeReturn), Modifier.weight(1f))
                    LabMetric("Avg MAE", pctSigned(r.averageMaeReturn), Modifier.weight(1f))
                    LabMetric("Avg net", pctSigned(r.averageNetReturn), Modifier.weight(1f))
                }
                r.bestWalkForward?.let { best ->
                    Text("BEST WALK-FORWARD", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text("Folds ${best.foldsWon}/${best.foldsRun} won · score ${"%.4f".format(best.score)} · ${if (best.robust) "ROBUST" else "NOT ROBUST"}", style = MaterialTheme.typography.labelSmall)
                    val h = best.hyperParameters
                    Text("LR ${"%.4f".format(h.learningRate)} · L2 ${"%.5f".format(h.l2)} · TAKE ${pct(h.takeThreshold)} · REJECT ${pct(h.rejectThreshold)}", style = MaterialTheme.typography.labelSmall)
                    Text("WF Candidate acc ${pct(best.candidate.accuracy)} / Brier ${"%.4f".format(best.candidate.brier)} vs Production ${pct(best.production.accuracy)} / ${"%.4f".format(best.production.brier)}", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "LOCKED HOLDOUT: ${when { !r.lockedHoldoutOpened -> "CLOSED"; r.lockedHoldoutPassed -> "PASS"; else -> "FAIL" }}",
                    fontWeight = FontWeight.Bold,
                    color = if (r.lockedHoldoutPassed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                if (r.lockedHoldoutOpened) {
                    val c = r.holdoutCandidate
                    val p = r.holdoutProduction
                    if (c != null && p != null) {
                        Text("Holdout Candidate acc ${pct(c.accuracy)} · Brier ${"%.4f".format(c.brier)} · TAKE ${pct(c.takePrecision)} · REJECT ${pct(c.rejectPrecision)}", style = MaterialTheme.typography.labelSmall)
                        Text("Holdout Production acc ${pct(p.accuracy)} · Brier ${"%.4f".format(p.brier)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(if (state.installedCandidate) "✓ HISTORICAL CHAMPION INSTALLED AS CANDIDATE · LIVE VALIDATION RESET TO 0" else "Production unchanged", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(r.note, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrainingChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    if (selected) Button({}, modifier, enabled = false, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 9.sp) }
    else OutlinedButton(onClick, modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 3.dp)) { Text(label, fontSize = 9.sp) }
}

@Composable
private fun LabMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

private fun pct(value: Double) = "%.1f%%".format(value * 100.0)
private fun pctSigned(value: Double) = "%+.2f%%".format(value * 100.0)
private fun formatTime(epoch: Long): String = if (epoch <= 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
