package com.meteor.memory.feature.gallery

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** One decrypted thumbnail. Decoding happens outside this module. */
data class GalleryItem(
    val token: String,
    val bitmap: ImageBitmap,
)

/** One album page: a localized date and every picture stored that day. */
data class GalleryDay(
    val label: String,
    val items: List<GalleryItem>,
)

/**
 * Standalone gallery laid out like the system album: pictures grouped by day,
 * the date printed once per group, three per row.
 *
 * Long-press starts a selection; while anything is selected a bar at the bottom
 * offers 删除. Deleting removes the encrypted file, so a diary that referenced it
 * will show a missing image — the confirm dialog says so.
 */
@Composable
fun GalleryScreen(
    days: List<GalleryDay>,
    loading: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = stringResource(R.string.gallery_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (selection.isEmpty() && days.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.gallery_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.gallery_back)) }
        }

        when {
            loading -> Text(
                text = stringResource(R.string.gallery_loading),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )

            days.isEmpty() -> Text(
                text = stringResource(R.string.gallery_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                days.forEachIndexed { index, day ->
                    item(
                        key = "day-$index-${day.label}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        DayHeader(day.label)
                    }
                    items(day.items, key = { it.token }) { item ->
                        GalleryTile(
                            item = item,
                            selected = item.token in selection,
                            selecting = selection.isNotEmpty(),
                            onTap = {
                                if (selection.isNotEmpty()) {
                                    selection = selection.toggle(item.token)
                                } else {
                                    onOpen(item.token)
                                }
                            },
                            onLongPress = { selection = selection.toggle(item.token) },
                        )
                    }
                }
            }
        }

        if (selection.isNotEmpty()) {
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
                        val tokens = selection.toList()
                        selection = emptySet()
                        onDelete(tokens)
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
private fun DayHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun GalleryTile(
    item: GalleryItem,
    selected: Boolean,
    selecting: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            // pointerInput instead of combinedClickable: no experimental opt-in.
            .pointerInput(item.token, selecting) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        Image(
            bitmap = item.bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selected) {
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
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onDelete: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
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

private fun Set<String>.toggle(token: String): Set<String> =
    if (token in this) this - token else this + token

private const val COLUMNS = 3
private val SELECTED_SCRIM = Color(0x66000000)
