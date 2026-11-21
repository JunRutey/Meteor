package com.meteor.memory.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meteor.memory.feature.media.MediaImportLogic
import com.meteor.memory.feature.media.MediaThumb

/**
 * UI-side album kind; `:app` maps `core-storage.MediaKind` onto the first three.
 *
 * [FILE] is an imported document (ADR-065): the former 记录 page merged into the
 * album, so one grid holds pictures, videos, audio and files. A file is not
 * playable — opening it shows the text extracted at import time.
 */
enum class GalleryKind { IMAGE, VIDEO, AUDIO, FILE }

/** UI-side sort switch; `:app` maps `core-storage.AlbumSortMode` onto this. */
enum class GallerySortMode { SOURCE_DATE, IMPORT_DATE }

/** Filter chips shown above the grid. */
enum class GalleryFilter { ALL, IMAGES, VIDEOS, AUDIO, FILES }

/** One album entry: enough to render the grid and open the viewer. */
data class GalleryItem(
    val ref: String,
    val kind: GalleryKind,
    val durationMs: Long? = null,
    val fileName: String? = null,
    val hasThumbnail: Boolean = false,
    /** Imported file size; null for media and legacy images. */
    val sizeBytes: Long? = null,
)

/** One album page: a localized date and every item stored that day. */
data class GalleryDay(
    val label: String,
    val items: List<GalleryItem>,
)

/**
 * Outcome of the last document import, as the grid reports it.
 *
 * Defined here rather than imported from `:app`'s attachment layer because
 * `:feature-gallery` must not know the storage or import types (ADR-052 §5 C);
 * the host maps its own summary onto this.
 */
data class FileImportSummary(
    val imported: Int,
    val failed: Int,
    val skipped: Int,
    val indexFailed: Boolean,
)

/**
 * Standalone album laid out like the system album: pictures, videos and audio
 * grouped by day, the date printed once per group, three per row.
 *
 * Long-press starts a selection; while anything is selected a bar at the bottom
 * offers 删除. Deleting removes the encrypted file, so a diary that referenced it
 * will show a missing media — the confirm dialog says so.
 */
@Composable
fun GalleryScreen(
    days: List<GalleryDay>,
    loading: Boolean,
    sortMode: GallerySortMode,
    onSortModeChange: (GallerySortMode) -> Unit,
    filter: GalleryFilter,
    onFilterChange: (GalleryFilter) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onBack: () -> Unit,
    title: String? = null,
    animationsEnabled: Boolean = true,
    /** Asks the host to open the document picker; null hides the import action. */
    onImportFile: (() -> Unit)? = null,
    importingFile: Boolean = false,
    importProgress: Pair<Int, Int>? = null,
    importSummary: FileImportSummary? = null,
    onDismissImportSummary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GalleryHeader(
            title = title ?: stringResource(R.string.gallery_title),
            showHint = selection.isEmpty() && days.isNotEmpty(),
            sortMode = sortMode,
            onSortModeChange = onSortModeChange,
            onBack = onBack,
            onImportFile = onImportFile,
            importingFile = importingFile,
        )

        FilterChips(filter = filter, onFilterChange = onFilterChange)

        ImportStatus(
            importing = importingFile,
            progress = importProgress,
            summary = importSummary,
            onDismiss = onDismissImportSummary,
        )

        when {
            loading -> PlaceholderCard(text = stringResource(R.string.gallery_loading))

            days.isEmpty() -> PlaceholderCard(
                text = stringResource(
                    if (filter == GalleryFilter.FILES) {
                        R.string.gallery_files_empty
                    } else {
                        R.string.gallery_empty
                    },
                ),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(TILE_GAP),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                days.forEachIndexed { index, day ->
                    item(
                        key = "day-$index-${day.label}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        DayHeader(day.label)
                    }
                    items(day.items, key = { it.ref }) { item ->
                        GalleryTile(
                            item = item,
                            selected = item.ref in selection,
                            selecting = selection.isNotEmpty(),
                            onTap = {
                                if (selection.isNotEmpty()) {
                                    selection = selection.toggle(item.ref)
                                } else {
                                    onOpen(item.ref)
                                }
                            },
                            onLongPress = { selection = selection.toggle(item.ref) },
                            animationsEnabled = animationsEnabled,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = selection.isNotEmpty(),
            enter = if (animationsEnabled) {
                fadeIn(tween(durationMillis = 150, easing = FastOutSlowInEasing)) +
                    slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
                    )
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut(tween(durationMillis = 100, easing = FastOutSlowInEasing)) +
                    slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                    )
            } else {
                ExitTransition.None
            },
        ) {
            SelectionBar(
                count = selection.size,
                onCancel = { selection = emptySet() },
                onDelete = { confirming = true },
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.gallery_delete_title)) },
            text = { Text(text = stringResource(R.string.gallery_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        val refs = selection.toList()
                        selection = emptySet()
                        onDelete(refs)
                    },
                ) {
                    Text(text = stringResource(R.string.gallery_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.gallery_cancel))
                }
            },
        )
    }
}

