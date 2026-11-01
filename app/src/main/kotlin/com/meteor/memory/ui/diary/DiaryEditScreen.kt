package com.meteor.memory.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meteor.memory.R
import com.meteor.memory.feature.editor.MarkdownField

@Composable
fun DiaryEditScreen(
    state: DiaryEditorState,
    onTextChanged: (String) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
) {
    // Purely a view concern: the record never stores whether it was last shown
    // as preview.
    var previewing by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
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
                if (state.isInsertingImage) {
                    Text(
                        text = stringResource(R.string.diary_adding_image),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.diary_back))
            }
        }

        MarkdownField(
            text = state.text,
            previewing = previewing,
            onTextChanged = onTextChanged,
            onPreviewToggle = { previewing = !previewing },
            onInsertLink = onInsertLink,
            onPickImage = onPickImage,
            onRemoveImage = onRemoveImage,
            modifier = Modifier.weight(1f),
            placeholder = { Text(text = stringResource(R.string.diary_hint)) },
            missingImages = state.missingImages,
            resolveImage = resolveImage,
            onActivate = onActivate,
        )

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSave,
            enabled = !state.isSaving && !state.isInsertingImage,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    when {
                        state.isSaving -> R.string.diary_saving
                        state.isInsertingImage -> R.string.diary_adding_image
                        else -> R.string.diary_save
                    },
                ),
            )
        }
    }
}
