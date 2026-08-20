package com.parmod.ema

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
import com.parmod.ema.engine.MetaBrainRuntime
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
private fun MetaBrainLabScreen() {
    var report by remember { mutableStateOf(MetaBrainRuntime.report()) }
    var message by remember { mutableStateOf("AI LAB ready") }
    var profileMenu by remember { mutableStateOf(false) }

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
        Text("VARDHANI AI LAB", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Persistent production model · dynamic candidate training · automatic out-of-sample validation", style = MaterialTheme.typography.labelMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("MODEL STATUS", fontWeight = FontWeight.Bold)
                Row { LabMetric("Storage", if (report.persistent) "PERSISTENT" else "MEMORY", Modifier.weight(1f)); LabMetric("Mode", if (report.gateEnabled) "GATE" else "SHADOW", Modifier.weight(1f)) }
                Row { LabMetric("Production", "v${report.productionVersion}", Modifier.weight(1f)); LabMetric("Candidate", "v${report.candidateVersion}", Modifier.weight(1f)) }
                Row { LabMetric("Prod samples", report.productionSamples.toString(), Modifier.weight(1f)); LabMetric("Cand samples", report.candidateSamples.toString(), Modifier.weight(1f)) }
                LabMetric("Pending delayed labels", report.pendingLabels.toString(), Modifier.fillMaxWidth())
                Text("Saved: ${formatTime(report.lastSavedAt)} · promoted: ${formatTime(report.lastPromotedAt)}", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DYNAMIC CANDIDATE LAB", fontWeight = FontWeight.Bold)
                Text("Test different learning settings without rebuilding the APK. Each new candidate starts from the same frozen production baseline.", style = MaterialTheme.typography.labelSmall)
                Box {
                    OutlinedButton({ profileMenu = true }, Modifier.fillMaxWidth()) { Text("Profile: ${report.candidateProfile.title}") }
                    DropdownMenu(profileMenu, { profileMenu = false }) {
                        MetaBrainRuntime.availableProfiles().forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.title) },
                                onClick = {
                                    profileMenu = false
                                    val result = MetaBrainRuntime.startCandidate(profile, archiveCurrent = true)
                                    message = result.second
                                },
                            )
                        }
                    }
                }
                val hp = report.candidateHyperParameters
                Text("LR ${"%.4f".format(hp.learningRate)} · L2 ${"%.5f".format(hp.l2)} · TAKE ≥ ${pct(hp.takeThreshold)} · REJECT ≤ ${pct(hp.rejectThreshold)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ message = MetaBrainRuntime.startNextCandidate().second }, Modifier.weight(1f)) { Text("ARCHIVE + NEXT", fontSize = 9.sp) }
                    OutlinedButton({ MetaBrainRuntime.resetCandidateLearning(); message = "Candidate restarted from production baseline" }, Modifier.weight(1f)) { Text("RETRAIN SAME", fontSize = 9.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AUTO CANDIDATE SEARCH", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("At 150 labels, failed candidate is archived and next profile starts automatically. A passing candidate stops the search.", style = MaterialTheme.typography.labelSmall)
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
                Text("UNSEEN LIVE VALIDATION", fontWeight = FontWeight.Bold)
                Text("Production and Candidate are scored before the Candidate learns each outcome.", style = MaterialTheme.typography.labelSmall)
                Row { LabMetric("Labels", v.labels.toString(), Modifier.weight(1f)); LabMetric("Pending", report.pendingLabels.toString(), Modifier.weight(1f)) }
                Row { LabMetric("Prod accuracy", pct(v.productionAccuracy), Modifier.weight(1f)); LabMetric("Cand accuracy", pct(v.candidateAccuracy), Modifier.weight(1f)) }
                Row { LabMetric("Prod Brier", "%.4f".format(v.productionBrier), Modifier.weight(1f)); LabMetric("Cand Brier", "%.4f".format(v.candidateBrier), Modifier.weight(1f)) }
                Row { LabMetric("TAKE precision", pct(v.takePrecision), Modifier.weight(1f)); LabMetric("REJECT precision", pct(v.rejectPrecision), Modifier.weight(1f)) }
                Text(if (report.eligibleForPromotion) "✓ PROMOTION READY" else "NOT READY · ${report.promotionReason}", fontWeight = FontWeight.Bold, color = if (report.eligibleForPromotion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }

        if (report.candidateHistory.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("CANDIDATE LEADERBOARD", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton({ MetaBrainRuntime.clearCandidateHistory(); message = "Candidate history cleared" }) { Text("CLEAR") }
                    }
                    report.candidateHistory.take(8).forEachIndexed { index, r ->
                        if (index > 0) HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${index + 1} ${r.profile.title}${if (r.passed) " · PASS" else ""}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                Text("${r.labels} labels · Acc ${pct(r.candidateAccuracy)} vs ${pct(r.productionAccuracy)} · Brier ${"%.4f".format(r.candidateBrier)}", style = MaterialTheme.typography.labelSmall)
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
                    Button({ message = if (MetaBrainRuntime.forceSave()) "Model + validation saved" else "Save failed" }, Modifier.weight(1f)) { Text("SAVE NOW", fontSize = 10.sp) }
                    OutlinedButton({ MetaBrainRuntime.resetCandidateLearning(); message = "Candidate reset to production baseline" }, Modifier.weight(1f)) { Text("RESET", fontSize = 10.sp) }
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
                ) { Text("PROMOTE BEST VALIDATED CANDIDATE") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ message = if (MetaBrainRuntime.rollbackProduction()) "Production rolled back; gate disabled" else "No rollback snapshot available" }, Modifier.weight(1f), enabled = report.rollbackAvailable) { Text("ROLLBACK", fontSize = 10.sp) }
                    OutlinedButton({ message = MetaBrainRuntime.setGateEnabled(!report.gateEnabled).second }, Modifier.weight(1f)) { Text(if (report.gateEnabled) "DISABLE GATE" else "ENABLE AI GATE", fontSize = 9.sp) }
                }
                Text("Only the frozen promoted Production model can gate engine entries. Learning candidates never place or force trades.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("WHAT THE BRAIN IS LEARNING", fontWeight = FontWeight.Bold)
                Text("E1/E2/E3 identity · CE/PE side · engine confidence · direction/entry quality · order flow · relative activity · OI impulse · option flow · acceleration · extension · D30 imbalance · microprice · TBQ/TSQ/book pressure · wall pressure · depth count · intraday phase.", style = MaterialTheme.typography.bodySmall)
                Text("Current labels use a fixed 5-minute directional outcome so candidate profiles are compared on exactly the same target. The next corpus upgrade will add actual option-premium MFE/MAE labels rather than changing labels between candidates.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(message, Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LabMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

private fun pct(value: Double) = "%.1f%%".format(value * 100.0)
private fun formatTime(epoch: Long): String = if (epoch <= 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epoch))