@Composable
private fun GalleryHeader(
    title: String,
    showHint: Boolean,
    sortMode: GallerySortMode,
    onSortModeChange: (GallerySortMode) -> Unit,
    onBack: () -> Unit,
    onImportFile: (() -> Unit)?,
    importingFile: Boolean,
) {
    val backLabel = stringResource(R.string.gallery_back)
    var sortMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            GalleryIcon(GalleryIconKind.Back, iconSize = 22.dp)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (showHint) {
                Text(
                    text = stringResource(R.string.gallery_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onImportFile != null) {
            val importLabel = stringResource(
                if (importingFile) R.string.gallery_importing_file else R.string.gallery_import_file,
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(enabled = !importingFile, onClick = onImportFile)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = importLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (importingFile) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        Box {
            val sortLabel = stringResource(R.string.gallery_sort_title)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable { sortMenuExpanded = true }
                    .semantics { contentDescription = sortLabel },
                contentAlignment = Alignment.Center,
            ) {
                GalleryIcon(GalleryIconKind.Sort, iconSize = 22.dp)
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_sort_source)) },
                    onClick = {
                        sortMenuExpanded = false
                        onSortModeChange(GallerySortMode.SOURCE_DATE)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gallery_sort_import)) },
                    onClick = {
                        sortMenuExpanded = false
                        onSortModeChange(GallerySortMode.IMPORT_DATE)
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterChips(filter: GalleryFilter, onFilterChange: (GalleryFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            label = stringResource(R.string.gallery_filter_all),
            selected = filter == GalleryFilter.ALL,
            onClick = { onFilterChange(GalleryFilter.ALL) },
        )
        FilterChip(
            label = stringResource(R.string.gallery_filter_images),
            selected = filter == GalleryFilter.IMAGES,
            onClick = { onFilterChange(GalleryFilter.IMAGES) },
        )
        FilterChip(
            label = stringResource(R.string.gallery_filter_videos),
            selected = filter == GalleryFilter.VIDEOS,
            onClick = { onFilterChange(GalleryFilter.VIDEOS) },
        )
        FilterChip(
            label = stringResource(R.string.gallery_filter_audio),
            selected = filter == GalleryFilter.AUDIO,
            onClick = { onFilterChange(GalleryFilter.AUDIO) },
        )
        FilterChip(
            label = stringResource(R.string.gallery_filter_files),
            selected = filter == GalleryFilter.FILES,
            onClick = { onFilterChange(GalleryFilter.FILES) },
        )
    }
}

/** Local chip, mirroring SearchScreen: no Material experimental icon dependency. */
@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.secondary
            },
        )
    }
}

/**
 * Progress and outcome of a document import.
 *
 * The album is a grid, so the banner sits above it and disappears once the
 * result is acknowledged — the former 记录 page could afford a permanent status
 * line, the album cannot.
 */
@Composable
private fun ImportStatus(
    importing: Boolean,
    progress: Pair<Int, Int>?,
    summary: FileImportSummary?,
    onDismiss: () -> Unit,
) {
    if (importing) {
        val (done, total) = progress ?: (0 to 0)
        StatusCard(
            text = stringResource(R.string.gallery_import_progress, done, total),
            error = false,
            onDismiss = null,
        )
        return
    }
    if (summary == null) return

    val messages = buildList {
        if (summary.imported > 0) {
            add(stringResource(R.string.gallery_import_summary, summary.imported, summary.failed))
        } else if (summary.failed > 0) {
            add(stringResource(R.string.gallery_import_failed))
        }
        if (summary.skipped > 0) add(stringResource(R.string.gallery_import_skipped, summary.skipped))
        if (summary.indexFailed) add(stringResource(R.string.gallery_index_failed))
    }
    if (messages.isEmpty()) {
        onDismiss()
        return
    }
    StatusCard(
        text = messages.joinToString("\n"),
        error = summary.imported == 0 || summary.indexFailed,
        onDismiss = onDismiss,
    )
}

@Composable
private fun StatusCard(text: String, error: Boolean, onDismiss: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.gallery_dismiss))
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCard(text: String) {    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Composable
private fun DayHeader(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GalleryTile(
    item: GalleryItem,
    selected: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    animationsEnabled: Boolean,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // pointerInput instead of combinedClickable: no experimental opt-in.
            .pointerInput(item.ref, selecting) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        when (item.kind) {
            GalleryKind.IMAGE -> MediaThumb(
                ref = item.ref,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
            )

            GalleryKind.VIDEO -> {
                MediaThumb(
                    ref = item.ref,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                )
                KindBadge(
                    kind = GalleryIconKind.Video,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                item.durationMs?.let { duration ->
                    DurationBadge(
                        durationMs = duration,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }

            GalleryKind.AUDIO -> AudioCard(item)

            GalleryKind.FILE -> FileCard(item)
        }

        AnimatedVisibility(
            visible = selected,
            enter = if (animationsEnabled) {
                fadeIn(tween(durationMillis = 120)) +
                    scaleIn(initialScale = 0.92f, animationSpec = tween(durationMillis = 160))
            } else {
                EnterTransition.None
            },
            exit = if (animationsEnabled) {
                fadeOut(tween(durationMillis = 90)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(durationMillis = 120))
            } else {
                ExitTransition.None
            },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(SELECTED_SCRIM))
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                GalleryIcon(
                    kind = GalleryIconKind.Check,
                    iconSize = 12.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun KindBadge(kind: GalleryIconKind, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0x88000000)),
        contentAlignment = Alignment.Center,
    ) {
        GalleryIcon(kind = kind, iconSize = 14.dp, tint = Color.White)
    }
}

@Composable
private fun DurationBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(6.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0x88000000),
    ) {
        Text(
            text = MediaImportLogic.formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AudioCard(item: GalleryItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GalleryIcon(GalleryIconKind.Audio, iconSize = 28.dp)
        Text(
            text = item.fileName ?: stringResource(R.string.gallery_kind_audio),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.gallery_selected, count),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.gallery_cancel))
                }
                Button(onClick = onDelete) {
                    Text(text = stringResource(R.string.gallery_delete))
                }
            }
        }
    }
}

private fun Set<String>.toggle(ref: String): Set<String> =
    if (ref in this) this - ref else this + ref

private const val COLUMNS = 3
private val TILE_GAP = 8.dp
private val SELECTED_SCRIM = Color(0x66000000)
