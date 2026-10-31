package com.meteor.memory.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One recurring term with how often it occurs. */
data class PersonaTopicRow(val term: String, val count: Int)

/** One stored line, dated by the record it came from. */
data class PersonaQuoteRow(val dateLabel: String, val text: String)

/** Everything the persona screen renders, already formatted by the caller. */
data class PersonaUi(
    val displayName: String,
    val distilled: Boolean,
    val body: String,
    val generatedLabel: String? = null,
    val providerLabel: String? = null,
    val skillLabel: String? = null,
    val recordCount: Int = 0,
    val diaryMentions: Int = 0,
    val topics: List<PersonaTopicRow> = emptyList(),
    val quotes: List<PersonaQuoteRow> = emptyList(),
)

/**
 * One person's digital persona: the model-written body (when distilled), the
 * local statistics, the explicit "distill over the network" action and the
 * entry into conversation.
 */
@Composable
fun PersonaScreen(
    persona: PersonaUi,
    providerLabel: String,
    sendsTo: String,
    busy: Boolean,
    error: String?,
    blockedMessage: String?,
    onDistill: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = persona.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.persona_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.ai_back)) }
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = stringResource(R.string.persona_stats_records, persona.recordCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.persona_stats_diaries, persona.diaryMentions),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (persona.distilled) {
                        val generated = persona.generatedLabel
                        if (generated != null) {
                            Text(
                                text = stringResource(R.string.persona_generated, generated),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val provider = persona.providerLabel
                        val skill = persona.skillLabel
                        if (provider != null && skill != null) {
                            Text(
                                text = stringResource(R.string.persona_by, provider, skill),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.persona_body_missing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    val blocked = blockedMessage
                    if (blocked != null) {
                        Text(text = blocked, style = MaterialTheme.typography.bodyMedium)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showConfirm = true },
                            enabled = !busy && blocked == null,
                        ) {
                            Text(
                                text = stringResource(
                                    when {
                                        busy -> R.string.persona_distilling
                                        persona.distilled -> R.string.persona_redistill
                                        else -> R.string.persona_distill
                                    },
                                ),
                            )
                        }
                        OutlinedButton(onClick = onOpenChat, enabled = !busy) {
                            Text(stringResource(R.string.persona_open_chat))
                        }
                        if (blocked != null) {
                            TextButton(onClick = onOpenSettings) {
                                Text(stringResource(R.string.ai_open_settings))
                            }
                        }
                    }

                    val message = error
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Text(
                        text = stringResource(R.string.ai_send_to, providerLabel),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (persona.distilled) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(text = persona.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.persona_topics),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (persona.topics.isEmpty()) {
                        Text(
                            text = stringResource(R.string.persona_topics_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        persona.topics.forEach { topic ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = topic.term, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = stringResource(R.string.persona_topic_weight, topic.count),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.persona_quotes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (persona.quotes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.persona_quotes_empty),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        persona.quotes.forEach { quote ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(text = quote.dateLabel, style = MaterialTheme.typography.bodySmall)
                                Text(text = quote.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.persona_confirm_title)) },
            text = { Text(stringResource(R.string.persona_confirm_body, sendsTo)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onDistill()
                    },
                ) {
                    Text(stringResource(R.string.persona_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.persona_confirm_cancel))
                }
            },
        )
    }
}
