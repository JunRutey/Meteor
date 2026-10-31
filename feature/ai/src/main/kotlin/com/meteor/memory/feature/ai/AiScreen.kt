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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** One persisted chat bubble. History is encrypted on disk, not in the archive. */
data class AiChatBubble(
    val fromUser: Boolean,
    val text: String,
    val usage: AiUsage? = null,
    val elapsedMs: Long = 0,
)

/** One tool the model is calling (or has called) during the live turn. */
data class AiToolTrace(val name: String, val detail: String, val done: Boolean)

/**
 * The AI domain screen: pick a skill, optionally pick a person, chat.
 *
 * Pickers stay fixed above the transcript; the list follows the newest content
 * while the turn streams. The screen renders state only and owns no business
 * logic.
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
    sessionUsage: AiUsage = AiUsage(),
    sessionElapsedMs: Long = 0,
    streaming: Boolean = false,
    liveContent: String = "",
    liveReasoning: String = "",
    liveTools: List<AiToolTrace> = emptyList(),
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
                TextButton(onClick = onClear, enabled = messages.isNotEmpty() || streaming) {
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
            return@Column
        }

        SkillAndScopePicker(
            skills = skills,
            selectedSkillId = selectedSkillId,
            people = people,
            selectedPersonId = selectedPersonId,
            personaStatus = personaStatus,
            onSkillSelect = onSkillSelect,
            onPersonSelect = onPersonSelect,
            onOpenPersona = onOpenPersona,
        )
        HorizontalDivider()

        val showLive = streaming || liveContent.isNotBlank() ||
            liveReasoning.isNotBlank() || liveTools.isNotEmpty()
        val itemCount = (if (messages.isEmpty()) 1 else messages.size) +
            (if (showLive) 1 else 0) +
            (if (busy && !showLive) 1 else 0) +
            (if (error != null) 1 else 0)
        val listState = rememberLazyListState()
        LaunchedEffect(itemCount, liveContent.length, liveReasoning.length, liveTools.size) {
            if (itemCount > 0) listState.scrollToItem(itemCount - 1)
        }

        LazyColumn(modifier = Modifier.weight(1f), state = listState) {
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

            if (showLive) {
                item {
                    LiveTurn(
                        content = liveContent,
                        reasoning = liveReasoning,
                        tools = liveTools,
                        streaming = streaming,
                    )
                }
            } else if (busy) {
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
            if (sessionUsage.totalTokens > 0) {
                UsageSummary(sessionUsage, sessionElapsedMs)
            }
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

@Composable
private fun SkillAndScopePicker(
    skills: List<AiSkillChip>,
    selectedSkillId: String?,
    people: List<AiPersonChip>,
    selectedPersonId: String?,
    personaStatus: String?,
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onOpenPersona: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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

        val persona = personaStatus
        val personId = selectedPersonId
        if (persona != null && personId != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = persona, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onOpenPersona(personId) }) {
                    Text(stringResource(R.string.ai_persona_open))
                }
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
        val usage = bubble.usage
        if (!bubble.fromUser && usage != null && usage.totalTokens > 0 && bubble.elapsedMs > 0) {
            UsageSummary(usage, bubble.elapsedMs, compact = true)
        }
    }
}

/** The in-flight turn: tool chain, thinking trace and streamed answer text. */
@Composable
private fun LiveTurn(
    content: String,
    reasoning: String,
    tools: List<AiToolTrace>,
    streaming: Boolean,
) {
    var reasoningOpen by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tools.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                tools.forEach { trace ->
                    Text(
                        text = stringResource(
                            R.string.ai_tool_line,
                            if (trace.done) "✓" else "…",
                            toolLabel(trace.name),
                            trace.detail,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (reasoning.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    TextButton(onClick = { reasoningOpen = !reasoningOpen }) {
                        Text(
                            stringResource(
                                if (reasoningOpen) R.string.ai_hide_thinking else R.string.ai_thinking_process,
                            ),
                        )
                    }
                    if (reasoningOpen) {
                        Text(text = reasoning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (content.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = content + if (streaming) "▍" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        } else if (streaming) {
            Text(
                text = stringResource(R.string.ai_busy),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun toolLabel(name: String): String = stringResource(
    when (name) {
        "list_people" -> R.string.ai_tool_list_people
        "read_person" -> R.string.ai_tool_read_person
        "list_records" -> R.string.ai_tool_list_records
        "read_record" -> R.string.ai_tool_read_record
        "read_diary" -> R.string.ai_tool_read_diary
        "list_diary_dates" -> R.string.ai_tool_list_diary_dates
        "read_moods" -> R.string.ai_tool_read_moods
        "search_archive" -> R.string.ai_tool_search
        "save_diary" -> R.string.ai_tool_save_diary
        "append_diary" -> R.string.ai_tool_append_diary
        "set_mood" -> R.string.ai_tool_set_mood
        "create_person" -> R.string.ai_tool_create_person
        "update_person" -> R.string.ai_tool_update_person
        else -> R.string.ai_tool_unknown
    },
)

/** "1.4s · 812 tokens · 580 tok/s · cache 63%" — numbers only in code. */
@Composable
private fun UsageSummary(usage: AiUsage, elapsedMs: Long, compact: Boolean = false) {
    if (usage.totalTokens <= 0 || elapsedMs <= 0) return
    val seconds = elapsedMs / 1000.0
    val rate = (usage.totalTokens / seconds).toInt()
    val cache = if (usage.cacheTokens > 0) {
        usage.cacheHitTokens * 100 / usage.cacheTokens
    } else {
        null
    }
    val text = when {
        compact && cache != null ->
            stringResource(R.string.ai_usage_line_cache, seconds, usage.totalTokens, rate, cache)

        compact ->
            stringResource(R.string.ai_usage_line, seconds, usage.totalTokens, rate)

        cache != null ->
            stringResource(R.string.ai_usage_session_cache, usage.totalTokens, rate, cache)

        else ->
            stringResource(R.string.ai_usage_session, usage.totalTokens, rate)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
