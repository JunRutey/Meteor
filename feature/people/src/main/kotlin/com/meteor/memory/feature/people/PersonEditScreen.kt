package com.meteor.memory.feature.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
 * Editing an existing person (ADR-020, ADR-023).
 *
 * The form is **scrollable** with a fixed-height body editor: a `weight(1f)`
 * column collapses to nothing as soon as the keyboard eats the screen, which is
 * exactly how the note field ended up as a one-line sliver.
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

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // Save lives in the header: a pinned footer competed with the keyboard for
        // the little height that was left.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.people_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = if (saved) {
                        stringResource(R.string.people_saved)
                    } else {
                        stringResource(R.string.people_edit_subtitle)
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
                TextButton(
                    onClick = onSave,
                    enabled = !saving && displayName.isNotBlank(),
                ) {
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

        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel(stringResource(R.string.people_label_name))
            OutlinedTextField(
                value = displayName,
                onValueChange = onNameChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.people_name_hint)) },
            )

            SectionLabel(stringResource(R.string.people_label_relation))
            RelationPicker(selected = relation, onSelect = onRelationChanged)

            SectionLabel(stringResource(R.string.people_label_note))
            OutlinedTextField(
                value = note,
                onValueChange = onNoteChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.people_note_hint)) },
            )

            SectionLabel(stringResource(R.string.people_label_body))

            // Fixed height, not weight: the parent scrolls, so a weighted child
            // would be measured against an infinite height and collapse.
            Box(modifier = Modifier.fillMaxWidth().height(BODY_EDITOR_HEIGHT)) {
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

            error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 家人 / 朋友 / 绝交的朋友 / 未分类.
 *
 * Scrolls horizontally instead of wrapping: the longest label alone is wide
 * enough to overflow a narrow screen, and clipping "未分类" would hide the only
 * way to clear a relationship.
 */
@Composable
private fun RelationPicker(selected: Relation?, onSelect: (Relation?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
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

private val BODY_EDITOR_HEIGHT = 320.dp
