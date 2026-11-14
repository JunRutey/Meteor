package com.meteor.memory.feature.people

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.feature.editor.MarkdownPreview

/** One aggregated record shown on a person's page. */
data class PersonRecordRow(
    val recordId: String,
    val dateLabel: String,
    val typeLabel: String,
    val title: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * Logical aggregation of one person: profile from the file, records from the
 * index. The two live in different places on disk by design (archive v0.5).
 *
 * The profile has **two** text fields (ADR-023): a one-line 备注 and the
 * Markdown 正文. They are shown — and edited — separately, because until now
 * everything the user typed ended up in the same place under the wrong label.
 *
 * This page scrolls as a whole. The old version kept the header fixed and let
 * only the record list scroll, which cut off a long 正文 on short screens.
 */
@Composable
fun PersonDetailScreen(
    displayName: String,
    relation: Relation?,
    tags: List<String>,
    note: String,
    notes: String,
    records: List<PersonRecordRow>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    onEditNote: () -> Unit = onEdit,
    onEditBody: () -> Unit = onEdit,
    resolveImage: (String) -> ImageBitmap? = { null },
    onActivate: (String) -> Unit = {},
    onAddRecord: () -> Unit = {},
    attachmentsError: Boolean = false,
    importingAttachments: Boolean = false,
    importedAttachmentCount: Int? = null,
    failedAttachmentCount: Int = 0,
    skippedAttachmentCount: Int = 0,
    attachmentIndexFailed: Boolean = false,
    onExportAttachment: (String) -> Unit = {},
    exportFailed: Boolean? = null,
    onDelete: () -> Unit = {},
    deleting: Boolean = false,
    deleteFailed: Boolean = false,
) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        DetailTopBar(
            onBack = onBack,
            onEdit = onEdit,
            onRequestDelete = { confirmingDelete = true },
            deleting = deleting,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ProfileHero(
                    displayName = displayName,
                    relation = relation,
                    tags = tags,
                    note = note,
                )
            }

            if (deleteFailed) {
                item { StatusLine(stringResource(R.string.people_delete_failed), error = true) }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.people_note_section),
                    action = stringResource(
                        if (note.isBlank()) R.string.people_note_add else R.string.people_note_edit,
                    ),
                    onAction = onEditNote,
                ) {
                    if (note.isBlank()) {
                        Placeholder(stringResource(R.string.people_note_empty))
                    } else {
                        Text(text = note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.people_body_section),
                    action = stringResource(
                        if (notes.isBlank()) R.string.people_body_add else R.string.people_body_edit,
                    ),
                    onAction = onEditBody,
                ) {
                    if (notes.isBlank()) {
                        Placeholder(stringResource(R.string.people_body_empty))
                    } else {
                        MarkdownPreview(
                            body = notes,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                            resolveImage = resolveImage,
                            onActivate = onActivate,
                        )
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.people_records),
                    action = stringResource(
                        if (importingAttachments) {
                            R.string.people_record_importing
                        } else {
                            R.string.people_record_add
                        },
                    ),
                    onAction = onAddRecord,
                    enabled = !importingAttachments,
                )
            }

            if (attachmentsError) {
                item { StatusLine(stringResource(R.string.people_records_load_failed), error = true) }
            }
            importedAttachmentCount?.let { imported ->
                item {
                    StatusLine(
                        stringResource(
                            R.string.people_record_import_summary,
                            imported,
                            failedAttachmentCount,
                        ),
                    )
                }
            }
            if (skippedAttachmentCount > 0) {
                item {
                    StatusLine(
                        stringResource(
                            R.string.people_record_import_skipped,
                            skippedAttachmentCount,
                        ),
                    )
                }
            }
            if (attachmentIndexFailed) {
                item { StatusLine(stringResource(R.string.people_record_index_failed), error = true) }
            }
            exportFailed?.let { failed ->
                item {
                    StatusLine(
                        message = stringResource(
                            if (failed) {
                                R.string.people_record_export_failed
                            } else {
                                R.string.people_record_export_done
                            },
                        ),
                        error = failed,
                    )
                }
            }

            if (records.isEmpty() && !attachmentsError) {
                item { EmptyCard(stringResource(R.string.people_records_empty)) }
            }

            items(records, key = { it.recordId }) { row ->
                RecordCard(
                    row = row,
                    onExport = { onExportAttachment(row.recordId) },
                )
            }
        }
    }

    if (confirmingDelete) {
        DeleteDialog(
            displayName = displayName,
            deleting = deleting,
            onDismiss = { if (!deleting) confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                onDelete()
            },
        )
    }
}

@Composable
private fun DetailTopBar(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
    deleting: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val backDescription = stringResource(R.string.people_back)
    val editDescription = stringResource(R.string.people_edit)
    val moreDescription = stringResource(R.string.people_more_actions)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
        Text(
            text = stringResource(R.string.people_detail_title),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = onEdit,
            modifier = Modifier
                .size(44.dp)
                .semantics { contentDescription = editDescription },
        ) {
            PersonIcon(
                PersonIconKind.Edit,
                iconSize = 22.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier
                    .size(44.dp)
                    .semantics { contentDescription = moreDescription },
            ) {
                PersonIcon(
                    PersonIconKind.More,
                    iconSize = 22.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.people_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    enabled = !deleting,
                    onClick = {
                        menuExpanded = false
                        onRequestDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileHero(
    displayName: String,
    relation: Relation?,
    tags: List<String>,
    note: String,
) {
    val initial = displayName.trim().firstOrNull()?.toString() ?: "?"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initial,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = displayName.ifBlank { stringResource(R.string.people_detail_unnamed) },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (relation != null) {
                        TagPill(label = relationLabel(relation), emphasized = true)
                    } else if (tags.isEmpty()) {
                        TagPill(label = relationLabel(null), emphasized = false)
                    }
                    tags.forEach { tag -> TagPill(label = tag, emphasized = false) }
                }
                if (note.isNotBlank()) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TagPill(label: String, emphasized: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (emphasized) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    action: String,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(text = action, style = MaterialTheme.typography.labelLarge)
                }
            }
            content()
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onAction, enabled = enabled) {
            Text(text = action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecordCard(
    row: PersonRecordRow,
    onExport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = row.title ?: row.typeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.title != null) {
                        Text(
                            text = row.typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        row.sizeBytes?.let { size ->
                            Text(
                                text = Formatter.formatShortFileSize(LocalContext.current, size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = row.dateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.title != null) {
                TextButton(onClick = onExport) {
                    Text(stringResource(R.string.people_record_export))
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(message: String, error: Boolean = false) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
}

@Composable
private fun Placeholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeleteDialog(
    displayName: String,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_delete_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.people_delete_confirm_body,
                    displayName.ifBlank { stringResource(R.string.people_detail_unnamed) },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    text = stringResource(
                        if (deleting) R.string.people_deleting else R.string.people_delete_confirm_action,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.people_cancel))
            }
        },
    )
}
