package com.meteor.memory.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

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
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    val files: List<AiFileReference> = emptyList(),
    val serverToolCallsJson: String? = null,
    val citations: List<AiCitation> = emptyList(),
    val isTruncated: Boolean = false,
)

data class AiConversationItem(val id: String, val title: String)

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
    chatMode: AiChatMode = AiChatMode.ARCHIVE,
    reasoning: String = ReasoningMode.AUTO.wire,
    usageTotals: AiUsageTotals = AiUsageTotals(),
    speechLoadingId: String? = null,
    speakingId: String? = null,
    conversations: List<AiConversationItem> = emptyList(),
    activeConversationId: String? = null,
    pendingFiles: List<AiFileReference> = emptyList(),
    fileUploading: Boolean = false,
    canUploadFiles: Boolean = false,
    canUseWebTools: Boolean = false,
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onSend: (String) -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAiSettings: () -> Unit = onOpenSettings,
    onOpenUsage: () -> Unit = onOpenAiSettings,
    onClear: () -> Unit,
    onToolApproval: (Boolean) -> Unit = {},
    onBack: () -> Unit,
    onChatModeSelect: (AiChatMode) -> Unit = {},
    onReasoningChange: (String) -> Unit = {},
    onToggleSpeech: (String) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onSelectConversation: (String) -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onDeleteConversation: (String) -> Unit = {},
    onPickFiles: () -> Unit = {},
    onRemovePendingFile: (String) -> Unit = {},
    onEditMessage: (String, String) -> Unit = { _, _ -> },
    onRegenerate: (String) -> Unit = {},
    onContinue: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var showChatSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var followTail by remember { mutableStateOf(true) }

    // Switching conversations must not leave an edit target or draft behind.
    LaunchedEffect(activeConversationId) {
        editingMessageId = null
        input = ""
        followTail = true
    }
    val bottomSlackPx = with(LocalDensity.current) { BOTTOM_SLACK_DP.dp.roundToPx() }

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
        if (query.isNotEmpty() && !busy && !fileUploading && blockedMessage == null) {
            input = ""
            followTail = true
            val editingId = editingMessageId
            editingMessageId = null
            if (editingId == null) onSend(query) else onEditMessage(editingId, query)
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

    val canInteract = !busy && !fileUploading
    val activeTitle = conversations.firstOrNull { it.id == activeConversationId }?.title.orEmpty()
    val screenTitle = if (activeTitle.isBlank()) {
        stringResource(R.string.ai_title)
    } else {
        activeTitle
    }
    val skillLabel = skills.firstOrNull { it.id == selectedSkillId }?.label
    val personLabel = if (selectedPersonId == null) {
        stringResource(R.string.ai_scope_archive)
    } else {
        people.firstOrNull { it.personId == selectedPersonId }?.label
            ?: selectedPersonId.take(8)
    }
    val scopeLabel = if (blockedMessage == null) {
        listOfNotNull(skillLabel, personLabel).joinToString(" · ")
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AiHeader(
            title = screenTitle,
            scopeLabel = scopeLabel,
            conversationEnabled = canInteract && blockedMessage == null,
            scopeEnabled = canInteract && blockedMessage == null,
            newEnabled = canInteract && blockedMessage == null,
            onBack = onBack,
            onOpenConversations = { showConversations = true },
            onOpenChatSettings = {
                if (blockedMessage != null) {
                    onOpenSettings()
                } else {
                    showChatSettings = true
                }
            },
            onNewConversation = {
                followTail = true
                onNewConversation()
            },
        )

        if (blockedMessage != null) {
            BlockedPanel(message = blockedMessage, onOpenSettings = onOpenSettings)
        } else {
            val showLive = streaming || liveContent.isNotBlank() || liveSteps.isNotEmpty()
            val continuePrompt = stringResource(R.string.ai_continue_prompt)
            val itemCount = (if (messages.isEmpty()) 1 else messages.size) +
                (if (showLive) 1 else 0) +
                (if (busy && !showLive) 1 else 0) +
                (if (error != null) 1 else 0)

            LaunchedEffect(
                itemCount,
                liveContent.length,
                liveSteps.size,
                followTail,
                messages.isEmpty(),
            ) {
                if (followTail && messages.isNotEmpty() && itemCount > 0) {
                    listState.scrollToItem(itemCount - 1, SCROLL_TO_END_OFFSET)
                }
            }
            val jumpLatestDescription = stringResource(R.string.ai_jump_latest)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (messages.isEmpty()) {
                        item(key = "welcome") {
                            AiWelcome(
                                enabled = canInteract,
                                onSend = { text -> submit(text) },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = messages,
                            key = { _, bubble -> bubble.id },
                        ) { index, bubble ->
                            ChatMessage(
                                bubble = bubble,
                                enabled = canInteract,
                                canContinue = index == messages.lastIndex,
                                speechLoading = speechLoadingId == bubble.id,
                                speaking = speakingId == bubble.id,
                                onEdit = {
                                    editingMessageId = bubble.id
                                    input = bubble.text
                                },
                                onRegenerate = { onRegenerate(bubble.id) },
                                onContinue = { onContinue(bubble.id, continuePrompt) },
                                onToggleSpeech = { onToggleSpeech(bubble.id) },
                            )
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
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                ThinkingIndicator()
                            }
                        }
                    }

                    error?.let { message ->
                        item(key = "error") { ErrorCard(message) }
                    }
                }

                if (!followTail && listState.canScrollForward) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                followTail = true
                                coroutineScope.launch {
                                    listState.animateScrollToItem(
                                        index = listState.layoutInfo.totalItemsCount - 1,
                                        scrollOffset = SCROLL_TO_END_OFFSET,
                                    )
                                }
                            }
                            .semantics { contentDescription = jumpLatestDescription },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 4.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AiIcon(
                                AiIconKind.ChevronDown,
                                iconSize = 18.dp,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            AiComposer(
                input = input,
                onInputChange = { input = it },
                onSend = { submit() },
                busy = busy,
                editing = editingMessageId != null,
                onCancelEdit = {
                    editingMessageId = null
                    input = ""
                },
                providerLabel = providerLabel,
                sessionUsage = sessionUsage,
                sessionElapsedMs = sessionElapsedMs,
                pendingFiles = pendingFiles,
                fileUploading = fileUploading,
                canUploadFiles = canUploadFiles,
                chatMode = chatMode,
                canUseWebTools = canUseWebTools,
                onChatModeSelect = onChatModeSelect,
                onPickFiles = onPickFiles,
                onRemovePendingFile = onRemovePendingFile,
            )
        }
    }

    if (showConversations && blockedMessage == null) {
        AiConversationSheet(
            conversations = conversations,
            activeConversationId = activeConversationId,
            enabled = canInteract,
            canClear = messages.isNotEmpty() && canInteract,
            hasMessages = messages.isNotEmpty(),
            onDismiss = { showConversations = false },
            onNew = {
                showConversations = false
                followTail = true
                onNewConversation()
            },
            onSelect = { id ->
                showConversations = false
                followTail = true
                onSelectConversation(id)
            },
            onRename = onRenameConversation,
            onDelete = onDeleteConversation,
            onClear = {
                showConversations = false
                confirmClear = true
            },
        )
    }

    if (showChatSettings && blockedMessage == null) {
        AiChatSettingsSheet(
            skills = skills,
            selectedSkillId = selectedSkillId,
            people = people,
            selectedPersonId = selectedPersonId,
            personaStatus = personaStatus,
            reasoning = reasoning,
            usageTotals = usageTotals,
            enabled = canInteract,
            canUploadFiles = canUploadFiles,
            canUseWebTools = canUseWebTools,
            onSkillSelect = onSkillSelect,
            onPersonSelect = onPersonSelect,
            onReasoningChange = onReasoningChange,
            onOpenPersona = { personId ->
                showChatSettings = false
                onOpenPersona(personId)
            },
            onOpenSettings = {
                showChatSettings = false
                onOpenAiSettings()
            },
            onOpenUsage = {
                showChatSettings = false
                onOpenUsage()
            },
            onDismiss = { showChatSettings = false },
        )
    }
}

