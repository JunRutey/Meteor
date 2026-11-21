package com.meteor.memory.feature.media

import android.net.Uri
import com.meteor.memory.core.storage.MediaKind
import java.time.Instant

/**
 * One device file the user picked in [MediaPickerScreen] (ADR-052 §4).
 *
 * Carries everything [MediaImporter] needs before it opens the stream, so handing
 * a whole batch to the importer costs no extra `MediaStore` round trips.
 */
data class PickedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: MediaKind,
    val sourceDate: Instant?,
)
