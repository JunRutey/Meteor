package com.meteor.memory.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.PersonQueries
import com.meteor.memory.core.storage.Relation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val personQueries: PersonQueries,
    private val appLog: AppLog,
) : ViewModel() {

    private val _people = MutableStateFlow(emptyList<com.meteor.memory.core.storage.PersonEntry>())
    val people: StateFlow<List<com.meteor.memory.core.storage.PersonEntry>> = _people.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _people.value = withContext(Dispatchers.IO) { personQueries.list() }
        }
    }

    fun create(displayName: String, relation: Relation?) {
        if (displayName.isBlank()) return
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { personQueries.create(displayName.trim(), relation) }
            appLog.info(TAG, "person created: $id")
            refresh()
        }
    }

    private companion object {
        const val TAG = "People"
    }
}