@Composable
private fun ChatMessage(
    bubble: AiChatBubble,
    enabled: Boolean,
    canContinue: Boolean,
    speechLoading: Boolean,
    speaking: Boolean,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onToggleSpeech: () -> Unit,
) {
    if (bubble.fromUser) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 460.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomEnd = 6.dp,
                    bottomStart = 20.dp,
                ),
            ) {
                SelectionContainer {
                    Text(
                        text = bubble.text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            bubble.files.forEach { file ->
                Text(
                    text = stringResource(R.string.ai_file_attached, file.fileName),
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onEdit,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_message_edit),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AssistantAvatar()
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProcessSteps(steps = bubble.steps, initiallyExpanded = false)
                if (bubble.text.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomEnd = 20.dp,
                            bottomStart = 6.dp,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = bubble.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (bubble.citations.isNotEmpty()) {
                                CitationList(bubble.citations)
                            }
                        }
                    }
                }
                AssistantFooter(
                    usage = bubble.usage,
                    elapsedMs = bubble.elapsedMs,
                    canContinue = bubble.isTruncated &&
                        bubble.serverToolCallsJson == null &&
                        canContinue,
                    hasText = bubble.text.isNotBlank(),
                    enabled = enabled,
                    speechLoading = speechLoading,
                    speaking = speaking,
                    onContinue = onContinue,
                    onToggleSpeech = onToggleSpeech,
                    onRegenerate = onRegenerate,
                )
            }
        }
    }
}

