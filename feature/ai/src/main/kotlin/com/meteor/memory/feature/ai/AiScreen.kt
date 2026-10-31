package com.meteor.memory.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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

/** One selectable skill. Labels are resolved by the caller. */
data class AiSkillChip(val id: String, val label: String, val requiresPerson: Boolean)

/** One selectable conversation subject. */
data class AiPersonChip(val personId: String, val label: String)

/** One in-memory chat bubble; history is never persisted. */
data class AiChatBubble(val fromUser: Boolean, val text: String)

/**
 * The AI domain screen: pick a skill, optionally pick a person, chat.
 *
 * It renders state only and owns no business logic; the provider name and every
 * model-produced string are supplied by the caller.
 */
@Composable
fun AiScreen(
    providerLabel: String,
    skills: List<AiSkillChip>,
    selectedSkillId: String?,
    people: List<AiPersonChip>,
    selectedPersonId: String?,
    messages: List<AiChatBubble>,
    busy: Boolean,
    error: String?,
    blockedMessage: String?,
    personaStatus: String?,
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onSend: (String) -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear, enabled = messages.isNotEmpty()) {
                    Text(stringResource(R.string.ai_clear))
                }
                TextButton(onClick = onBack) { Text(stringResource(R.string.ai_back)) }
            }
        }
        HorizontalDivider()

        val blocked = blockedMessage
        if (blocked != null) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = blocked, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_skill),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            skills.forEach { skill ->
                                if (skill.id == selectedSkillId) {
                                    Button(onClick = { onSkillSelect(skill.id) }) { Text(skill.label) }
                                } else {
                                    OutlinedButton(onClick = { onSkillSelect(skill.id) }) { Text(skill.label) }
                                }
                            }
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.ai_scope),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                if (selectedPersonId == null) {
                                    Button(onClick = { onPersonSelect(null) }) {
                                        Text(stringResource(R.string.ai_scope_archive))
                                    }
                                } else {
                                    OutlinedButton(onClick = { onPersonSelect(null) }) {
                                        Text(stringResource(R.string.ai_scope_archive))
                                    }
                                }
                            }
                            items(people, key = { it.personId }) { person ->
                                if (person.personId == selectedPersonId) {
                                    Button(onClick = { onPersonSelect(person.personId) }) { Text(person.label) }
                                } else {
                                    OutlinedButton(onClick = { onPersonSelect(person.personId) }) { Text(person.label) }
                                }
                            }
                        }
                    }
                }

                val persona = personaStatus
                val personId = selectedPersonId
                if (persona != null && personId != null) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = persona, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onOpenPersona(personId) }) {
                                Text(stringResource(R.string.ai_persona_open))
                            }
                        }
                    }
                }

                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.ai_empty_chat),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items(messages) { bubble -> ChatBubble(bubble) }
                }

                if (busy) {
                    item {
                        Text(
                            text = stringResource(R.string.ai_busy),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                val message = error
                if (message != null) {
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.ai_question_hint)) },
                    )
                    Button(
                        onClick = {
                            val text = input.trim()
                            if (text.isNotEmpty()) {
                                input = ""
                                onSend(text)
                            }
                        },
                        enabled = input.isNotBlank() && !busy,
                    ) {
                        Text(stringResource(R.string.ai_send))
                    }
                }
                Text(
                    text = stringResource(R.string.ai_send_to, providerLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.ai_privacy_short),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(bubble: AiChatBubble) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = if (bubble.fromUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = if (bubble.fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = bubble.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
