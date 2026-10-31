package com.meteor.memory.ui.people

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.feature.editor.EditorModule
import com.meteor.memory.feature.editor.MarkdownEditing
import com.meteor.memory.ui.common.decodeImages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class PersonEditState(
    val displayName: String = "",
    val relation: Relation? = null,
    val notes: String = "",
    val images: Map<String, ImageBitmap> = emptyMap(),
    /** Referenced images whose bytes exist but produced no bitmap. */
    val missingImages: Set<String> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

/**
 * Writes a person's profile back to `<person_id>/profile.md` (ADR-020).
 *
 * The notes field is a Markdown body, so it is edited through the same
 * `:feature-editor` surface as a diary entry: images go to
 * `数字遗产/图片/<person_id>/` and land in the body as `meteor://image/…`.
 */
@HiltViewModel
class PersonEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personQueries: PersonQueries,
    private val editorModule: EditorModule,
    private val imageStore: ImageStore,
    private val storageRouter: StorageRouter,
    private val appLog: AppLog,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle.get<String>(ARG_PERSON_ID))

    private val _state = MutableStateFlow(PersonEditState())
    val state: StateFlow<PersonEditState> = _state.asStateFlow()

    /** Images removed from the notes; their files go away only after a save. */
    private val pendingDeletes = mutableSetOf<String>()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { personQueries.read(personId) }.getOrNull()
            }
            _state.update {
                it.copy(
                    displayName = profile?.displayName ?: personId.take(SHORT_ID),
                    relation = profile?.relation,
                    notes = profile?.notes.orEmpty(),
                )
            }
            refreshImages(_state.value.notes)
        }
    }

    fun onNameChanged(value: String) {
        _state.update { it.copy(displayName = value, saved = false, error = null) }
    }

    fun onRelationChanged(value: Relation?) {
        _state.update { it.copy(relation = value, saved = false) }
    }

    fun onNotesChanged(value: String) {
        _state.update { it.copy(notes = value, saved = false, error = null) }
    }

    fun imageFor(token: String): ImageBitmap? = _state.value.images[token]

    fun insertImage(bytes: ByteArray) {
        runCatching { editorModule.insertImage(document(), bytes, personId) }
            .onSuccess { updated ->
                _state.update { it.copy(notes = updated.body, saved = false) }
                refreshImages(updated.body)
            }
            .onFailure { appLog.warn(TAG, "insert image failed: $personId", it) }
    }

    fun insertLink(target: String, label: String?) {
        if (target.isBlank()) return
        val updated = editorModule.insertLink(document(), target, label)
        _state.update { it.copy(notes = updated.body, saved = false) }
    }

    /** Drops one image from the notes and schedules its file for deletion. */
    fun removeImage(token: String) {
        val current = _state.value
        val body = MarkdownEditing.removeImage(current.notes, token)
        if (body == current.notes) return

        pendingDeletes += token
        _state.update {
            it.copy(
                notes = body,
                images = it.images - token,
                missingImages = it.missingImages - token,
                saved = false,
            )
        }
        appLog.debug(TAG, "image detached, will delete on save: $token")
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val name = current.displayName.trim()
        // The button is disabled for a blank name; guard anyway so a profile file
        // can never lose the one field PersonProfileCodec requires.
        if (name.isEmpty()) return

        _state.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    personQueries.update(personId, name, current.relation, current.notes)
                }
            }
            result.fold(
                onSuccess = { appLog.info(TAG, "person saved: $personId") },
                onFailure = { appLog.error(TAG, "person save failed: $personId", it) },
            )
            if (result.isSuccess) purgeDeletedImages()

            _state.update {
                it.copy(
                    saving = false,
                    saved = result.isSuccess,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    private suspend fun purgeDeletedImages() {
        if (pendingDeletes.isEmpty()) return
        val tokens = pendingDeletes.toList()
        pendingDeletes.clear()
        withContext(Dispatchers.IO) {
            tokens.forEach { token ->
                runCatching { imageStore.delete(token) }
                    .onSuccess { deleted -> appLog.info(TAG, "image deleted=$deleted $token") }
                    .onFailure { appLog.warn(TAG, "image delete failed: $token", it) }
            }
        }
    }

    private fun refreshImages(body: String) {
        val known = _state.value.images.keys + _state.value.missingImages
        val tokens = MarkdownEditing.imagesOf(body)
            .map { it.token }
            .filter { it !in known }
        if (tokens.isEmpty()) return

        viewModelScope.launch {
            val decoded = decodeImages(imageStore, tokens)
            if (decoded.missing.isNotEmpty()) {
                appLog.warn(TAG, "images not decodable: ${decoded.missing.size}")
            }
            _state.update {
                it.copy(
                    images = it.images + decoded.bitmaps,
                    missingImages = it.missingImages + decoded.missing,
                )
            }
        }
    }

    /** Shell document so the editor module can address this person's image folder. */
    private fun document(): Document = Document(
        file = storageRouter.route(personId, RecordType.PROFILE, LocalDate.now()),
        personId = personId,
        type = RecordType.PROFILE,
        timestamp = Instant.now(),
        source = RecordSource.USER,
        body = _state.value.notes,
    )

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val TAG = "PersonEdit"
        private const val SHORT_ID = 8
    }
}
