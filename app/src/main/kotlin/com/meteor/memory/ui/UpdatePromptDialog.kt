package com.meteor.memory.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R

/**
 * 「发现新版本，要不要现在下载」prompt (ADR-072).
 *
 * Shown by the app shell the moment the update check lands on a newer release,
 * and only above the unlocked, onboarded UI — the shell composes it inside the
 * same branch as the NavHost, so it can never sit over the lock gate.
 *
 * Three answers: download now, decide later (default dismissal, asked again on
 * the next check), or skip this version persistently.
 */
@Composable
internal fun UpdatePromptDialog(
    versionName: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(text = stringResource(R.string.update_prompt_title, versionName)) },
        text = {
            Column {
                Text(text = stringResource(R.string.update_prompt_body))
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(text = stringResource(R.string.update_prompt_skip))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) {
                Text(text = stringResource(R.string.update_prompt_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(
                    text = stringResource(R.string.update_prompt_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
