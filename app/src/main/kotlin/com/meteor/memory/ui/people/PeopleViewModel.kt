package com.meteor.memory.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.PersonProfileCodec
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.feature.people.PeopleTagLibrary
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val personQueries: PersonQueries,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
) : ViewModel() {

    private val _people = MutableStateFlow(emptyList<com.meteor.memory.core.storage.PersonEntry>())
    val people: StateFlow<List<com.meteor.memory.core.storage.PersonEntry>> = _people.asStateFlow()

    /** Remembered custom tags, selectable in every person's tag row. */
    private val _customTags = MutableStateFlow(emptyList<String>())
    val customTags: StateFlow<List<String>> = _customTags.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { personQueries.list() }
            _people.value = entries

            val stored = withContext(Dispatchers.IO) {
                runCatching { settingsRepository.settings.first().peopleTags }
                    .getOrDefault(emptySet<String>())
            }
            // Only the remembered library feeds the create dialog. Labels that
            // exist on some other profile must not crawl back in after a delete.
            // Union with the in-memory list so a just-added tag survives a
            // refresh that races the DataStore write.
            _customTags.update { current ->
                PeopleTagLibrary.available(current, stored)
            }
        }
    }

    fun create(displayName: String, relation: Relation?, tags: List<String>) {
        if (displayName.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                personQueries.create(displayName.trim(), relation, tags = tags)
            }
            appLog.info(TAG, "person created: $id")
            refresh()
        }
    }

    /** Remembers a tag immediately and persists it for future create/edit rows. */
    fun addTag(raw: String) {
        val tag = PersonProfileCodec.normalizeTags(listOf(raw)).firstOrNull() ?: return
        _customTags.update { current -> (current + tag).distinct().sorted() }

        viewModelScope.launch {
            runCatching { settingsRepository.addPeopleTag(tag) }
                .onFailure { appLog.warn(TAG, "people tag persist failed", it) }
        }
    }

    /**
     * Forgets a tag in the library. Profiles that already store it are left
     * untouched; the create dialog drops it from its own selection locally.
     */
    fun removeTag(raw: String) {
        val tag = PersonProfileCodec.normalizeTags(listOf(raw)).firstOrNull() ?: return
        _customTags.update { current -> current - tag }

        viewModelScope.launch {
            runCatching { settingsRepository.removePeopleTag(tag) }
                .onFailure { appLog.warn(TAG, "people tag delete failed", it) }
        }
    }

    private companion object {
        const val TAG = "People"
    }
}
