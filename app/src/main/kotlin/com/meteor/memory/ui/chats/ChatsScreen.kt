package com.meteor.memory.ui.chats

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.ui.attachments.AttachmentImportSummary

@Composable
fun ChatsScreen(
    state: ChatsViewState,
    onPersonSelected: (String?) -> Unit,
    onImportFiles: () -> Unit,
    onExport: (ChatAttachmentRow) -> Unit,
    onDelete: (ChatAttachmentRow) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var personMenuExpanded by remember { mutableStateOf(false) }
    val selectedPersonName = state.people.firstOrNull { it.personId == state.selectedPersonId }
        ?.displayName
        ?: state.selectedPersonId?.take(8)
        ?: stringResource(R.string.records_unassigned)
    var pendingDelete by remember { mutableStateOf<ChatAttachmentRow?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.chats_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onBack) { Text(text = stringResource(R.string.chats_back)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.records_owner_label, selectedPersonName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { personMenuExpanded = true }) {
                    Text(text = stringResource(R.string.records_choose_person))
                }
                DropdownMenu(
                    expanded = personMenuExpanded,
                    onDismissRequest = { personMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.records_unassigned)) },
                        onClick = {
                            onPersonSelected(null)
                            personMenuExpanded = false
                        },
                    )
                    state.people.forEach { person ->
                        DropdownMenuItem(
                            text = {
                                Text(person.displayName ?: person.personId.take(8))
                            },
                            onClick = {
                                onPersonSelected(person.personId)
                                personMenuExpanded = false
                            },
                        )
                    }
                }
            }
            Button(onClick = onImportFiles, enabled = !state.isImporting) {
                Text(
                    text = stringResource(
                        if (state.isImporting) R.string.records_importing else R.string.records_import,
                    ),
                )
            }
        }

        if (state.isImporting) {
            val (done, total) = state.importProgress ?: (0 to 0)
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.records_import_progress, done, total))
            }
        }

        state.exportFailed?.let { failed ->
            Text(
                text = stringResource(
                    if (failed) R.string.records_export_failed else R.string.records_export_done,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            state.loadFailed -> Text(
                text = stringResource(R.string.records_load_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            state.attachments.isEmpty() -> Text(
                text = stringResource(
                    if (state.selectedPersonId == null) {
                        R.string.chats_empty
                    } else {
                        R.string.records_empty_for_person
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.attachments, key = { it.id }) { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(text = row.fileName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = stringResource(
                                    R.string.records_attachment_details,
                                    row.mimeType,
                                    Formatter.formatShortFileSize(context, row.sizeBytes),
                                    row.dateLabel,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    R.string.records_attachment_owner,
                                    row.personName ?: stringResource(R.string.records_unassigned),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column {
                            TextButton(onClick = { onExport(row) }) {
                                Text(stringResource(R.string.records_export))
                            }
                            TextButton(onClick = { pendingDelete = row }) {
                                Text(stringResource(R.string.records_delete))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        state.importSummary?.let { summary ->
            ImportSummary(summary)
        }
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.records_delete_confirm_title)) },
            text = { Text(stringResource(R.string.records_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(row)
                    },
                ) {
                    Text(stringResource(R.string.records_delete_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.records_delete_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportSummary(summary: AttachmentImportSummary) {
    if (summary.imported > 0) {
        Text(
            text = stringResource(
                R.string.records_import_summary,
                summary.imported,
                summary.failed,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else if (summary.failed > 0) {
        Text(
            text = stringResource(R.string.records_import_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    if (summary.skipped > 0) {
        Text(
            text = stringResource(R.string.records_import_skipped, summary.skipped),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    if (summary.indexFailed) {
        Text(
            text = stringResource(R.string.records_index_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}
