package com.meteor.memory.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val moodQueries: MoodQueries,
    private val appLog: AppLog,
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private val _moods = MutableStateFlow<Map<LocalDate, Mood>>(emptyMap())
    val moods: StateFlow<Map<LocalDate, Mood>> = _moods.asStateFlow()

    init {
        load()
    }

    fun previousMonth() = shiftMonth(-1)

    fun nextMonth() = shiftMonth(1)

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun setMood(date: LocalDate, mood: Mood) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { moodQueries.save(date, mood) }
            appLog.debug(TAG, "mood saved $date=${mood.code}")
            _selectedDate.value = date
            load()
        }
    }

    private fun shiftMonth(delta: Long) {
        _month.value = _month.value.plusMonths(delta)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val current = _month.value
            _moods.value = withContext(Dispatchers.IO) {
                moodQueries.range(current.atDay(1), current.atEndOfMonth())
            }
        }
    }

    private companion object {
        const val TAG = "Calendar"
    }
}
