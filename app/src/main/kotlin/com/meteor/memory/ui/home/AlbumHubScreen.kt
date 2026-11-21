package com.meteor.memory.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meteor.memory.R

/**
 * 总入口「相册」(ADR-054): a segmented top bar with two sections —
 * **相册** (the media grid) and **记录** (the imported file records that used
 * to be `ChatsScreen`). The original `图片` entry now lands here.
 *
 * Each segment is rendered from a `@Composable` slot supplied by the caller,
 * so this screen knows nothing about `GalleryViewModel` or `ChatsViewModel` and
 * stays pure presentation.
 */
@Composable
fun AlbumHubScreen(
    mediaContent: @Composable () -> Unit,
    recordsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSegment by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        AlbumHubSegments(
            selected = selectedSegment,
            onSelect = { selectedSegment = it },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedSegment == 0) mediaContent() else recordsContent()
        }
    }
}

@Composable
private fun AlbumHubSegments(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentButton(
            label = stringResource(R.string.album_hub_tab_media),
            selected = selected == 0,
            onClick = { onSelect(0) },
            modifier = Modifier.weight(1f),
        )
        SegmentButton(
            label = stringResource(R.string.album_hub_tab_records),
            selected = selected == 1,
            onClick = { onSelect(1) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = shape,
                )
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
