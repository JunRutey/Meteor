package com.meteor.memory.ui.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.feature.editor.ImageCompressor
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val moodQueries: MoodQueries,
    private val diaryQueries: DiaryQueries,
    private val appLog: AppLog,
    private val imageStore: ImageStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private val _moods = MutableStateFlow<Map<LocalDate, Mood>>(emptyMap())
    val moods: StateFlow<Map<LocalDate, Mood>> = _moods.asStateFlow()

    /** Days with a diary on disk — drives the ✓✓ next to the selected date. */
    private val _diaryDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val diaryDates: StateFlow<Set<LocalDate>> = _diaryDates.asStateFlow()

    private val _imageDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val imageDates: StateFlow<Set<LocalDate>> = _imageDates.asStateFlow()

    init {
        load()
        refresh()
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

    /** 一次性刷新：已写日记的日期 + 当天有图的日期。 */
    fun refresh() {
        viewModelScope.launch {
            val diary = withContext(Dispatchers.IO) {
                runCatching { diaryQueries.dates().toSet() }.getOrDefault(emptySet())
            }
            val images = withContext(Dispatchers.IO) {
                runCatching {
                    imageStore.list()
                        .map { it.modifiedAt.atZone(ZoneId.systemDefault()).toLocalDate() }
                        .toSet()
                }.getOrDefault(emptySet())
            }
            _diaryDates.value = diary
            _imageDates.value = images
        }
    }

    fun saveImage(date: LocalDate, bytes: ByteArray) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val token = imageStore.save(ImageCompressor.compress(bytes), personId = null)
                    val at = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
                    File(File(context.filesDir, StorageLayout.ROOT_NAME), token)
                        .setLastModified(at.toEpochMilli())
                    token
                }
            }
            saved.fold(
                onSuccess = { appLog.info(TAG, "image saved for $date") },
                onFailure = { appLog.warn(TAG, "image save failed", it) },
            )
            refresh()
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
