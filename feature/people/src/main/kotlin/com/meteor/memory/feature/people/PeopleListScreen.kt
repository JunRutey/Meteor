package com.meteor.memory.feature.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
    onCreate: (String, Relation?) -> Unit,
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
            Text(text = stringResource(R.string.people_title), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showCreate = true }) { Text(stringResource(R.string.people_create)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.people_back)) }
            }
        }

        if (people.isEmpty()) {
            Text(
                text = stringResource(R.string.people_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(people, key = { it.personId }) { row ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(row.personId) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = row.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = relationLabel(row.relation),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(text = row.secondary, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }

    if (showCreate) {
        CreatePersonDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, relation ->
                showCreate = false
                onCreate(name, relation)
            },
        )
    }
}

@Composable
private fun CreatePersonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Relation?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf<Relation?>(Relation.FRIEND) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.people_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.people_name)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Relation.entries.forEach { candidate ->
                        if (candidate == relation) {
                            Button(onClick = { relation = candidate }) { Text(relationLabel(candidate)) }
                        } else {
                            TextButton(onClick = { relation = candidate }) { Text(relationLabel(candidate)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), relation) },
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
