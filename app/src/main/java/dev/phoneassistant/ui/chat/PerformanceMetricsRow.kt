package dev.phoneassistant.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phoneassistant.domain.chat.PerfMetrics

/**
 * Displays performance metrics from an LLM generation:
 * Prefill: X tok/s · Decode: Y tok/s · Z tokens
 */
@Composable
fun PerformanceMetricsRow(
    perfMetrics: PerfMetrics,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (perfMetrics.prefillSpeed > 0) {
            MetricText("Prefill: ${"%.1f".format(perfMetrics.prefillSpeed)} tok/s")
        }
        if (perfMetrics.decodeSpeed > 0) {
            MetricText("Decode: ${"%.1f".format(perfMetrics.decodeSpeed)} tok/s")
        }
        if (perfMetrics.decodeLen > 0) {
            MetricText("${perfMetrics.decodeLen} tokens")
        }
    }
}

@Composable
private fun MetricText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
}