@Composable
private fun AssistantFooter(
    usage: AiUsage?,
    elapsedMs: Long,
    canContinue: Boolean,
    hasText: Boolean,
    enabled: Boolean,
    speechLoading: Boolean,
    speaking: Boolean,
    onContinue: () -> Unit,
    onToggleSpeech: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (usage != null && usage.totalTokens > 0) {
                AiUsageSummary(usage, elapsedMs, compact = true)
            }
        }
        if (canContinue) {
            TextButton(
                onClick = onContinue,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_message_continue),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (hasText) {
            AiSpeechButton(
                loading = speechLoading,
                speaking = speaking,
                enabled = enabled,
                onClick = onToggleSpeech,
            )
        }
        AiRegenerateButton(enabled = enabled, onClick = onRegenerate)
    }
}

@Composable
private fun AiSpeechButton(
    loading: Boolean,
    speaking: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(
        if (loading || speaking) R.string.ai_speech_stop else R.string.ai_speech_play,
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = description },
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            speaking -> AiIcon(
                AiIconKind.Stop,
                iconSize = 18.dp,
                tint = MaterialTheme.colorScheme.primary,
            )

            else -> AiIcon(
                AiIconKind.Play,
                iconSize = 18.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiRegenerateButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.ai_message_regenerate)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.dp)
            .semantics { contentDescription = description },
    ) {
        AiIcon(
            AiIconKind.Regenerate,
            iconSize = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistantAvatar() {
    Surface(
        modifier = Modifier.size(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AiIcon(
                AiIconKind.Sparkle,
                iconSize = 15.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Citations come from the model, so only open ordinary web links. */
private fun openWebUrl(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
    runCatching { uriHandler.openUri(url) }
}

@Composable
private fun CitationList(citations: List<AiCitation>) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        citations.distinctBy(AiCitation::url).forEachIndexed { index, citation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { openWebUrl(uriHandler, citation.url) }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AiIcon(
                    AiIconKind.ChevronRight,
                    iconSize = 12.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.ai_citation_link, index + 1, citation.title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        AssistantAvatar()
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProcessSteps(steps = steps, initiallyExpanded = false)
            if (content.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomEnd = 20.dp,
                        bottomStart = 6.dp,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SelectionContainer {
                        Text(
                            text = content + if (streaming) "▍" else "",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
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

    var expanded by remember(initiallyExpanded) {
        mutableStateOf(initiallyExpanded)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiIcon(
                    if (expanded) AiIconKind.ChevronDown else AiIconKind.ChevronRight,
                    iconSize = 13.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.ai_process_group, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                steps.forEach { step ->
                    when (step) {
                        is AiProcessStep.Reasoning -> ReasoningStep(step.text)
                        is AiProcessStep.Tool -> ToolStep(step)
                        is AiProcessStep.Web -> WebToolStep(step.event)
                    }
                }
            }
        }
    }
}

@Composable
private fun WebToolStep(event: AiServerToolEvent) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val action = when (event.action) {
        "search" -> stringResource(R.string.ai_web_searching)
        "read_link" -> stringResource(R.string.ai_web_reading)
        "open_page" -> stringResource(R.string.ai_web_opening)
        "find_in_page" -> stringResource(R.string.ai_web_finding)
        else -> stringResource(R.string.ai_web_working)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "$action${event.url?.let { " · $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (event.queries.isNotEmpty()) {
            Text(
                text = event.queries.joinToString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        event.sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { openWebUrl(uriHandler, source.url) }
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AiIcon(
                    AiIconKind.ChevronRight,
                    iconSize = 11.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = source.siteName?.let { "$it · ${source.title}" } ?: source.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReasoningStep(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AiIcon(
                if (open) AiIconKind.ChevronDown else AiIconKind.ChevronRight,
                iconSize = 12.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.ai_thinking_process),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(start = 22.dp, end = 8.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolStep(step: AiProcessStep.Tool) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
private fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.ai_busy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BlockedPanel(message: String, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AiIcon(
                    AiIconKind.Alert,
                    iconSize = 28.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.ai_open_settings))
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AiIcon(
                AiIconKind.Alert,
                iconSize = 18.dp,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
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
        "delete_person" -> R.string.ai_tool_delete_person
        "delete_record" -> R.string.ai_tool_delete_record
        else -> R.string.ai_tool_unknown
    },
)
