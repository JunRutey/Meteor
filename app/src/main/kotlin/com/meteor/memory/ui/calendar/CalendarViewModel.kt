package com.meteor.memory.ui.calendar

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.DiaryQueries
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.MoodQueries
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.core.storage.StorageRouter
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val moodQueries: MoodQueries,
    private val diaryQueries: DiaryQueries,
    private val appLog: AppLog,
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

    private val _moods = MutableStateFlow<Map<LocalDate, List<Mood>>>(emptyMap())
    val moods: StateFlow<Map<LocalDate, List<Mood>>> = _moods.asStateFlow()

    /** Days with a diary on disk — drives the ✓✓ next to the selected date. */
    private val _diaryDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val diaryDates: StateFlow<Set<LocalDate>> = _diaryDates.asStateFlow()

    /**
     * Serializes mood read-modify-write cycles. Without it two quick taps read
     * the same old selection and the second save silently drops the first mood.
     */
    private val moodWriteMutex = Mutex()

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

    /**
     * Toggles one mood on [date]: tapping an already selected mood removes it,
     * a new mood is appended while the day holds fewer than [Mood.MAX_PER_DAY].
     */
    fun toggleMood(date: LocalDate, mood: Mood) {
        viewModelScope.launch {
            moodWriteMutex.withLock {
                runCatching {
                    val current = withContext(Dispatchers.IO) { moodQueries.read(date) }
                    val next = Mood.toggled(current, mood)
                    // A fourth mood is refused here as well as in the UI.
                    if (next != current) {
                        val stored = withContext(Dispatchers.IO) {
                            moodQueries.save(date, next)
                            moodRecord(date)
                        }
                        appLog.debug(TAG, "moods saved $date=${next.map { it.code }}")
                        _selectedDate.value = date
                        _moods.value = readMoods(_month.value)
                        // Mood rows are searchable by their label, so the index
                        // has to change now instead of waiting for the next launch.
                        runCatching {
                            if (stored == null) {
                                searchService.removeRecord(moodSourcePath(date))
                            } else {
                                searchService.indexDocument(stored)
                            }
                        }.onFailure { appLog.warn(TAG, "mood index update failed", it) }
                    }
                }.onFailure { appLog.warn(TAG, "mood save failed", it) }
            }
        }
    }

    /**
     * 一次性刷新：已写日记的日期 + 本月心情。
     *
     * 「当天有没有图」不再扫描：ADR-068 把「存张记忆」从日历里拿掉了，图片一律从
     * 相册导入，日历不需要知道哪一天有图片。
     */
    fun refresh() {
        viewModelScope.launch {
            val diary = withContext(Dispatchers.IO) {
                runCatching { diaryQueries.dates().toSet() }.getOrDefault(emptySet())
            }
            _diaryDates.value = diary
            moodWriteMutex.withLock {
                // Re-read the month inside the lock: the user may have swiped
                // while the diary IO above was running.
                _moods.value = readMoods(_month.value)
            }
        }
    }

    private fun shiftMonth(delta: Long) {
        _month.value = _month.value.plusMonths(delta)
        load()
    }

    private fun load() {
        viewModelScope.launch {
            moodWriteMutex.withLock {
                _moods.value = readMoods(_month.value)
            }
        }
    }

    private suspend fun readMoods(current: YearMonth): Map<LocalDate, List<Mood>> =
        withContext(Dispatchers.IO) {
            moodQueries.range(current.atDay(1), current.atEndOfMonth())
        }

    /** The saved record, or null when the day no longer has any mood. */
    private fun moodRecord(date: LocalDate): Document? {
        val file = storageRouter.route(null, RecordType.MOOD, date)
        if (!file.isFile) return null
        return runCatching { storageRouter.read(file) }.getOrNull()
    }

    /** Archive-relative path used by the search index. */
    private fun moodSourcePath(date: LocalDate): String =
        storageRouter.route(null, RecordType.MOOD, date)
            .relativeTo(File(context.filesDir, StorageLayout.ROOT_NAME))
            .invariantSeparatorsPath

    private companion object {
        const val TAG = "Calendar"
        const val HOME_TAB_DIARY = 1
        const val KEY_HOME_TAB = "homeTab"
    }
}
