package com.meteor.memory.feature.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
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
 */
@Composable
fun PersonDetailScreen(
    displayName: String,
    relation: Relation?,
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
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(text = displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = relationLabel(relation),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.people_edit)) }
                TextButton(onClick = onBack) { Text(stringResource(R.string.people_back)) }
            }
        }

        HorizontalDivider()

        SectionHeader(
            title = stringResource(R.string.people_note_section),
            action = stringResource(
                if (note.isBlank()) R.string.people_note_add else R.string.people_note_edit,
            ),
            onAction = onEditNote,
        )
        if (note.isBlank()) {
            Placeholder(stringResource(R.string.people_note_empty))
        } else {
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))

        SectionHeader(
            title = stringResource(R.string.people_body_section),
            action = stringResource(
                if (notes.isBlank()) R.string.people_body_add else R.string.people_body_edit,
            ),
            onAction = onEditBody,
        )
        if (notes.isBlank()) {
            Placeholder(stringResource(R.string.people_body_empty))
        } else {
            MarkdownPreview(
                body = notes,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(horizontal = 4.dp),
                resolveImage = resolveImage,
                onActivate = onActivate,
            )
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))

        SectionHeader(
            title = stringResource(R.string.people_records),
            action = stringResource(
                if (importingAttachments) R.string.people_record_importing else R.string.people_record_add,
            ),
            onAction = onAddRecord,
            enabled = !importingAttachments,
        )

        if (attachmentsError) {
            Placeholder(stringResource(R.string.people_records_load_failed))
        } else if (records.isEmpty()) {
            Placeholder(stringResource(R.string.people_records_empty))
        }

        importedAttachmentCount?.let { imported ->
            Text(
                text = stringResource(
                    R.string.people_record_import_summary,
                    imported,
                    failedAttachmentCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (skippedAttachmentCount > 0) {
            Text(
                text = stringResource(R.string.people_record_import_skipped, skippedAttachmentCount),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (attachmentIndexFailed) {
            Placeholder(stringResource(R.string.people_record_index_failed))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(records, key = { it.recordId }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = row.title ?: row.typeLabel,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    )
                                }
                            }
                            Text(text = row.dateLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.title != null) {
                            TextButton(onClick = { onExportAttachment(row.recordId) }) {
                                Text(stringResource(R.string.people_record_export))
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onAction, enabled = enabled) {
            Text(text = action, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
