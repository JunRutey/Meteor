package com.meteor.memory.feature.media

import android.Manifest
import android.os.Build
import com.meteor.memory.core.storage.MediaKind

/**
 * Which runtime permissions the in-app picker needs (ADR-052 §4).
 *
 * Android 13 (API 33) replaced `READ_EXTERNAL_STORAGE` with one read permission
 * per media type, so Meteor asks only for the types the caller actually wants:
 * picking a photo should never ask for an audio read it will not use.
 *
 * Pure decision table, so the JVM tests in this module cover it without an
 * Android runtime.
 */
internal object MediaPermissions {

    /**
     * The permissions needed to list [kinds] on a device running [sdkInt].
     *
     * On API 32 and below a single storage read covers every media type.
     */
    fun required(kinds: Set<MediaKind>, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val effective = kinds.ifEmpty { MediaKind.entries.toSet() }
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return effective
            .map {
                when (it) {
                    MediaKind.IMAGE -> Manifest.permission.READ_MEDIA_IMAGES
                    MediaKind.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                    MediaKind.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
                }
            }
            .distinct()
            .sorted()
    }
}
