package com.parmod.ema

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parmod.ema.model.DashboardState

@Composable
fun LearningDashboardPanel(state: DashboardState, vm: TradingViewModel) {
    val learning = state.learning
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PAPER LEARNING", fontWeight = FontWeight.Bold)
                Text(
                    if (state.paperRiskLocked) "● LOCKED" else "● ACTIVE",
                    color = if (state.paperRiskLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trades ${learning.completedTrades}", style = MaterialTheme.typography.labelSmall)
                Text("Win ${"%.1f".format(learning.winRate * 100)}%", style = MaterialTheme.typography.labelSmall)
                Text("PF ${if (learning.profitFactor.isFinite()) "%.2f".format(learning.profitFactor) else "∞"}", style = MaterialTheme.typography.labelSmall)
                Text("DD ${"%.1f".format(learning.maximumDrawdownPct)}%", style = MaterialTheme.typography.labelSmall)
            }
            Text("Expectancy ₹${"%.2f".format(learning.expectancy)} · Wins ${learning.wins} · Losses ${learning.losses}", style = MaterialTheme.typography.labelSmall)
            Text("Policy v${learning.policyVersion} · minimum AI confidence ${learning.minimumAiConfidence}", style = MaterialTheme.typography.labelSmall)
            Text(if (learning.promotionEligible) "Candidate policy passed guarded evidence gates; approval is still required." else learning.message, style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            Text("Today: ${state.todayPaperTrades} trades · P&L ₹${"%.2f".format(state.todayPaperPnl)} · consecutive losses ${state.consecutivePaperLosses}", style = MaterialTheme.typography.labelSmall)
            Text(state.paperRiskReason, color = if (state.paperRiskLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = if (state.paperRiskLocked) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = vm::clearLearningJournal, enabled = state.position == null, modifier = Modifier.fillMaxWidth()) {
                Text("CLEAR PAPER JOURNAL")
            }
        }
    }
}
