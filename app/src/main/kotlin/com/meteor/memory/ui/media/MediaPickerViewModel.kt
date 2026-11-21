package com.meteor.memory.ui.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.MediaStore
import com.meteor.memory.feature.media.MediaImporter
import com.meteor.memory.feature.media.PickedMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Imports one picker batch for the standalone media-picker destination (ADR-059).
 *
 * Selection stays inside `:feature-media`; the import (metadata, thumbnail,
 * encryption) happens here so the destination can return plain media ids to
 * whichever screen opened it. The editor screens therefore never import in
 * place and the picker is never drawn on top of the editor.
 */
@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStore,
    private val appLog: AppLog,
) : ViewModel() {

    /**
     * Imports [picked] under [personId] (null = self) and reports the stored ids.
     *
     * A failed file is logged and skipped; the rest of the batch still lands, so
     * one unreadable video cannot throw away the pictures chosen with it.
     */
    fun importPicked(
        picked: List<PickedMedia>,
        personId: String?,
        onDone: (List<String>) -> Unit,
    ) {
        if (picked.isEmpty()) {
            onDone(emptyList())
            return
        }
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) {
                val importer = MediaImporter(context.contentResolver, mediaStore)
                picked.mapNotNull { media ->
                    runCatching { importer.import(media.uri, personId) }
                        .onFailure { appLog.warn(TAG, "media import failed: ${media.mimeType}", it) }
                        .getOrNull()
                        ?.id
                }
            }
            if (ids.isEmpty()) appLog.warn(TAG, "media import produced no stored entries")
            onDone(ids)
        }
    }

    private companion object {
        const val TAG = "MediaPicker"
    }
}
