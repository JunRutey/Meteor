package com.meteor.memory.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meteor.memory.R

/**
 * "Export logs" entry rendered in the About section. Logs land in
 * `Download/Meteor/` so they can be attached to a bug report without a cable.
 */
@Composable
fun LogActions(
    viewModel: LogViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = viewModel::export,
            enabled = state != LogUiState.Working,
        ) {
            Text(text = stringResource(R.string.log_export))
        }

        when (val current = state) {
            is LogUiState.Done -> Text(
                text = stringResource(R.string.log_export_done, current.location),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is LogUiState.Failed -> Text(
                text = stringResource(R.string.log_export_failed, current.message),
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
