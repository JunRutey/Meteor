package com.meteor.memory.feature.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the viewer should say when one of its actions failed. */
enum class ImageViewerError {
    None,
    DeleteFailed,
    ShareFailed,
}

/**
 * Full-size view of one decrypted image, reached from the gallery or a link.
 *
 * The picture is centered inside the space left between the header and the
 * action bar and always uses [ContentScale.Fit], so it never hangs from the top
 * of the screen. The bottom card offers share and delete; both work on the
 * already decrypted image and do their I/O in the view model.
 */
@Composable
fun ImageViewerScreen(
    bitmap: ImageBitmap?,
    caption: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    loading: Boolean = false,
    animationsEnabled: Boolean = true,
    deleting: Boolean = false,
    sharing: Boolean = false,
    dateLabel: String? = null,
    error: ImageViewerError = ImageViewerError.None,
    modifier: Modifier = Modifier,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ViewerHeader(caption = caption, onBack = onBack)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> {
                    if (animationsEnabled) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = stringResource(R.string.gallery_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                bitmap == null -> MissingImageCard()

                else -> {
                    // Keep the bare Image here: AnimatedVisibility's ColumnScope
                    // overload cannot be resolved inside this Box, and a static
                    // centered image is what the viewer actually needs.
                    Image(
                        bitmap = bitmap,
                        contentDescription = caption,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }

        ViewerActionBar(
            dateLabel = dateLabel,
            error = error,
            enabled = bitmap != null,
            deleting = deleting,
            sharing = sharing,
            onShare = onShare,
            onDelete = { confirmingDelete = true },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.gallery_delete_confirm_title)) },
            text = { Text(stringResource(R.string.gallery_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.gallery_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.gallery_cancel))
                }
            },
        )
    }
}

@Composable
private fun ViewerHeader(caption: String, onBack: () -> Unit) {
    val backLabel = stringResource(R.string.gallery_back)
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            GalleryIcon(GalleryIconKind.Back, iconSize = 22.dp)
        }
        Text(
            text = caption,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MissingImageCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ViewerActionBar(
    dateLabel: String?,
    error: ImageViewerError,
    enabled: Boolean,
    deleting: Boolean,
    sharing: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (dateLabel != null) {
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (error != ImageViewerError.None) {
                Text(
                    text = stringResource(
                        when (error) {
                            ImageViewerError.DeleteFailed -> R.string.gallery_delete_failed
                            ImageViewerError.ShareFailed -> R.string.gallery_share_failed
                            ImageViewerError.None -> R.string.gallery_share_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onShare,
                    enabled = enabled && !deleting && !sharing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                ) {
                    GalleryIcon(GalleryIconKind.Share, iconSize = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.gallery_share))
                }
                Button(
                    onClick = onDelete,
                    enabled = enabled && !deleting && !sharing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    GalleryIcon(GalleryIconKind.Delete, iconSize = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (deleting) R.string.gallery_deleting else R.string.gallery_delete,
                        ),
                    )
                }
            }
        }
    }
}
