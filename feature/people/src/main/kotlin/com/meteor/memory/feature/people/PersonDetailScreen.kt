package com.meteor.memory.feature.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.Relation

/** One aggregated record shown on a person's page. */
data class PersonRecordRow(
    val recordId: String,
    val dateLabel: String,
    val typeLabel: String,
)

/**
 * Logical aggregation of one person: profile from the file, records from the
 * index. The two live in different places on disk by design (archive v0.5).
 */
@Composable
fun PersonDetailScreen(
    displayName: String,
    relation: Relation?,
    notes: String,
    records: List<PersonRecordRow>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = relationLabel(relation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.people_edit)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.people_back)) }
            }
        }

        HorizontalDivider()

        Column(modifier = Modifier.padding(16.dp)) {
            if (notes.isBlank()) {
                Text(
                    text = stringResource(R.string.people_notes_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.people_records),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(16.dp),
        )

        if (records.isEmpty()) {
            Text(
                text = stringResource(R.string.people_records_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(records, key = { it.recordId }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = row.typeLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(text = row.dateLabel, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }
}
