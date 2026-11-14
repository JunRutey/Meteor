package com.meteor.memory.ui.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R
import com.meteor.memory.core.log.LogExportError

private const val LOG_MIME_TYPE = "text/plain"

/**
 * "Export logs" entry rendered in the diagnostics section. Tapping it opens the
 * system file picker, so the user decides where the diagnostic text is saved
 * (Downloads, Drive, a USB drive, ...). No storage permission is involved.
 */
@Composable
fun LogActions(
    viewModel: LogViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LOG_MIME_TYPE),
    ) { uri ->
        // A null URI means the picker was dismissed; keep the last status quiet.
        if (uri != null) viewModel.export(uri)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { createDocument.launch(viewModel.suggestedFileName()) },
            enabled = state != LogUiState.Working,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.log_export),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        when (val current = state) {
            is LogUiState.Done -> Text(
                text = stringResource(R.string.log_export_done, current.fileName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is LogUiState.Failed -> Text(
                text = stringResource(
                    when (current.error) {
                        LogExportError.OPEN_FAILED -> R.string.log_export_failed_open
                        LogExportError.WRITE_FAILED -> R.string.log_export_failed_write
                        LogExportError.READ_FAILED -> R.string.log_export_failed_read
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            LogUiState.Working -> Text(
                text = stringResource(R.string.log_export_working),
                style = MaterialTheme.typography.bodySmall,
            )
            LogUiState.Idle -> Unit
        }
    }
}
