package com.meteor.memory.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.index.IndexModule
import com.meteor.memory.core.index.QueryPlan
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.time.TimeModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

data class SearchRow(
    val recordId: String,
    val type: RecordType,
    val date: LocalDate,
    val dateLabel: String,
    val snippet: String,
)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data class Results(val rows: List<SearchRow>) : SearchUiState
    data class Failed(val message: String) : SearchUiState
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val indexModule: IndexModule,
    private val timeModule: TimeModule,
    private val appLog: AppLog,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val state: StateFlow<SearchUiState> = _query
        // Typing should not trigger a query per keystroke.
        .debounce(DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { raw -> runSearch(raw) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SearchUiState.Idle,
        )

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private suspend fun runSearch(raw: String): SearchUiState {
        if (raw.isBlank()) return SearchUiState.Idle

        return runCatching {
            val hits = indexModule.search(
                QueryPlan(
                    ftsExpression = raw,
                    maxHits = MAX_HITS,
                    snippetChars = SNIPPET_CHARS,
                ),
            )
            appLog.debug(TAG, "query=\"$raw\" hits=${hits.size}")

            if (hits.isEmpty()) {
                SearchUiState.Empty
            } else {
                val locale = Locale.getDefault()
                SearchUiState.Results(
                    hits.map { hit ->
                        val date = hit.timestamp.atZone(ZoneId.systemDefault()).toLocalDate()
                        SearchRow(
                            recordId = hit.recordId,
                            type = hit.type,
                            date = date,
                            dateLabel = timeModule.formatDate(date, locale),
                            snippet = hit.snippet,
                        )
                    },
                )
            }
        }.getOrElse { throwable ->
            appLog.warn(TAG, "search failed for \"$raw\"", throwable)
            SearchUiState.Failed(throwable.message ?: throwable::class.java.simpleName)
        }
    }

    private companion object {
        const val TAG = "Search"
        const val DEBOUNCE_MS = 250L
        const val MAX_HITS = 20
        const val SNIPPET_CHARS = 160
    }
}
