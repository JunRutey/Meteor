package com.meteor.memory.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R

@Composable
fun StorageDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: StorageDiagnosticsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val indexSummary by viewModel.indexSummary.collectAsStateWithLifecycle()
    val indexBusy by viewModel.indexBusy.collectAsStateWithLifecycle()
    val indexError by viewModel.indexError.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.diagnostics_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.diagnostics_refresh)) }
                Button(onClick = onBack) { Text(stringResource(R.string.diagnostics_back)) }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = viewModel::rebuildIndex, enabled = !indexBusy) {
                Text(stringResource(R.string.diagnostics_rebuild_index))
            }
            when {
                indexBusy -> Text(
                    text = stringResource(R.string.diagnostics_index_running),
                    style = MaterialTheme.typography.bodySmall,
                )
                indexError != null -> Text(
                    text = stringResource(R.string.diagnostics_index_error, indexError.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                indexSummary != null -> Text(
                    text = stringResource(
                        R.string.diagnostics_index_summary,
                        indexSummary!!.records,
                        indexSummary!!.types,
                        indexSummary!!.persons,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        snapshot?.let { data ->
            Text(
                text = data.rootPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
            Text(
                text = stringResource(R.string.diagnostics_count, data.fileCount),
                style = MaterialTheme.typography.bodySmall,
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(data.files, key = { it.relativePath }) { file ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(text = file.relativePath, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(
                                if (file.looksEncrypted) R.string.diagnostics_encrypted else R.string.diagnostics_unknown,
                                file.sizeBytes,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (file.looksEncrypted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = file.head,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
