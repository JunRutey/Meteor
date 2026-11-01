package com.meteor.memory.feature.ai

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One selectable skill. Labels are resolved by the caller. */
data class AiSkillChip(val id: String, val label: String, val requiresPerson: Boolean)

/** One selectable conversation subject. */
data class AiPersonChip(val personId: String, val label: String)

/** Scroll distance after which the pickers fold away. */
private const val PICKER_FOLD_THRESHOLD = 48

/** Tool output shown under a call: first line only, capped at this length. */
private const val MAX_RESULT_LINE_CHARS = 120

/** One persisted chat bubble. History is encrypted on disk, not in the archive. */
data class AiChatBubble(
    val fromUser: Boolean,
    val text: String,
    val usage: AiUsage? = null,
    val elapsedMs: Long = 0,
    /** Thinking segments and tool calls of this turn, in order. */
    val steps: List<AiProcessStep> = emptyList(),
)

/**
 * The AI domain screen: pick a skill, optionally pick a person, chat.
 *
 * The title bar stays pinned; the pickers fold away once the transcript is
 * scrolled forward and unfold when it is scrolled back. The list follows the
 * newest content while the turn streams. The screen renders state only.
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
    liveSteps: List<AiProcessStep> = emptyList(),
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
    var composerCollapsed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // The title bar is pinned; only the skill/person pickers fold away while the
    // transcript is being read, and unfold as soon as the list moves back.
    var pickersCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val forward = index > lastIndex || (index == lastIndex && offset > lastOffset)
                val backward = index < lastIndex || (index == lastIndex && offset < lastOffset)
                if (forward && (index > 0 || offset > PICKER_FOLD_THRESHOLD)) {
                    pickersCollapsed = true
                } else if (backward) {
                    pickersCollapsed = false
                }
                lastIndex = index
                lastOffset = offset
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        val blocked = blockedMessage
        ScreenHeader(
            canClear = messages.isNotEmpty() || streaming,
            onClear = onClear,
            onBack = onBack,
        )
        HorizontalDivider()

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

        AnimatedVisibility(visible = !pickersCollapsed) {
            Column {
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
            }
        }

        val showLive = streaming || liveContent.isNotBlank() || liveSteps.isNotEmpty()
        val itemCount = (if (messages.isEmpty()) 1 else messages.size) +
            (if (showLive) 1 else 0) +
            (if (busy && !showLive) 1 else 0) +
            (if (error != null) 1 else 0)
        LaunchedEffect(itemCount, liveContent.length, liveSteps.size) {
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
                        steps = liveSteps,
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
        Composer(
            input = input,
            onInputChange = { input = it },
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    input = ""
                    onSend(text)
                }
            },
            busy = busy,
            collapsed = composerCollapsed,
            onToggleCollapsed = { composerCollapsed = !composerCollapsed },
            sessionUsage = sessionUsage,
            sessionElapsedMs = sessionElapsedMs,
            providerLabel = providerLabel,
        )
    }
}

/** Input row. Collapsing it hands the space to the transcript. */
@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    sessionUsage: AiUsage,
    sessionElapsedMs: Long,
    providerLabel: String,
) {
    if (collapsed) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onToggleCollapsed) {
                Text(stringResource(R.string.ai_composer_expand))
            }
        }
        return
    }

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
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.ai_question_hint)) },
            )
            Button(onClick = onSend, enabled = input.isNotBlank() && !busy) {
                Text(stringResource(R.string.ai_send))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ai_send_to, providerLabel),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleCollapsed) {
                Text(stringResource(R.string.ai_composer_collapse))
            }
        }
    }
}

@Composable
private fun ScreenHeader(
    canClear: Boolean,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.ai_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onClear, enabled = canClear) {
                Text(stringResource(R.string.ai_clear))
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.ai_back)) }
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
        if (!bubble.fromUser) {
            ProcessSteps(steps = bubble.steps, initiallyExpanded = false)
        }
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

/**
 * One folding group per turn: "思考与工具调用（n）".
 *
 * Inside, thinking segments and tool calls keep the order they happened in. A
 * finished turn starts collapsed, so the answer stays readable, but the whole
 * process can be re-opened at any time.
 */
@Composable
private fun ProcessSteps(steps: List<AiProcessStep>, initiallyExpanded: Boolean) {
    if (steps.isEmpty()) return

    var expanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    text = chevron(expanded) + stringResource(R.string.ai_process_group, steps.size),
                )
            }
            if (expanded) {
                steps.forEach { step ->
                    when (step) {
                        is AiProcessStep.Reasoning -> ReasoningStep(step.text)
                        is AiProcessStep.Tool -> ToolStep(step)
                    }
                }
            }
        }
    }
}

/** One thinking segment, folded on its own inside the group. */
@Composable
private fun ReasoningStep(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(text = chevron(open) + stringResource(R.string.ai_thinking_process))
        }
        if (open) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

/** One tool call: status, name, arguments, and the first line of its output. */
@Composable
private fun ToolStep(step: AiProcessStep.Tool) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = stringResource(
                R.string.ai_tool_line,
                if (step.done) "✓" else "…",
                toolLabel(step.name),
                step.arguments,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val result = step.result
        if (!result.isNullOrBlank()) {
            Text(
                text = "↳ " + result.lineSequence().first().take(MAX_RESULT_LINE_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun chevron(open: Boolean): String = if (open) "▾ " else "▸ "

/** The in-flight turn: process steps plus the streamed answer text. */
@Composable
private fun LiveTurn(
    content: String,
    steps: List<AiProcessStep>,
    streaming: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ProcessSteps(steps = steps, initiallyExpanded = true)

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
