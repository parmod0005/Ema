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
        Text("Persistent Numerical Meta Brain · frozen production vs continuously learning candidate", style = MaterialTheme.typography.labelMedium)

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

        val v = report.validation
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UNSEEN LIVE VALIDATION", fontWeight = FontWeight.Bold)
                Text("Both models are scored on each new outcome before the candidate is allowed to learn from it.", style = MaterialTheme.typography.labelSmall)
                Row { LabMetric("Labels", v.labels.toString(), Modifier.weight(1f)); LabMetric("Pending", report.pendingLabels.toString(), Modifier.weight(1f)) }
                Row { LabMetric("Prod accuracy", pct(v.productionAccuracy), Modifier.weight(1f)); LabMetric("Cand accuracy", pct(v.candidateAccuracy), Modifier.weight(1f)) }
                Row { LabMetric("Prod Brier", "%.4f".format(v.productionBrier), Modifier.weight(1f)); LabMetric("Cand Brier", "%.4f".format(v.candidateBrier), Modifier.weight(1f)) }
                Row { LabMetric("TAKE precision", pct(v.takePrecision), Modifier.weight(1f)); LabMetric("REJECT precision", pct(v.rejectPrecision), Modifier.weight(1f)) }
                Text(if (report.eligibleForPromotion) "✓ PROMOTION READY" else "NOT READY · ${report.promotionReason}", fontWeight = FontWeight.Bold, color = if (report.eligibleForPromotion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TRAINING TOOLS", fontWeight = FontWeight.Bold)
                Text("Live candidate learning is automatic. These controls manage persistence and the candidate training cycle.", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ message = if (MetaBrainRuntime.forceSave()) "Model + validation saved" else "Save failed" }, Modifier.weight(1f)) { Text("SAVE NOW", fontSize = 10.sp) }
                    OutlinedButton({ MetaBrainRuntime.resetCandidateLearning(); message = "Candidate reset to production; validation restarted" }, Modifier.weight(1f)) { Text("RESET CANDIDATE", fontSize = 9.sp) }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VALIDATION / DEPLOYMENT TOOLS", fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        val result = MetaBrainRuntime.promoteCandidate()
                        message = result.second
                    },
                    enabled = report.eligibleForPromotion,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("PROMOTE VALIDATED CANDIDATE") }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ message = if (MetaBrainRuntime.rollbackProduction()) "Production rolled back; gate disabled" else "No rollback snapshot available" }, Modifier.weight(1f), enabled = report.rollbackAvailable) { Text("ROLLBACK", fontSize = 10.sp) }
                    OutlinedButton({
                        val result = MetaBrainRuntime.setGateEnabled(!report.gateEnabled)
                        message = result.second
                    }, Modifier.weight(1f)) { Text(if (report.gateEnabled) "DISABLE GATE" else "ENABLE AI GATE", fontSize = 9.sp) }
                }
                Text("Gate uses only the frozen production model. The continuously learning candidate can never directly place or force a trade.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("WHAT THE BRAIN IS LEARNING", fontWeight = FontWeight.Bold)
                Text("E1/E2/E3 identity · CE/PE side · engine confidence · direction/entry-quality scores · order flow · relative activity · OI impulse · option flow · acceleration · extension · D30 imbalance · microprice pressure · TBQ/TSQ/book pressure · wall pressure · depth level count · intraday phase · recent engine performance.", style = MaterialTheme.typography.bodySmall)
                Text("Labels: favorable move before adverse move, with a 5-minute delayed horizon. Repeated 250 ms evaluations are deduplicated into independent candidate windows.", style = MaterialTheme.typography.bodySmall)
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
