package com.meteor.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.ui.about.DocsLine

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
    notes: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(text = stringResource(R.string.update_prompt_title, versionName)) },
        text = {
            Column {
                Text(text = stringResource(R.string.update_prompt_body, versionName))
                // Fixed-height scroll area: a long release note must not turn
                // the confirmation into a full-screen wall of text, and the
                // dialog must stay short enough to keep all three answers
                // visible. Notes are rendered as plain lines, never HTML.
                Text(
                    text = stringResource(R.string.update_prompt_notes_title),
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (notes.isBlank()) {
                    Text(
                        text = stringResource(R.string.update_prompt_notes_empty),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(top = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                RoundedCornerShape(14.dp),
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        notes.lineSequence().forEach { line -> DocsLine(line) }
                    }
                }
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

/**
 * Foreground download progress (1.7.1).
 *
 * Replaces the confirmation dialog in place after 「立即更新」. The same
 * download keeps running as a notification if the person sends it to the
 * background with the button, back or by leaving the app; if the dialog is
 * still in front when the bytes finish, [RootViewModel] opens the installer.
 */
@Composable
internal fun UpdateProgressDialog(
    progress: UpdateDownloadProgress,
    onDismiss: () -> Unit,
) {
    val determinate = progress.totalBytes > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.update_progress_title, progress.versionName)) },
        text = {
            Column {
                if (determinate) {
                    LinearProgressIndicator(
                        progress = { progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.update_progress_percent, progress.percent),
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.update_progress_preparing),
                        modifier = Modifier.padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.update_progress_body),
                    modifier = Modifier.padding(top = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.update_progress_background))
            }
        },
    )
}
