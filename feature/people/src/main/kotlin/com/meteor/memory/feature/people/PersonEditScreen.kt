package com.meteor.memory.feature.people

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.feature.editor.MarkdownField

/**
 * Editing an existing person (ADR-020, ADR-023, ADR-024, ADR-046).
 *
 * Layout rules, in priority order:
 *
 * 1. The header (back icon + 标题 + 保存) is **pinned**. The keyboard used to push
 *    the save action off the screen, which forced the user to close the IME first.
 * 2. The page itself never scrolls and never moves: the identity card sits above,
 *    the body editor takes whatever height is left. When the IME appears only the
 *    editor shrinks, so nothing jumps.
 * 3. The identity card *may* scroll, but only inside a capped box: on a short
 *    screen that keeps the editor usable instead of squeezing it to zero.
 * 4. While the body editor holds focus and the IME is visible the cap shrinks
 *    to a small strip and animates back on blur/IME dismissal (ADR-046). Typing
 *    Chinese in a 44 %-tall card left the editor with almost nothing; the
 *    name/tags/note fields stay reachable by scrolling the strip, and tapping
 *    them restores the tall card because the body loses focus.
 *
 * 备注 and 正文 are separate fields because they answer different questions —
 * "怎么称呼、记着点什么" versus "关于这个人的正文".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonEditScreen(
    displayName: String,
    relation: Relation?,
    tags: List<String>,
    availableTags: List<String>,
    note: String,
    notes: String,
    saving: Boolean,
    insertingImage: Boolean,
    saved: Boolean,
    error: String?,
    onNameChanged: (String) -> Unit,
    onRelationChanged: (Relation?) -> Unit,
    onTagsChanged: (List<String>) -> Unit,
    onAddTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit,
    onInsertLink: (String, String?) -> Unit,
    onPickImage: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    missingImages: Set<String> = emptySet(),
    resolveImage: (String) -> ImageBitmap? = { null },
) {
    var previewing by rememberSaveable { mutableStateOf(false) }
    // Only body focus collapses the identity card: opening the keyboard for the
    // name or note field must keep the whole card usable. IME visibility is
    // combined so dismissing the keyboard also restores the card even if the
    // field still holds focus (ADR-046).
    var bodyFocused by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val collapseIdentityCard = bodyFocused && imeVisible

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The card animates between "tall enough to edit 称呼/标签/备注" and a
        // small strip that leaves almost all of the remaining height to 正文.
        val fieldsCapFraction = if (collapseIdentityCard) {
            FIELDS_FOCUSED_FRACTION
        } else {
            FIELDS_MAX_FRACTION
        }
        val fieldsMaxHeight by animateDpAsState(
            targetValue = maxHeight * fieldsCapFraction,
            animationSpec = tween(durationMillis = FIELDS_HEIGHT_ANIMATION_MS),
            label = "personIdentityMaxHeight",
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                saved = saved,
                saving = saving,
                insertingImage = insertingImage,
                canSave = displayName.isNotBlank(),
                onSave = onSave,
                onBack = onBack,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = fieldsMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = onNameChanged,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            label = { Text(text = stringResource(R.string.people_label_name)) },
                            placeholder = { Text(text = stringResource(R.string.people_name_hint)) },
                        )

                        PersonTagEditor(
                            relation = relation,
                            tags = tags,
                            availableTags = availableTags,
                            onSelectionChanged = { selected, derived ->
                                onTagsChanged(selected)
                                onRelationChanged(derived)
                            },
                            onAddTag = onAddTag,
                            onDeleteTag = onDeleteTag,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = note,
                            onValueChange = onNoteChanged,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            label = { Text(text = stringResource(R.string.people_label_note)) },
                            placeholder = { Text(text = stringResource(R.string.people_note_hint)) },
                        )

                        error?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.people_body_section),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Box(modifier = Modifier.weight(1f)) {
                    MarkdownField(
                        text = notes,
                        previewing = previewing,
                        onTextChanged = onNotesChanged,
                        onPreviewToggle = { previewing = !previewing },
                        onInsertLink = onInsertLink,
                        onPickImage = onPickImage,
                        onRemoveImage = onRemoveImage,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = { Text(text = stringResource(R.string.people_body_hint)) },
                        missingImages = missingImages,
                        resolveImage = resolveImage,
                        onFocusChanged = { bodyFocused = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    saved: Boolean,
    saving: Boolean,
    insertingImage: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val backDescription = stringResource(R.string.people_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = backDescription },
        ) {
            PersonIcon(PersonIconKind.Back, iconSize = 22.dp)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = stringResource(R.string.people_edit_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    saving -> stringResource(R.string.people_saving)
                    insertingImage -> stringResource(R.string.people_adding_image)
                    saved -> stringResource(R.string.people_saved)
                    else -> stringResource(R.string.people_edit_subtitle)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (saved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onSave,
            enabled = !saving && !insertingImage && canSave,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(
                    if (saving) R.string.people_saving else R.string.people_save,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Share of the available height the identity block may claim while it is not
 * collapsed. Everything above the editor is fixed furniture; keeping it under
 * half guarantees the editor stays usable with the keyboard open, even on a
 * short screen.
 */
private const val FIELDS_MAX_FRACTION = 0.44f

/**
 * Cap while 正文 has focus and the IME is visible (ADR-046). 0.18 leaves the
 * editor the large majority of the post-IME height; the identity card stays
 * scrollable so 称呼/标签/备注 can still be edited by tapping them (which blurs
 * the body and expands it again).
 */
private const val FIELDS_FOCUSED_FRACTION = 0.18f

/** Smooth but short enough that the keyboard animation dominates. */
private const val FIELDS_HEIGHT_ANIMATION_MS = 180
