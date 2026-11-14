package com.meteor.memory.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private const val TITLE_INPUT_LIMIT = 32

/** Shared header for the AI settings and usage sub-pages. */
@Composable
internal fun AiSubPageHeader(title: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.ai_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            AiIcon(
                AiIconKind.Back,
                iconSize = 22.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Top bar: back, the current conversation / scope, new chat and chat settings. */
@Composable
internal fun AiHeader(
    title: String,
    scopeLabel: String?,
    conversationEnabled: Boolean,
    scopeEnabled: Boolean,
    newEnabled: Boolean,
    onBack: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenChatSettings: () -> Unit,
    onNewConversation: () -> Unit,
) {
    val backDescription = stringResource(R.string.ai_back)
    val conversationsDescription = stringResource(R.string.ai_open_conversations)
    val settingsDescription = stringResource(R.string.ai_open_chat_settings)
    val newDescription = stringResource(R.string.ai_session_new)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = backDescription },
        ) {
            AiIcon(AiIconKind.Back, iconSize = 22.dp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        enabled = conversationEnabled,
                        role = Role.Button,
                        onClick = onOpenConversations,
                    )
                    .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                    .semantics { contentDescription = conversationsDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(3.dp))
                AiIcon(
                    AiIconKind.ChevronDown,
                    iconSize = 14.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (scopeLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = scopeEnabled,
                            role = Role.Button,
                            onClick = onOpenChatSettings,
                        )
                        .padding(start = 6.dp, end = 6.dp, top = 1.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = scopeLabel,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    AiIcon(
                        AiIconKind.ChevronRight,
                        iconSize = 12.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        IconButton(
            onClick = onNewConversation,
            enabled = newEnabled,
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = newDescription },
        ) {
            AiIcon(
                AiIconKind.Plus,
                iconSize = 22.dp,
                tint = if (newEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
        IconButton(
            onClick = onOpenChatSettings,
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = settingsDescription },
        ) {
            AiIcon(
                AiIconKind.Tune,
                iconSize = 22.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Empty state: one greeting, a short explanation and three quiet starters. */
@Composable
internal fun AiWelcome(enabled: Boolean, onSend: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AiIcon(
                    AiIconKind.Sparkle,
                    iconSize = 30.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.ai_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ai_welcome_body),
            modifier = Modifier.widthIn(max = 320.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.ai_suggestions_heading),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            R.string.ai_suggestion_archive,
            R.string.ai_suggestion_diary,
            R.string.ai_suggestion_person,
        ).forEach { promptId ->
            val prompt = stringResource(promptId)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled) { onSend(prompt) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AiIcon(
                        AiIconKind.ArrowUpRight,
                        iconSize = 18.dp,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = prompt,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Composer: attachments, growing text field, mode switches, attach and send. */
@Composable
internal fun AiComposer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    editing: Boolean,
    onCancelEdit: () -> Unit,
    providerLabel: String,
    sessionUsage: AiUsage,
    sessionElapsedMs: Long,
    pendingFiles: List<AiFileReference>,
    fileUploading: Boolean,
    canUploadFiles: Boolean,
    chatMode: AiChatMode,
    canUseWebTools: Boolean,
    onChatModeSelect: (AiChatMode) -> Unit,
    onPickFiles: () -> Unit,
    onRemovePendingFile: (String) -> Unit,
) {
    val enabled = !busy && !fileUploading
    val canSend = input.isNotBlank() && !busy && !fileUploading
    val addFileDescription = stringResource(R.string.ai_file_add)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (sessionUsage.totalTokens > 0) {
                    AiUsageSummary(usage = sessionUsage, elapsedMs = sessionElapsedMs)
                }
                if (editing) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AiIcon(
                                AiIconKind.Edit,
                                iconSize = 14.dp,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.ai_edit_banner),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            TextButton(
                                onClick = onCancelEdit,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_edit_cancel),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
                if (pendingFiles.isNotEmpty() || fileUploading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pendingFiles.forEach { file ->
                            AiAttachmentChip(
                                fileName = file.fileName,
                                enabled = enabled,
                                onRemove = { onRemovePendingFile(file.id) },
                            )
                        }
                        if (fileUploading) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = stringResource(R.string.ai_file_uploading),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 4.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    minLines = 1,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            if (input.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.ai_question_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AiModeChip(
                            label = stringResource(R.string.ai_mode_archive),
                            icon = AiIconKind.Archive,
                            selected = chatMode == AiChatMode.ARCHIVE,
                            enabled = enabled,
                            onClick = { onChatModeSelect(AiChatMode.ARCHIVE) },
                        )
                        AiModeChip(
                            label = stringResource(R.string.ai_mode_web),
                            icon = AiIconKind.Globe,
                            selected = chatMode == AiChatMode.WEB,
                            enabled = enabled && canUseWebTools,
                            onClick = { onChatModeSelect(AiChatMode.WEB) },
                        )
                    }
                    if (canUploadFiles) {
                        IconButton(
                            onClick = onPickFiles,
                            enabled = enabled,
                            modifier = Modifier
                                .size(40.dp)
                                .semantics { contentDescription = addFileDescription },
                        ) {
                            AiIcon(
                                AiIconKind.Paperclip,
                                iconSize = 20.dp,
                                tint = if (enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    AiSendButton(
                        canSend = canSend,
                        busy = busy || fileUploading,
                        onSend = onSend,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ai_composer_disclaimer, providerLabel),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AiAttachmentChip(
    fileName: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val removeDescription = stringResource(R.string.ai_file_remove)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = fileName,
                modifier = Modifier.widthIn(max = 150.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = removeDescription },
            ) {
                AiIcon(
                    AiIconKind.Close,
                    iconSize = 12.dp,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
        }
    }
}

@Composable
private fun AiModeChip(
    label: String,
    icon: AiIconKind,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiIcon(
                icon,
                iconSize = 15.dp,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AiReasoningOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun AiSendButton(canSend: Boolean, busy: Boolean, onSend: () -> Unit) {
    val sendDescription = stringResource(R.string.ai_send)
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .clickable(enabled = canSend, role = Role.Button, onClick = onSend)
            .semantics { contentDescription = sendDescription },
        shape = CircleShape,
        color = if (canSend) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AiIcon(
                    AiIconKind.Send,
                    iconSize = 20.dp,
                    tint = if (canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}

/** Conversation switcher, modelled after the demo's mobile session drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiConversationSheet(
    conversations: List<AiConversationItem>,
    activeConversationId: String?,
    enabled: Boolean,
    canClear: Boolean,
    hasMessages: Boolean,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf<AiConversationItem?>(null) }
    var deleting by remember { mutableStateOf<AiConversationItem?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = SheetShape,
        dragHandle = { AiSheetDragHandle() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            AiSheetHeader(
                title = stringResource(R.string.ai_conversations),
                onClose = onDismiss,
            )
            Button(
                onClick = onNew,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
            ) {
                AiIcon(
                    AiIconKind.Plus,
                    iconSize = 18.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_session_new))
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    AiConversationRow(
                        conversation = conversation,
                        active = conversation.id == activeConversationId,
                        enabled = enabled,
                        menuExpanded = menuFor == conversation.id,
                        onSelect = { onSelect(conversation.id) },
                        onMenuOpen = { menuFor = conversation.id },
                        onMenuDismiss = { menuFor = null },
                        onRename = {
                            menuFor = null
                            renaming = conversation
                        },
                        onDelete = {
                            menuFor = null
                            deleting = conversation
                        },
                    )
                }
            }
            if (hasMessages) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TextButton(
                    onClick = onClear,
                    enabled = canClear,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ai_clear_current),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    renaming?.let { conversation ->
        var titleDraft by remember(conversation.id) { mutableStateOf(conversation.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.ai_session_rename)) },
            text = {
                OutlinedTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it.take(TITLE_INPUT_LIMIT) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(conversation.id, titleDraft.trim())
                        renaming = null
                    },
                    enabled = titleDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.ai_session_rename_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.ai_settings_cancel))
                }
            },
        )
    }
    deleting?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.ai_session_delete)) },
            text = { Text(stringResource(R.string.ai_session_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(conversation.id)
                        deleting = null
                    },
                ) {
                    Text(stringResource(R.string.ai_session_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.ai_settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun AiConversationRow(
    conversation: AiConversationItem,
    active: Boolean,
    enabled: Boolean,
    menuExpanded: Boolean,
    onSelect: () -> Unit,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = conversation.title.ifBlank {
        stringResource(R.string.ai_session_untitled)
    }
    val moreDescription = stringResource(R.string.ai_more_actions)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Text(
                    text = stringResource(R.string.ai_current_conversation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box {
            IconButton(
                onClick = onMenuOpen,
                enabled = enabled,
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = moreDescription },
            ) {
                AiIcon(
                    AiIconKind.More,
                    iconSize = 18.dp,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_session_rename)) },
                    onClick = onRename,
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.ai_session_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = onDelete,
                )
            }
        }
    }
}

/** Conversation-specific settings: skill, subject, persona status and AI settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiChatSettingsSheet(
    skills: List<AiSkillChip>,
    selectedSkillId: String?,
    people: List<AiPersonChip>,
    selectedPersonId: String?,
    personaStatus: String?,
    reasoning: String,
    usageTotals: AiUsageTotals,
    enabled: Boolean,
    canUploadFiles: Boolean,
    canUseWebTools: Boolean,
    onSkillSelect: (String) -> Unit,
    onPersonSelect: (String?) -> Unit,
    onReasoningChange: (String) -> Unit,
    onOpenPersona: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var personMenuExpanded by remember { mutableStateOf(false) }
    val personLabel = if (selectedPersonId == null) {
        stringResource(R.string.ai_scope_archive)
    } else {
        people.firstOrNull { it.personId == selectedPersonId }?.label
            ?: selectedPersonId.take(8)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = SheetShape,
        dragHandle = { AiSheetDragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            AiSheetHeader(
                title = stringResource(R.string.ai_context_heading),
                onClose = onDismiss,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiSheetSectionLabel(stringResource(R.string.ai_skill))
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        skills.forEach { skill ->
                            AiSkillOption(
                                label = skill.label,
                                selected = skill.id == selectedSkillId,
                                enabled = enabled,
                                blockedByPerson = skill.requiresPerson && selectedPersonId == null,
                                onClick = { onSkillSelect(skill.id) },
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiSheetSectionLabel(stringResource(R.string.ai_scope))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(
                                    enabled = enabled,
                                    role = Role.Button,
                                    onClick = { personMenuExpanded = true },
                                ),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AiIcon(
                                    AiIconKind.User,
                                    iconSize = 18.dp,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = personLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                AiIcon(
                                    AiIconKind.ChevronDown,
                                    iconSize = 16.dp,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = personMenuExpanded,
                            onDismissRequest = { personMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ai_scope_archive)) },
                                onClick = {
                                    onPersonSelect(null)
                                    personMenuExpanded = false
                                },
                            )
                            people.forEach { person ->
                                DropdownMenuItem(
                                    text = { Text(person.label) },
                                    onClick = {
                                        onPersonSelect(person.personId)
                                        personMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (personaStatus != null && selectedPersonId != null) {
                    AiPersonaCard(
                        status = personaStatus,
                        enabled = enabled,
                        onOpenPersona = { onOpenPersona(selectedPersonId) },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiSheetSectionLabel(stringResource(R.string.ai_settings_reasoning))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReasoningMode.entries.forEach { mode ->
                            AiReasoningOption(
                                label = reasoningLabel(mode),
                                selected = mode.wire == reasoning,
                                enabled = enabled,
                                onClick = { onReasoningChange(mode.wire) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.ai_settings_reasoning_hint_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!canUseWebTools || !canUploadFiles) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!canUseWebTools) {
                            AiCapabilityNote(stringResource(R.string.ai_web_unavailable))
                        }
                        if (!canUploadFiles) {
                            AiCapabilityNote(stringResource(R.string.ai_file_not_supported))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiSettingsEntry(enabled = true, onClick = onOpenSettings)
                AiUsageSummaryCard(totals = usageTotals, onClick = onOpenUsage)
                Text(
                    text = stringResource(R.string.ai_sessions_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AiSkillOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    blockedByPerson: Boolean,
    onClick: () -> Unit,
) {
    val canPick = enabled && !blockedByPerson
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (canPick) 1f else 0.55f)
            .clip(RoundedCornerShape(16.dp))
            .selectable(
                selected = selected,
                enabled = canPick,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            } else {
                Color.Transparent
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (blockedByPerson) {
                    Text(
                        text = stringResource(R.string.ai_skill_requires_person),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                AiIcon(
                    AiIconKind.Check,
                    iconSize = 18.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Box(
                    Modifier
                        .size(18.dp)
                        .border(
                            1.5.dp,
                            MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun AiPersonaCard(
    status: String,
    enabled: Boolean,
    onOpenPersona: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.persona_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onOpenPersona,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_persona_open),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun AiCapabilityNote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AiIcon(
            AiIconKind.Alert,
            iconSize = 14.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiSettingsEntry(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AiIcon(
                    AiIconKind.Tune,
                    iconSize = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.ai_settings_entry),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        AiIcon(
            AiIconKind.ChevronRight,
            iconSize = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AiSheetHeader(title: String, onClose: () -> Unit) {
    val closeDescription = stringResource(R.string.ai_close_sheet)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = closeDescription },
        ) {
            AiIcon(
                AiIconKind.Close,
                iconSize = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AiSheetSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AiSheetDragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
    }
}

@Composable
internal fun AiUsageSummary(usage: AiUsage, elapsedMs: Long, compact: Boolean = false) {
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
