package com.meteor.memory.feature.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Full-size view of one decrypted image, reached from the gallery or a link. */
@Composable
fun ImageViewerScreen(
    bitmap: ImageBitmap?,
    caption: String,
    onBack: () -> Unit,
    loading: Boolean = false,
    animationsEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = caption, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text(stringResource(R.string.gallery_back)) }
        }

        if (loading) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (animationsEnabled) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = stringResource(R.string.gallery_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (bitmap == null) {
            Text(
                text = stringResource(R.string.gallery_missing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                AnimatedVisibility(
                    visible = true,
                    enter = if (animationsEnabled) {
                        fadeIn(tween(durationMillis = 220, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.985f,
                                animationSpec = tween(
                                    durationMillis = 260,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                    } else {
                        EnterTransition.None
                    },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = caption,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}
