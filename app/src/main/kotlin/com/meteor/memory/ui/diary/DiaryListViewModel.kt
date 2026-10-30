package com.meteor.memory.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.time.TimeModule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

data class DiaryRow(
    val date: LocalDate,
    val label: String,
    val preview: String,
    val hasContent: Boolean,
)

@HiltViewModel
class DiaryListViewModel @Inject constructor(
    private val diaryQueries: DiaryQueries,
    private val timeModule: TimeModule,
) : ViewModel() {

    private val _rows = MutableStateFlow<List<DiaryRow>>(emptyList())
    val rows: StateFlow<List<DiaryRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val locale = Locale.getDefault()
            val loaded = withContext(Dispatchers.IO) {
                diaryQueries.dates().take(MAX_ROWS).map { date ->
                    val body = diaryQueries.read(date)?.body.orEmpty()
                    DiaryRow(
                        date = date,
                        label = timeModule.formatDate(date, locale),
                        preview = previewOf(body),
                        hasContent = body.isNotBlank(),
                    )
                }
            }
            _rows.value = loaded
            _loading.value = false
        }
    }

    private fun previewOf(body: String): String {
        val flat = body.replace('\n', ' ').trim()
        return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS) + "…"
    }

    private companion object {
        const val MAX_ROWS = 100
        const val PREVIEW_CHARS = 60
    }
}
