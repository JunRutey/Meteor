package com.meteor.memory.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
 * Since ADR-045 every label behaves the same way: the three former relation
 * buckets (家人 / 朋友 / 绝交的朋友) are ordinary tags, rendered with the same
 * [CustomTagChip] and the same ×/confirm deletion flow as user-authored labels.
 * [Relation] is not selected through special chips anymore; it is derived from
 * whichever built-in label is selected so `profile.md` keeps its `relation:`
 * field for AI tools and old readers.
 *
 * 未分类 stays a special chip: it clears the whole selection and has no delete
 * affordance. Custom chips carry a small × that removes the label from the
 * remembered library (and from this selection) after confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonTagEditor(
    relation: Relation?,
    tags: List<String>,
    availableTags: List<String>,
    onSelectionChanged: (List<String>, Relation?) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    val noneLabel = relationLabel(null)
    val relationLabels = Relation.entries.associateWith { relationLabel(it) }
    // 未分类 is an action, never a tag: it must not be deleted or remembered.
    val nonDeletableLabels = setOf(noneLabel)
    val legacyRelationLabel = relation?.let { relationLabels[it] }

    // Legacy profiles store `relation:` without the matching tag; fold it into
    // the selected set so the built-in label is shown and deletable as a tag.
    val selectedTags = PeopleTagLibrary.selectedTags(tags, legacyRelationLabel)
    val visibleTags = PeopleTagLibrary.visibleTags(
        available = availableTags,
        selected = selectedTags,
        excluded = nonDeletableLabels,
    )

    fun derivedRelation(selected: List<String>): Relation? =
        PeopleTagLibrary.relationFor(selected, relationLabels)

    fun applySelection(selected: List<String>) {
        onSelectionChanged(selected.distinct(), derivedRelation(selected))
    }

    fun toggleTag(label: String, selected: Boolean) {
        when {
            selected -> applySelection(selectedTags - label)
            selectedTags.size < PersonProfileCodec.MAX_TAGS -> applySelection(selectedTags + label)
        }
    }

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
                selected = selectedTags.isEmpty(),
                role = Role.RadioButton,
                onClick = { applySelection(emptyList()) },
            )
            visibleTags.forEach { tag ->
                val selected = tag in selectedTags
                CustomTagChip(
                    label = tag,
                    selected = selected,
                    onClick = { toggleTag(tag, selected) },
                    onDelete = { pendingDelete = tag },
                )
            }
            AddTagChip(onClick = { showAddDialog = true })
        }
        if (selectedTags.size >= PersonProfileCodec.MAX_TAGS) {
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
                if (value != null && value !in nonDeletableLabels) {
                    onAddTag(value)
                    if (value !in selectedTags && selectedTags.size < PersonProfileCodec.MAX_TAGS) {
                        applySelection(selectedTags + value)
                    }
                }
                showAddDialog = false
            },
        )
    }

    pendingDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.people_tag_delete_title)) },
            text = { Text(stringResource(R.string.people_tag_delete_body, tag)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tag in selectedTags) applySelection(selectedTags - tag)
                        onDeleteTag(tag)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.people_tag_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.people_cancel))
                }
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
private fun CustomTagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val deleteDescription = stringResource(R.string.people_tag_delete_description, label)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                modifier = Modifier
                    .selectable(
                        selected = selected,
                        role = Role.Checkbox,
                        onClick = onClick,
                    )
                    .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDelete)
                    .semantics { contentDescription = deleteDescription },
                contentAlignment = Alignment.Center,
            ) {
                PersonIcon(
                    kind = PersonIconKind.Close,
                    iconSize = 10.dp,
                    tint = contentColor.copy(alpha = 0.72f),
                )
            }
        }
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
