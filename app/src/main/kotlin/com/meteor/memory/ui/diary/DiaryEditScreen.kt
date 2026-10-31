package com.meteor.memory.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.meteor.memory.R
import com.meteor.memory.feature.editor.EditorToolbar
import com.meteor.memory.feature.editor.MarkdownPreview

@Composable
fun DiaryEditScreen(
    state: DiaryEditorState,
    isPreviewing: Boolean,
    onTogglePreview: () -> Unit,
    onTextChanged: (String) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
) {
    var showLinkDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = state.dateLabel, style = MaterialTheme.typography.titleLarge)
                state.signedAtLabel?.let { stamped ->
                    Text(
                        text = stringResource(R.string.diary_signed_at, stamped),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.saved) {
                    Text(
                        text = stringResource(R.string.diary_saved),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.diary_back))
            }
        }

        EditorToolbar(
            isPreviewing = isPreviewing,
            onLink = { showLinkDialog = true },
            onImage = onPickImage,
            onTogglePreview = onTogglePreview,
        )

        if (isPreviewing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                MarkdownPreview(body = state.text, resolveImage = resolveImage)
            }
        } else {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(text = stringResource(R.string.diary_hint)) },
            )
        }

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (state.isSaving) R.string.diary_saving else R.string.diary_save,
                ),
            )
        }
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onConfirm = { target, label ->
                showLinkDialog = false
                onInsertLink(target, label)
            },
        )
    }
}

@Composable
private fun LinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit,
) {
    var target by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.diary_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.diary_link_target)) },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.diary_link_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(target.trim(), label.trim().takeIf { it.isNotEmpty() }) },
                enabled = target.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.diary_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.diary_link_cancel))
            }
        },
    )
}
