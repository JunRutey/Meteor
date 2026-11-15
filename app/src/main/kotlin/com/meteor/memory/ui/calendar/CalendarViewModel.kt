package com.meteor.memory.ui.calendar

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.feature.editor.ImageCompressor
import com.meteor.memory.ui.search.SearchService
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
    private val savedStateHandle: SavedStateHandle,
    private val moodQueries: MoodQueries,
    private val diaryQueries: DiaryQueries,
    private val appLog: AppLog,
    private val imageStore: ImageStore,
    private val searchService: SearchService,
    private val storageRouter: StorageRouter,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(LocalDate.now())
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    /** Home tab: 0 = life, 1 = diary. Kept here so search can switch tabs. */
    private val _homeTab = MutableStateFlow(savedStateHandle.get<Int>(KEY_HOME_TAB) ?: 0)
    val homeTab: StateFlow<Int> = _homeTab.asStateFlow()

    private val _moods = MutableStateFlow<Map<LocalDate, Mood>>(emptyMap())
    val moods: StateFlow<Map<LocalDate, Mood>> = _moods.asStateFlow()

    /** Days with a diary on disk — drives the ✓✓ next to the selected date. */
    private val _diaryDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val diaryDates: StateFlow<Set<LocalDate>> = _diaryDates.asStateFlow()

    private val _imageDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val imageDates: StateFlow<Set<LocalDate>> = _imageDates.asStateFlow()

    init {
        refresh()
    }

    fun previousMonth() = shiftMonth(-1)

    fun nextMonth() = shiftMonth(1)

    /** 回到今天：必要时切回本月，再选中今天。 */
    fun goToToday() {
        val today = LocalDate.now()
        _selectedDate.value = today
        val current = YearMonth.from(today)
        if (_month.value != current) {
            _month.value = current
            load()
        }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun selectHomeTab(tab: Int) {
        if (_homeTab.value == tab) return
        _homeTab.value = tab
        savedStateHandle[KEY_HOME_TAB] = tab
    }

    /**
     * Focuses [date] on the home calendar — used when a mood search result is
     * opened. Switches to the diary tab and moves the month when the date is
     * outside the month currently shown.
     */
    fun showDate(date: LocalDate) {
        selectHomeTab(HOME_TAB_DIARY)
        _selectedDate.value = date
        val target = YearMonth.from(date)
        if (_month.value != target) {
            _month.value = target
            load()
        }
    }

    fun setMood(date: LocalDate, mood: Mood) {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                moodQueries.save(date, mood)
                runCatching {
                    storageRouter.read(storageRouter.route(null, RecordType.MOOD, date))
                }.getOrNull()
            }
            appLog.debug(TAG, "mood saved $date=${mood.code}")
            _selectedDate.value = date
            load()
            // Mood rows are searchable by their label, so a new mood has to
            // reach the index now instead of waiting for the next launch.
            if (stored != null) {
                runCatching { searchService.indexDocument(stored) }
                    .onFailure { appLog.warn(TAG, "mood index update failed", it) }
            }
        }
    }

    /** 一次性刷新：已写日记的日期 + 当天有图的日期。 */
    fun refresh() {
        viewModelScope.launch {
            val current = _month.value
            val moods = withContext(Dispatchers.IO) {
                moodQueries.range(current.atDay(1), current.atEndOfMonth())
            }
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
            _moods.value = moods
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
            loadMoods(_month.value)
        }
    }

    private suspend fun loadMoods(current: YearMonth) {
        _moods.value = withContext(Dispatchers.IO) {
            moodQueries.range(current.atDay(1), current.atEndOfMonth())
        }
    }

    private companion object {
        const val TAG = "Calendar"
        const val HOME_TAB_DIARY = 1
        const val KEY_HOME_TAB = "homeTab"
    }
}
