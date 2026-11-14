package com.meteor.memory.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
 * and the filesystem schema depend on their stable codes. Everything after them
 * is free-form user text, stored as `tags:` in `profile.md`.
 */
@Composable
internal fun PersonTagEditor(
    relation: Relation?,
    tags: List<String>,
    onRelationChanged: (Relation?) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val canAdd = draft.isNotBlank() && tags.size < PersonProfileCodec.MAX_TAGS

    fun addTag() {
        val value = draft
            .replace(',', ' ')
            .replace('，', ' ')
            .replace('、', ' ')
            .trim()
            .take(PersonProfileCodec.MAX_TAG_CHARS)
            .trim()
        if (value.isNotEmpty() && value !in tags) {
            onTagsChanged((tags + value).take(PersonProfileCodec.MAX_TAGS))
        }
        draft = ""
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.people_tags_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelationChoice(
                label = relationLabel(null),
                selected = relation == null,
                onClick = { onRelationChanged(null) },
            )
            Relation.entries.forEach { candidate ->
                RelationChoice(
                    label = relationLabel(candidate),
                    selected = candidate == relation,
                    onClick = { onRelationChanged(candidate) },
                )
            }
            tags.forEach { tag ->
                RemovableTag(label = tag, onRemove = { onTagsChanged(tags - tag) })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    if (it.length <= PersonProfileCodec.MAX_TAG_CHARS) {
                        draft = it.replace('\n', ' ').replace('\r', ' ')
                    }
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                label = { Text(text = stringResource(R.string.people_tag_input_label)) },
                placeholder = { Text(text = stringResource(R.string.people_tag_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canAdd) addTag() }),
            )
            TextButton(onClick = { addTag() }, enabled = canAdd) {
                Text(text = stringResource(R.string.people_tag_add))
            }
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
}

@Composable
private fun RelationChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .selectable(
                selected = selected,
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
private fun RemovableTag(label: String, onRemove: () -> Unit) {
    val removeDescription = stringResource(R.string.people_tag_remove, label)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = removeDescription },
            ) {
                PersonIcon(
                    kind = PersonIconKind.Close,
                    iconSize = 12.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
