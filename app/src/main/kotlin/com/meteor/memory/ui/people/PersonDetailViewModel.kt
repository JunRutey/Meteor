package com.meteor.memory.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.Relation
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.time.TimeModule
import com.meteor.memory.ui.search.PersonRecord
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class PersonRecordView(
    val recordId: String,
    val type: RecordType,
    val dateLabel: String,
)

data class PersonViewState(
    val displayName: String = "",
    val relation: Relation? = null,
    val notes: String = "",
    val records: List<PersonRecordView> = emptyList(),
    val loaded: Boolean = false,
)

@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val personQueries: PersonQueries,
    private val searchService: SearchService,
    private val timeModule: TimeModule,
) : ViewModel() {

    private val personId: String = checkNotNull(savedStateHandle.get<String>(ARG_PERSON_ID))

    private val _state = MutableStateFlow(PersonViewState())
    val state: StateFlow<PersonViewState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val locale = Locale.getDefault()
            val profile = withContext(Dispatchers.IO) { personQueries.read(personId) }
            val records = withContext(Dispatchers.IO) { searchService.recordsForPerson(personId) }

            _state.value = PersonViewState(
                displayName = profile?.displayName ?: personId.take(SHORT_ID),
                relation = profile?.relation,
                notes = profile?.notes.orEmpty(),
                records = records.map { it.toView(locale) },
                loaded = true,
            )
        }
    }

    private fun PersonRecord.toView(locale: Locale): PersonRecordView {
        val date = timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
        return PersonRecordView(
            recordId = recordId,
            type = type,
            dateLabel = timeModule.formatDate(date, locale),
        )
    }

    companion object {
        const val ARG_PERSON_ID = "personId"
        private const val SHORT_ID = 8
    }
}

/** Fallback profile for a directory that exists but has no readable profile yet. */
internal fun PersonProfile?.displayOr(personId: String): String =
    this?.displayName ?: personId.take(8)
