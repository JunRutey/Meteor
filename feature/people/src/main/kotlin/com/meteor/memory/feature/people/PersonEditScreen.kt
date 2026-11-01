package com.meteor.memory.feature.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.feature.editor.MarkdownField

/**
 * Editing an existing person (ADR-020, ADR-023, ADR-024).
 *
 * Layout rules, in priority order:
 *
 * 1. The header (标题 + 保存 / 返回) is **pinned**. The keyboard used to push it off
 *    the screen, which is why saving required closing the keyboard first.
 * 2. The page itself never scrolls and never moves: the identity fields sit above,
 *    the body editor takes whatever height is left. When the IME appears only the
 *    editor shrinks, so nothing jumps.
 * 3. The identity block *may* scroll, but only inside a capped box: on a short
 *    screen that keeps the editor usable instead of squeezing it to zero.
 *
 * 备注 and 正文 are separate fields because they answer different questions —
 * "怎么称呼、记着点什么" versus "关于这个人的正文".
 */
@Composable
fun PersonEditScreen(
    displayName: String,
    relation: Relation?,
    note: String,
    notes: String,
    saving: Boolean,
    insertingImage: Boolean,
    saved: Boolean,
    error: String?,
    onNameChanged: (String) -> Unit,
    onRelationChanged: (Relation?) -> Unit,
    onNoteChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    missingImages: Set<String> = emptySet(),
    resolveImage: (String) -> ImageBitmap? = { null },
) {
    var previewing by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val fieldsMaxHeight = maxHeight * FIELDS_MAX_FRACTION

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                saved = saved,
                saving = saving,
                insertingImage = insertingImage,
                canSave = displayName.isNotBlank(),
                onSave = onSave,
                onBack = onBack,
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = fieldsMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.people_label_name)) },
                    placeholder = { Text(text = stringResource(R.string.people_name_hint)) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.people_label_relation),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RelationPicker(
                        selected = relation,
                        onSelect = onRelationChanged,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.people_label_note)) },
                    placeholder = { Text(text = stringResource(R.string.people_note_hint)) },
                )

                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // The editor owns everything the other blocks did not take, so it is the
            // only thing that changes size when the keyboard animates in.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
            ) {
                MarkdownField(
                    text = notes,
                    previewing = previewing,
                    onTextChanged = onNotesChanged,
                    onPreviewToggle = { previewing = !previewing },
                    onInsertLink = onInsertLink,
                    onPickImage = onPickImage,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = { Text(text = stringResource(R.string.people_body_hint)) },
                    missingImages = missingImages,
                    resolveImage = resolveImage,
                )
            }
        }
    }
}

@Composable
private fun Header(
    saved: Boolean,
    saving: Boolean,
    insertingImage: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f): a long subtitle or a large font scale must never squeeze the
        // 保存 / 返回 buttons off the header.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.people_edit_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = when {
                    saving -> stringResource(R.string.people_saving)
                    insertingImage -> stringResource(R.string.people_adding_image)
                    saved -> stringResource(R.string.people_saved)
                    else -> stringResource(R.string.people_edit_subtitle)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSave, enabled = !saving && !insertingImage && canSave) {
                Text(
                    text = stringResource(
                        if (saving) R.string.people_saving else R.string.people_save,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.people_back))
            }
        }
    }
}

/**
 * 家人 / 朋友 / 绝交的朋友 / 未分类.
 *
 * Scrolls horizontally instead of wrapping: the longest label alone is wide
 * enough to overflow a narrow screen, and clipping "未分类" would hide the only
 * way to clear a relationship.
 */
@Composable
private fun RelationPicker(
    selected: Relation?,
    onSelect: (Relation?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelationChip(label = relationLabel(null), selected = selected == null) { onSelect(null) }
        Relation.entries.forEach { candidate ->
            RelationChip(label = relationLabel(candidate), selected = candidate == selected) {
                onSelect(candidate)
            }
        }
    }
}

@Composable
private fun RelationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        TextButton(onClick = onClick) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Share of the available height the identity block may claim. Everything above the
 * editor is fixed furniture; keeping it under half guarantees the editor stays
 * usable with the keyboard open, even on a short screen.
 */
private const val FIELDS_MAX_FRACTION = 0.42f
