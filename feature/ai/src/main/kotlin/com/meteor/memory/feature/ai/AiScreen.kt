package com.meteor.memory.feature.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.align
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One selectable skill. Labels are resolved by the caller. */
data class AiSkillChip(val id: String, val label: String, val requiresPerson: Boolean)

/** One selectable conversation subject. */
data class AiPersonChip(val personId: String, val label: String)

/** Gap below the last message that still counts as "the reader is at the end". */
private const val BOTTOM_SLACK_DP = 24
private const val SCROLL_TO_END_OFFSET = Int.MAX_VALUE / 4
private const val MAX_RESULT_LINE_CHARS = 120

/** One persisted chat bubble. History is encrypted on disk, not in the archive. */
data class AiChatBubble(
    val fromUser: Boolean,
    val text: String,
    val usage: AiUsage? = null,
    val elapsedMs: Long = 0,
    val steps: List<AiProcessStep> = emptyList(),
)

/** The AI workspace. It renders state only and keeps all AI operations in the caller. */
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
    pendingToolApprovals: List<AiToolApproval> = emptyList(),
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onSend: (String) -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
    onToolApproval: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var personMenuExpanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val bottomSlackPx = with(LocalDensity.current) { BOTTOM_SLACK_DP.dp.roundToPx() }
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        var lastOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (index, offset, scrolling) ->
            if (index < lastIndex || (index == lastIndex && offset < lastOffset)) {
                followTail = false
            }
            if (!scrolling && listState.isAtBottom(bottomSlackPx)) followTail = true
            lastIndex = index
            lastOffset = offset
        }
    }

    LaunchedEffect(selectedSkillId, selectedPersonId) {
        followTail = true
    }

    fun submit(text: String = input) {
        val query = text.trim()
        if (query.isNotEmpty() && !busy && blockedMessage == null) {
            input = ""
            followTail = true
            onSend(query)
        }
    }

    if (pendingToolApprovals.isNotEmpty()) {
        val isBatch = pendingToolApprovals.size > 1
        AlertDialog(
            onDismissRequest = { onToolApproval(false) },
            title = {
                Text(
                    stringResource(
                        if (isBatch) {
                            R.string.ai_tool_approval_batch_title
                        } else {
                            R.string.ai_tool_approval_title
                        },
                    ),
                )
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isBatch) {
                        Text(
                            stringResource(
                                R.string.ai_tool_approval_batch_message,
                                pendingToolApprovals.size,
                            ),
                        )
                    } else {
                        Text(
                            stringResource(
                                R.string.ai_tool_approval_message,
                                toolLabel(pendingToolApprovals.single().name),
                            ),
                        )
                    }
                    pendingToolApprovals.forEachIndexed { index, approval ->
                        if (isBatch) {
                            Text(
                                stringResource(
                                    R.string.ai_tool_approval_item,
                                    index + 1,
                                    toolLabel(approval.name),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        SelectionContainer {
                            Text(approval.arguments, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onToolApproval(true) }) {
                    Text(
                        stringResource(
                            if (isBatch) {
                                R.string.ai_tool_approval_confirm_all
                            } else {
                                R.string.ai_tool_approval_confirm
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onToolApproval(false) }) {
                    Text(stringResource(R.string.ai_tool_approval_deny))
                }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ai_clear_confirm_title)) },
            text = { Text(stringResource(R.string.ai_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        followTail = true
                        onClear()
                    },
                ) {
                    Text(stringResource(R.string.ai_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.ai_settings_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        WorkspaceHeader(
            providerLabel = providerLabel,
            canClear = messages.isNotEmpty() && !busy,
            onBack = onBack,
            onClear = { confirmClear = true },
            onOpenSettings = onOpenSettings,
        )

        HorizontalDivider()

        if (blockedMessage != null) {
            BlockedPanel(message = blockedMessage, onOpenSettings = onOpenSettings)
        } else {
            ConversationContext(
                skills = skills,
                selectedSkillId = selectedSkillId,
                people = people,
                selectedPersonId = selectedPersonId,
                personaStatus = personaStatus,
                enabled = !busy,
                personMenuExpanded = personMenuExpanded,
                onPersonMenuExpandedChange = { personMenuExpanded = it },
                onSkillSelect = onSkillSelect,
                onPersonSelect = onPersonSelect,
                onOpenPersona = onOpenPersona,
            )

            val showLive = streaming || liveContent.isNotBlank() || liveSteps.isNotEmpty()
            val itemCount = (if (messages.isEmpty()) 1 else messages.size) +
                (if (showLive) 1 else 0) +
                (if (busy && !showLive) 1 else 0) +
                (if (error != null) 1 else 0)

            LaunchedEffect(itemCount, liveContent.length, liveSteps.size, followTail) {
                if (followTail && itemCount > 0) {
                    listState.scrollToItem(itemCount - 1, SCROLL_TO_END_OFFSET)
                }
            }
            val jumpLatestDescription = stringResource(R.string.ai_jump_latest)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (messages.isEmpty()) {
                        item(key = "welcome") {
                            WelcomePanel(
                                enabled = !busy,
                                onSend = { text -> submit(text) },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = messages,
                            key = { index, _ -> "message-$index" },
                        ) { _, bubble ->
                            ChatMessage(bubble)
                        }
                    }

                    if (showLive) {
                        item(key = "live") {
                            LiveTurn(
                                content = liveContent,
                                steps = liveSteps,
                                streaming = streaming,
                            )
                        }
                    } else if (busy) {
                        item(key = "busy") {
                            ThinkingIndicator()
                        }
                    }

                    error?.let { message ->
                        item(key = "error") { ErrorCard(message) }
                    }
                }

                if (!followTail && listState.canScrollForward) {
                    SmallFloatingActionButton(
                        onClick = {
                            followTail = true
                            coroutineScope.launch {
                                listState.animateScrollToItem(
                                    index = listState.layoutInfo.totalItemsCount - 1,
                                    scrollOffset = SCROLL_TO_END_OFFSET,
                                )
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    ) {
                        Text(
                            text = "↓",
                            modifier = Modifier.semantics {
                                contentDescription = jumpLatestDescription
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }

            ChatComposer(
                input = input,
                onInputChange = { input = it },
                onSend = { submit() },
                busy = busy,
                blocked = blockedMessage != null,
                providerLabel = providerLabel,
                sessionUsage = sessionUsage,
                sessionElapsedMs = sessionElapsedMs,
            )
        }
    }
}

@Composable
private fun WorkspaceHeader(
    providerLabel: String,
    canClear: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val backDescription = stringResource(R.string.ai_back)
    val clearDescription = stringResource(R.string.ai_clear)
    val settingsDescription = stringResource(R.string.ai_open_settings)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backDescription },
        ) {
            Text("‹", style = MaterialTheme.typography.headlineMedium)
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text = "✦",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.ai_header_provider, providerLabel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier.semantics { contentDescription = clearDescription },
        ) {
            Text("↺", style = MaterialTheme.typography.titleLarge)
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.semantics { contentDescription = settingsDescription },
        ) {
            Text("⚙", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ConversationContext(
    skills: List<AiSkillChip>,
    selectedSkillId: String?,
    people: List<AiPersonChip>,
    selectedPersonId: String?,
    personaStatus: String?,
    enabled: Boolean,
    personMenuExpanded: Boolean,
    onPersonMenuExpandedChange: (Boolean) -> Unit,
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onOpenPersona: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_context_heading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            skills.forEach { skill ->
                item(key = skill.id) {
                    FilterChip(
                        selected = skill.id == selectedSkillId,
                        onClick = { onSkillSelect(skill.id) },
                        enabled = enabled,
                        label = { Text(skill.label) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_scope),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                val personLabel = if (selectedPersonId == null) {
                    stringResource(R.string.ai_scope_archive)
                } else {
                    people.firstOrNull { it.personId == selectedPersonId }?.label
                        ?: selectedPersonId.take(8)
                }
                FilterChip(
                    selected = selectedPersonId != null,
                    onClick = { onPersonMenuExpandedChange(true) },
                    enabled = enabled,
                    label = { Text(personLabel) },
                )
                DropdownMenu(
                    expanded = personMenuExpanded,
                    onDismissRequest = { onPersonMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ai_scope_archive)) },
                        enabled = enabled,
                        onClick = {
                            onPersonSelect(null)
                            onPersonMenuExpandedChange(false)
                        },
                    )
                    people.forEach { person ->
                        DropdownMenuItem(
                            text = { Text(person.label) },
                            enabled = enabled,
                            onClick = {
                                onPersonSelect(person.personId)
                                onPersonMenuExpandedChange(false)
                            },
                        )
                    }
                }
            }
        }

        val status = personaStatus
        val personId = selectedPersonId
        if (status != null && personId != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpenPersona(personId) }, enabled = enabled) {
                    Text(stringResource(R.string.ai_persona_open))
                }
            }
        }
    }
}

@Composable
private fun WelcomePanel(enabled: Boolean, onSend: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "✦",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.ai_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.ai_welcome_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.ai_suggestions_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        listOf(
            R.string.ai_suggestion_archive,
            R.string.ai_suggestion_diary,
            R.string.ai_suggestion_person,
        ).forEach { promptId ->
            val prompt = stringResource(promptId)
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onSend(prompt) },
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "↗",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = prompt,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockedPanel(message: String, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Text(
                text = "!",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Text(
            text = message,
            modifier = Modifier.padding(top = 18.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.ai_open_settings))
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.ai_busy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatMessage(bubble: AiChatBubble) {
    if (bubble.fromUser) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.94f),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    SelectionContainer {
                        Text(
                            text = bubble.text,
                            modifier = Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            AvatarBadge(assistant = false, modifier = Modifier.padding(start = 8.dp))
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            AvatarBadge(assistant = true, modifier = Modifier.padding(end = 8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProcessSteps(steps = bubble.steps, initiallyExpanded = false)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    SelectionContainer {
                        Text(
                            text = bubble.text,
                            modifier = Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                val usage = bubble.usage
                if (usage != null && usage.totalTokens > 0 && bubble.elapsedMs > 0) {
                    UsageSummary(usage, bubble.elapsedMs, compact = true)
                }
            }
        }
    }
}

@Composable
private fun AvatarBadge(assistant: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(32.dp),
        color = if (assistant) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = if (assistant) "✦" else "•",
                color = if (assistant) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun LiveTurn(
    content: String,
    steps: List<AiProcessStep>,
    streaming: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AvatarBadge(assistant = true, modifier = Modifier.padding(end = 8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProcessSteps(steps = steps, initiallyExpanded = true)
            if (content.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        text = content + if (streaming) "▍" else "",
                        modifier = Modifier.padding(horizontal = 17.dp, vertical = 13.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else if (streaming) {
                ThinkingIndicator()
            }
        }
    }
}

@Composable
private fun ProcessSteps(steps: List<AiProcessStep>, initiallyExpanded: Boolean) {
    if (steps.isEmpty()) return

    var expanded by remember(steps, initiallyExpanded) {
        mutableStateOf(initiallyExpanded)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = (if (expanded) "▾ " else "▸ ") +
                        stringResource(R.string.ai_process_group, steps.size),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelLarge,
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

@Composable
private fun ReasoningStep(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(
                text = (if (open) "▾ " else "▸ ") + stringResource(R.string.ai_thinking_process),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (open) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolStep(step: AiProcessStep.Tool) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
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
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ChatComposer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    blocked: Boolean,
    providerLabel: String,
    sessionUsage: AiUsage,
    sessionElapsedMs: Long,
) {
    HorizontalDivider()
    val sendDescription = stringResource(R.string.ai_send)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sessionUsage.totalTokens > 0) {
            UsageSummary(sessionUsage, sessionElapsedMs)
        }
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.ai_question_hint)) },
            shape = MaterialTheme.shapes.extraLarge,
            minLines = 1,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            trailingIcon = {
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank() && !busy && !blocked,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .semantics { contentDescription = sendDescription },
                ) {
                    Text("↑", style = MaterialTheme.typography.titleLarge)
                }
            },
        )
        Text(
            text = stringResource(R.string.ai_send_to, providerLabel),
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun LazyListState.isAtBottom(slackPx: Int): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + slackPx
}

@Composable
private fun toolLabel(name: String): String = stringResource(
    when (name) {
        "list_people" -> R.string.ai_tool_list_people
        "archive_overview" -> R.string.ai_tool_archive_overview
        "list_attachments" -> R.string.ai_tool_list_attachments
        "read_attachment_text" -> R.string.ai_tool_read_attachment_text
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
        "delete_record" -> R.string.ai_tool_delete_record
        else -> R.string.ai_tool_unknown
    },
)

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
        modifier = Modifier.widthIn(max = 520.dp).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
