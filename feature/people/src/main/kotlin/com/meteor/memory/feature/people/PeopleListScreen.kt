package com.meteor.memory.feature.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.Relation

/** Row of the people overview. Labels are computed by the caller. */
data class PeopleRow(
    val personId: String,
    val displayName: String,
    val relation: Relation?,
    val secondary: String,
    val tags: List<String> = emptyList(),
)

@Composable
fun relationLabel(relation: Relation?): String = stringResource(
    when (relation) {
        Relation.FAMILY -> R.string.people_relation_family
        Relation.FRIEND -> R.string.people_relation_friend
        Relation.FORMER_FRIEND -> R.string.people_relation_former
        null -> R.string.people_relation_none
    },
)

@Composable
fun PeopleListScreen(
    people: List<PeopleRow>,
    availableTags: List<String>,
    onCreate: (String, Relation?, List<String>) -> Unit,
    onAddTag: (String) -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = stringResource(R.string.people_title), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showCreate = true }) { Text(stringResource(R.string.people_create)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.people_back)) }
            }
        }

        if (people.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = stringResource(R.string.people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 4.dp,
                end = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(people, key = { it.personId }) { row ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.personId) },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = row.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = relationLabel(row.relation),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            row.tags.forEach { tag ->
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = row.secondary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreatePersonDialog(
            availableTags = availableTags,
            onAddTag = onAddTag,
            onDismiss = { showCreate = false },
            onConfirm = { name, relation, tags ->
                showCreate = false
                onCreate(name, relation, tags)
            },
        )
    }
}

@Composable
private fun CreatePersonDialog(
    availableTags: List<String>,
    onAddTag: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, Relation?, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf<Relation?>(Relation.FRIEND) }
    var tags by remember { mutableStateOf(emptyList<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.people_create_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(text = stringResource(R.string.people_name)) },
                    placeholder = { Text(text = stringResource(R.string.people_name_hint)) },
                )
                PersonTagEditor(
                    relation = relation,
                    tags = tags,
                    availableTags = availableTags,
                    onRelationChanged = { relation = it },
                    onTagsChanged = { tags = it },
                    onAddTag = onAddTag,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), relation, tags) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.people_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.people_cancel)) }
        },
    )
}
