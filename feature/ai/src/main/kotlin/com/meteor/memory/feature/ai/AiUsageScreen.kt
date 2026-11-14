package com.meteor.memory.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/** Lifetime AI chat accounting, shown on the dedicated usage page and summaries. */
data class AiUsageTotals(
    val tokens: Long = 0,
    val requests: Int = 0,
    val elapsedMs: Long = 0,
    val cacheHitTokens: Long = 0,
    val cacheTokens: Long = 0,
)

/** Dedicated usage page; the AI settings page no longer carries the statistics card. */
@Composable
fun AiUsageScreen(
    usageTotals: AiUsageTotals,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiSubPageHeader(
            title = stringResource(R.string.ai_usage_page_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            AiUsageTotalsCard(usageTotals)
        }
    }
}

/** Compact entry used by Settings and by the chat settings sheet. */
@Composable
internal fun AiUsageSummaryCard(
    totals: AiUsageTotals,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_usage_page_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.ai_usage_summary_line,
                        NumberFormat.getIntegerInstance().format(totals.tokens),
                        totals.requests,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AiIcon(
                AiIconKind.ChevronRight,
                iconSize = 16.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiUsageTotalsCard(totals: AiUsageTotals) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_usage_total_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.ai_usage_total_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AiStatRow(
                label = stringResource(R.string.ai_usage_total_tokens),
                value = NumberFormat.getIntegerInstance().format(totals.tokens),
            )
            AiStatRow(
                label = stringResource(R.string.ai_usage_total_requests),
                value = NumberFormat.getIntegerInstance().format(totals.requests.toLong()),
            )
            AiStatRow(
                label = stringResource(R.string.ai_usage_total_elapsed),
                value = aiElapsedLabel(totals.elapsedMs),
            )
            if (totals.cacheTokens > 0) {
                val percent = totals.cacheHitTokens * 100 / totals.cacheTokens
                AiStatRow(
                    label = stringResource(R.string.ai_usage_total_cache),
                    value = stringResource(R.string.ai_usage_total_cache_value, percent),
                )
            }
        }
    }
}

@Composable
private fun AiStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun aiElapsedLabel(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000).coerceAtLeast(0)
    return when {
        seconds >= 3600 -> stringResource(
            R.string.ai_usage_duration_hours_minutes,
            seconds / 3600,
            (seconds % 3600) / 60,
        )

        seconds >= 60 -> stringResource(
            R.string.ai_usage_duration_minutes_seconds,
            seconds / 60,
            seconds % 60,
        )

        else -> stringResource(R.string.ai_usage_duration_seconds, seconds)
    }
}
