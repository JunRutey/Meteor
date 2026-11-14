package com.meteor.memory.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.PersonProfileCodec
import com.meteor.memory.core.storage.Relation

/**
 * The tag row used by both the create dialog and the edit screen.
 *
 * The three archive buckets stay structured ([Relation]) because the AI tools
 * and the filesystem schema depend on their stable codes. One relation can be
 * selected at a time (tap it again to clear), while custom tags are remembered
 * in settings and can be toggled independently — selecting several is allowed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonTagEditor(
    relation: Relation?,
    tags: List<String>,
    availableTags: List<String>,
    onRelationChanged: (Relation?) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onAddTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    val noneLabel = relationLabel(null)
    val relationLabels = Relation.entries.map { relationLabel(it) }
    val builtInLabels = (listOf(noneLabel) + relationLabels).toSet()
    val visibleCustomTags = (availableTags + tags)
        .filter { it.isNotBlank() && it !in builtInLabels }
        .distinct()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.people_tags_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip(
                label = noneLabel,
                selected = relation == null,
                role = Role.RadioButton,
                onClick = { onRelationChanged(null) },
            )
            Relation.entries.forEachIndexed { index, candidate ->
                val selected = candidate == relation
                TagChip(
                    label = relationLabels[index],
                    selected = selected,
                    role = Role.RadioButton,
                    // Clicking the selected relation again clears it.
                    onClick = { onRelationChanged(if (selected) null else candidate) },
                )
            }
            visibleCustomTags.forEach { tag ->
                val selected = tag in tags
                TagChip(
                    label = tag,
                    selected = selected,
                    role = Role.Checkbox,
                    onClick = {
                        when {
                            selected -> onTagsChanged(tags - tag)
                            tags.size < PersonProfileCodec.MAX_TAGS -> onTagsChanged(tags + tag)
                        }
                    },
                )
            }
            AddTagChip(onClick = { showAddDialog = true })
        }
        if (tags.size >= PersonProfileCodec.MAX_TAGS) {
            Text(
                text = stringResource(
                    R.string.people_tag_limit,
                    PersonProfileCodec.MAX_TAGS,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAddDialog) {
        AddTagDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { raw ->
                val value = PersonProfileCodec.normalizeTags(listOf(raw)).firstOrNull()
                if (value != null && value !in builtInLabels) {
                    onAddTag(value)
                    if (value !in tags && tags.size < PersonProfileCodec.MAX_TAGS) {
                        onTagsChanged(tags + value)
                    }
                }
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun TagChip(
    label: String,
    selected: Boolean,
    role: Role,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .selectable(
                selected = selected,
                role = role,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
private fun AddTagChip(onClick: () -> Unit) {
    val description = stringResource(R.string.people_tag_add_description)
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            PersonIcon(
                kind = PersonIconKind.Plus,
                iconSize = 14.dp,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddTagDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val canAdd = draft.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_tag_add_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    if (it.length <= PersonProfileCodec.MAX_TAG_CHARS) {
                        draft = it.replace('\n', ' ').replace('\r', ' ')
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text(stringResource(R.string.people_tag_input_label)) },
                placeholder = { Text(stringResource(R.string.people_tag_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (canAdd) onConfirm(draft) },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = canAdd) {
                Text(stringResource(R.string.people_tag_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.people_cancel))
            }
        },
    )
}
