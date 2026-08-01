package com.parmod.ema.backtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BacktestRangeSelector(
    selectedMonths: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = listOf(1 to "1M", 3 to "3M", 6 to "6M", 12 to "1Y")
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ranges.forEach { (months, label) ->
            val selected = selectedMonths == months
            if (selected) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(label, fontSize = 11.sp) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(months) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Text(label, fontSize = 11.sp) }
            }
        }
    }
}

fun backtestRangeTitle(months: Int): String = when (months) {
    1 -> "1-MONTH"
    3 -> "3-MONTH"
    6 -> "6-MONTH"
    12 -> "1-YEAR"
    else -> "$months-MONTH"
}
