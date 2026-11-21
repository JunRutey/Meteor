package com.meteor.memory.feature.media

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meteor.memory.core.storage.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * In-app media picker (ADR-052 §4).
 *
 * Lists the device's photos/videos/audio from `MediaStore`, grouped by the month
 * the file was taken, in a multi-select thumbnail grid. Requesting read access is
 * the screen's own job: Android 13+ needs one permission per media type, API 32
 * and below a single storage read. A refusal is explained with a retry entry
 * instead of an empty grid, and the continue button reports the selection.
 *
 * The screen only *returns* selections; importing and encrypting them is the
 * caller's job through [MediaImporter].
 */
@Composable
fun MediaPickerScreen(
    kinds: Set<MediaKind>,
    onConfirm: (List<PickedMedia>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissions = remember(kinds) { MediaPermissions.required(kinds) }

    var granted by remember(permissions) {
        mutableStateOf(permissions.all { isGranted(context, it) })
    }
    var askedOnce by remember { mutableStateOf(false) }
    var months by remember { mutableStateOf<List<DeviceMediaMonth>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<PickedMedia>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        askedOnce = true
        granted = permissions.all { permission ->
            result[permission] ?: isGranted(context, permission)
        }
    }

    // Ask on first entry, but never in a loop: a denial falls through to the
    // explanation card with its own retry button.
    LaunchedEffect(permissions) {
        if (!granted && !askedOnce) launcher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(granted, kinds) {
        if (!granted) {
            months = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val query = DeviceMediaQuery(context.contentResolver)
        val zone = ZoneId.systemDefault()
        months = withContext(Dispatchers.IO) {
            query.months(query.query(kinds), zone)
        }
        loading = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        PickerHeader(
            onBack = onBack,
            showHint = selection.isEmpty() && months.isNotEmpty(),
        )

        when {
            !granted -> PermissionGate(
                askedOnce = askedOnce,
                onRequest = { launcher.launch(permissions.toTypedArray()) },
                onOpenSettings = { openAppSettings(context) },
                modifier = Modifier.weight(1f),
            )

            loading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.media_picker_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            months.isEmpty() -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.media_picker_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(TILE_GAP),
                    horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    months.forEach { month ->
                        item(
                            key = "month-${month.yearMonth}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            MonthHeader(month.yearMonth)
                        }
                        items(month.items, key = { it.uri.toString() }) { item ->
                            PickerTile(
                                item = item,
                                selected = selection.any { it.uri == item.uri },
                                onClick = {
                                    selection = if (selection.any { it.uri == item.uri }) {
                                        selection.filterNot { it.uri == item.uri }
                                    } else {
                                        selection + item
                                    }
                                },
                            )
                        }
                    }
                }

                ConfirmBar(
                    count = selection.size,
                    onCancel = onBack,
                    onConfirm = { onConfirm(selection) },
                )
            }
        }
    }
}

@Composable
private fun PickerHeader(onBack: () -> Unit, showHint: Boolean) {
    val backLabel = stringResource(R.string.media_picker_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            MediaGlyph(MediaGlyphKind.Back, iconSize = 22.dp)
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.media_picker_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (showHint) {
                Text(
                    text = stringResource(R.string.media_picker_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(yearMonth: YearMonth) {
    val pattern = stringResource(R.string.media_picker_month_pattern)
    val unknownLabel = stringResource(R.string.media_picker_month_unknown)
    val label = if (yearMonth.year <= 0) {
        unknownLabel
    } else {
        runCatching {
            DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                .format(yearMonth.atDay(1))
        }.getOrDefault(yearMonth.toString())
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PickerTile(item: PickedMedia, selected: Boolean, onClick: () -> Unit) {
    val kindLabel = when (item.kind) {
        MediaKind.IMAGE -> stringResource(R.string.media_picker_kind_image)
        MediaKind.VIDEO -> stringResource(R.string.media_picker_kind_video)
        MediaKind.AUDIO -> stringResource(R.string.media_picker_kind_audio)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${item.displayName} $kindLabel" },
    ) {
        when (item.kind) {
            MediaKind.IMAGE -> DeviceMediaThumb(uri = item.uri, modifier = Modifier.fillMaxSize())
            MediaKind.VIDEO -> {
                DeviceMediaThumb(uri = item.uri, modifier = Modifier.fillMaxSize())
                KindGlyph(MediaGlyphKind.Video, Modifier.align(Alignment.BottomStart))
            }

            MediaKind.AUDIO -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaGlyph(
                    MediaGlyphKind.Audio,
                    iconSize = 34.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
                MediaGlyph(
                    MediaGlyphKind.Check,
                    iconSize = 12.dp,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun KindGlyph(kind: MediaGlyphKind, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(KIND_SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        MediaGlyph(kind, iconSize = 13.dp, tint = Color.White)
    }
}

@Composable
private fun PermissionGate(
    askedOnce: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.media_picker_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.media_picker_permission_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (askedOnce) {
                    Text(
                        text = stringResource(R.string.media_picker_permission_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequest, shape = RoundedCornerShape(18.dp)) {
                        Text(text = stringResource(R.string.media_picker_permission_retry))
                    }
                    // After a permanent denial the system stops showing the
                    // dialog, so the only way forward is the app settings page.
                    if (askedOnce) {
                        TextButton(onClick = onOpenSettings) {
                            Text(text = stringResource(R.string.media_picker_permission_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmBar(count: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                text = stringResource(R.string.media_picker_selected, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.media_picker_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = count > 0,
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(text = stringResource(R.string.media_picker_confirm))
                }
            }
        }
    }
}

private fun isGranted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private const val GRID_COLUMNS = 3
private val TILE_GAP = 6.dp
private val SELECTED_SCRIM = Color(0x66000000)
private val KIND_SCRIM = Color(0x99000000)
