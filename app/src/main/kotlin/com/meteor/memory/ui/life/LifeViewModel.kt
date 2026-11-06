package com.meteor.memory.ui.life

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface LifePageState {
    data object Loading : LifePageState
    data class Ready(val profile: BirthProfile, val metrics: BirthdayMetrics) : LifePageState

    /** No birth profile yet: first launch was skipped or settings cleared it. */
    data object Missing : LifePageState
    data object Failed : LifePageState
}

@HiltViewModel
class LifeViewModel @Inject constructor(
    private val profileStore: BirthdayProfileStore,
    private val appLog: AppLog,
) : ViewModel() {

    private val lunarCalendar = ChineseLunarCalendar()
    private val _state = MutableStateFlow<LifePageState>(LifePageState.Loading)
    val state = _state.asStateFlow()
    private var ticker: Job? = null

    fun refresh() {
        ticker?.cancel()
        ticker = viewModelScope.launch(Dispatchers.Default) {
            // Re-entering the screen keeps the last values on screen instead of
            // flashing the spinner for one frame while the profile is re-read.
            if (_state.value !is LifePageState.Ready) _state.value = LifePageState.Loading
            try {
                val profile = withContext(Dispatchers.IO) { profileStore.read() }
                if (profile == null) {
                    _state.value = LifePageState.Missing
                    return@launch
                }
                var zone = ZoneId.systemDefault()
                var cachedDate: LocalDate? = null
                var cachedBirthday: LocalDate? = null

                while (isActive) {
                    val now = Instant.now()
                    val currentZone = ZoneId.systemDefault()
                    if (currentZone != zone) {
                        zone = currentZone
                        cachedDate = null
                    }
                    val today = now.atZone(zone).toLocalDate()
                    val birthdayAlreadyPassed = cachedBirthday
                        ?.atStartOfDay(zone)
                        ?.toInstant()
                        ?.isBefore(now) == true
                    if (today != cachedDate || birthdayAlreadyPassed) {
                        cachedBirthday = BirthdayAgeCalculator.nextBirthday(
                            profile = profile,
                            now = now,
                            zone = zone,
                            lunarCalendar = lunarCalendar,
                        )
                        cachedDate = today
                    }
                    _state.value = LifePageState.Ready(
                        profile = profile,
                        metrics = BirthdayAgeCalculator.calculate(
                            profile = profile,
                            now = now,
                            zone = zone,
                            nextBirthday = requireNotNull(cachedBirthday),
                        ),
                    )
                    delay(1_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "could not load or update birth profile", failure)
                _state.value = LifePageState.Failed
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TAG = "LifeClock"
    }
}
