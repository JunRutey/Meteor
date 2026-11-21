package com.meteor.memory.feature.people

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meteor.memory.core.storage.AlbumKind
import com.meteor.memory.feature.media.MediaThumb
import java.util.Locale

/**
 * One item owned by a person, as the detail page renders it (ADR-052 §5 E).
 *
 * [ref] is the **complete** Markdown URL stored in the album entry
 * (`meteor://media/<id>` for new media, `meteor://image/<token>` for a picture
 * imported before ADR-052, `meteor://file/<id>` for an imported document added by
 * ADR-065). The page never parses it: the host turns it into a route, and
 * `:feature-media`'s thumbnail resolves it.
 */
data class PersonMediaRow(
    val ref: String,
    val kind: AlbumKind,
    /** Original file name; null for legacy images, which never stored one. */
    val title: String? = null,
    /** Video/audio length in milliseconds; null when unknown. */
    val durationMs: Long? = null,
)

/** Result of the last "add media" run, shown under the section header. */
data class PersonMediaImportSummary(
    val imported: Int,
    val failed: Int,
)

/**
 * Media strip of a person's page: the pictures, videos and audio filed under
 * `数字遗产/媒体/<person_id>/` plus the legacy pictures under
 * `数字遗产/图片/<person_id>/`, exactly as `AlbumRepository` merges them.
 *
 * A horizontal strip rather than a grid: the detail page is one vertical
 * `LazyColumn`, and a nested vertical grid would fight it for the scroll. The
 * tiles keep the same 14dp rounded language as the record cards above.
 *
 * Deleting is a **long press** on a tile — the gesture the album already uses —
 * and always asks first, because the file is gone for good while bodies that
 * reference it are deliberately not rewritten (ADR-037).
 */
@Composable
internal fun PersonMediaSection(
    personId: String,
    rows: List<PersonMediaRow>,
    onOpenMedia: (String) -> Unit,
    onAddMedia: (String) -> Unit,
    onDeleteMedia: (String) -> Unit,
    modifier: Modifier = Modifier,
    importing: Boolean = false,
    importSummary: PersonMediaImportSummary? = null,
    loadFailed: Boolean = false,
    deleteFailed: Boolean = false,
) {
    var pendingDelete by remember { mutableStateOf<PersonMediaRow?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.people_media_section),
            action = stringResource(
                if (importing) R.string.people_media_importing else R.string.people_media_add,
            ),
            onAction = { onAddMedia(personId) },
            enabled = !importing,
        )

        if (loadFailed) {
            StatusLine(stringResource(R.string.people_media_load_failed), error = true)
        }
        if (deleteFailed) {
            StatusLine(stringResource(R.string.people_media_delete_failed), error = true)
        }
        importSummary?.let { summary ->
            StatusLine(
                stringResource(
                    R.string.people_media_import_summary,
                    summary.imported,
                    summary.failed,
                ),
            )
        }

        when {
            rows.isNotEmpty() -> LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                items(rows, key = { it.ref }) { item ->
                    PersonMediaTile(
                        item = item,
                        onOpen = { onOpenMedia(item.ref) },
                        onLongPress = { pendingDelete = item },
                    )
                }
            }

            !loadFailed -> EmptyCard(stringResource(R.string.people_media_empty))
        }
    }

    pendingDelete?.let { item ->
        MediaDeleteDialog(
            item = item,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDeleteMedia(item.ref)
            },
        )
    }
}

@Composable
private fun PersonMediaTile(
    item: PersonMediaRow,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kindLabel = mediaKindLabel(item.kind)
    val openLabel = stringResource(R.string.people_media_open_description, kindLabel)
    val duration = PersonMediaFormat.duration(item.durationMs)

    Box(
        modifier = Modifier
            .size(TILE_SIZE)
            .clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = openLabel }
            // pointerInput instead of combinedClickable: no experimental opt-in,
            // and it matches the album's tap / long-press pair.
            .pointerInput(item.ref) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        MediaThumb(
            ref = item.ref,
            modifier = Modifier.fillMaxSize(),
            contentDescription = openLabel,
        )

        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = BADGE_ALPHA),
        ) {
            Text(
                text = kindLabel,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }

        if (duration != null && item.kind != AlbumKind.IMAGE) {
            val durationLabel = stringResource(R.string.people_media_duration_description, duration)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .semantics { contentDescription = durationLabel },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = BADGE_ALPHA),
            ) {
                Text(
                    text = duration,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Confirmation before the ciphertext is deleted.
 *
 * The wording has to say the quiet part out loud: bodies that referenced this
 * file keep their text and start showing a missing-media placeholder, because
 * nothing in the archive rewrites Markdown references (ADR-037 §3).
 */
@Composable
private fun MediaDeleteDialog(
    item: PersonMediaRow,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.people_media_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.title?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.people_media_untitled),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.people_media_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.people_media_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.people_cancel))
            }
        },
    )
}

@Composable
private fun mediaKindLabel(kind: AlbumKind): String = stringResource(
    when (kind) {
        AlbumKind.IMAGE -> R.string.people_media_kind_image
        AlbumKind.VIDEO -> R.string.people_media_kind_video
        AlbumKind.AUDIO -> R.string.people_media_kind_audio
        AlbumKind.FILE -> R.string.people_media_kind_file
    },
)

/**
 * Duration badges are digits and colons only, so they need no string resource
 * and stay identical in every language. Pure, so it is unit-tested.
 */
internal object PersonMediaFormat {

    /** `m:ss`, or `h:mm:ss` past an hour; null when the length is unknown. */
    fun duration(durationMs: Long?): String? {
        val millis = durationMs?.takeIf { it > 0 } ?: return null
        val totalSeconds = millis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}

private val TILE_SIZE = 96.dp
private const val BADGE_ALPHA = 0.82f
