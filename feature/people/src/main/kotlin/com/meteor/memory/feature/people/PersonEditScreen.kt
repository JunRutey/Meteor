package com.meteor.memory.feature.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
 * Editing an existing person (ADR-020): name, relationship, and the free-form
 * notes field, which is a Markdown body edited by `:feature-editor`.
 *
 * Laid out like the detail page it came from — same header line, same section
 * rhythm — so moving between 详情 and 编辑 does not feel like two different apps.
 */
@Composable
fun PersonEditScreen(
    displayName: String,
    relation: Relation?,
    notes: String,
    saving: Boolean,
    saved: Boolean,
    error: String?,
    onNameChanged: (String) -> Unit,
    onRelationChanged: (Relation?) -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.people_back))
            }
        }

        HorizontalDivider()

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

        SectionLabel(stringResource(R.string.people_label_notes))
        MarkdownField(
            text = notes,
            previewing = previewing,
            onTextChanged = onNotesChanged,
            onPreviewToggle = { previewing = !previewing },
            onInsertLink = onInsertLink,
            onPickImage = onPickImage,
            onRemoveImage = onRemoveImage,
            modifier = Modifier.weight(1f),
            placeholder = { Text(text = stringResource(R.string.people_notes_hint)) },
            missingImages = missingImages,
            resolveImage = resolveImage,
        )

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSave,
            enabled = !saving && displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                text = stringResource(
                    if (saving) R.string.people_saving else R.string.people_save,
                ),
            )
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
        if (selected == null) {
            Button(onClick = { onSelect(null) }) { Text(relationLabel(null)) }
        } else {
            TextButton(onClick = { onSelect(null) }) { Text(relationLabel(null)) }
        }
        Relation.entries.forEach { candidate ->
            if (candidate == selected) {
                Button(onClick = { onSelect(candidate) }) { Text(relationLabel(candidate)) }
            } else {
                TextButton(onClick = { onSelect(candidate) }) { Text(relationLabel(candidate)) }
            }
        }
    }
}
