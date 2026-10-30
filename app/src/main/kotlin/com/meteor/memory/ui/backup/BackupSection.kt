package com.meteor.memory.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R

private const val BACKUP_MIME = "application/zip"

/**
 * Manual backup entry points rendered inside the settings screen.
 *
 * The plaintext warning is mandatory (archive §7 / ADR-004): the export really
 * is readable Markdown, so this is the only moment to warn the user.
 */
@Composable
fun BackupActions(
    viewModel: BackupViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmExport by remember { mutableStateOf(false) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri -> if (uri != null) viewModel.export(uri) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.import(uri) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmExport = true },
                enabled = !state.busy,
            ) {
                Text(text = stringResource(R.string.backup_export))
            }
            OutlinedButton(
                onClick = { openDocument.launch(arrayOf(BACKUP_MIME, "application/octet-stream")) },
                enabled = !state.busy,
            ) {
                Text(text = stringResource(R.string.backup_import))
            }
        }

        when (val outcome = state.outcome) {
            is BackupOutcome.Exported -> ResultText(
                stringResource(R.string.backup_export_done, outcome.files, outcome.bytes / 1024),
            )
            is BackupOutcome.Imported -> ResultText(
                stringResource(R.string.backup_import_done, outcome.files),
            )
            is BackupOutcome.Failed -> Text(
                text = outcome.message ?: stringResource(R.string.backup_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(text = stringResource(R.string.backup_warning_title)) },
            text = { Text(text = stringResource(R.string.backup_warning_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExport = false
                        createDocument.launch("meteor-backup.zip")
                    },
                ) {
                    Text(text = stringResource(R.string.backup_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExport = false }) {
                    Text(text = stringResource(R.string.backup_warning_cancel))
                }
            },
        )
    }
}

@Composable
private fun ResultText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
