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
    private val prewarm: LifeStartupPrewarm,
) : ViewModel() {

    private val lunarCalendar = ChineseLunarCalendar()

    // The cold-start prewarm already resolved the profile and its next birthday
    // (ADR-044), so the first frame can be `Ready` instead of a spinner. The
    // snapshot is only trusted when it belongs to today and to this timezone;
    // `refresh()` still re-reads the store to catch a birthday changed in
    // Settings while the process was alive.
    private val _state = MutableStateFlow(seededState())
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
                // A snapshot seeded for the previous profile must not stay on
                // screen while the new one is computed; the seed is only there
                // to avoid a spinner when nothing changed.
                val current = _state.value
                if (current is LifePageState.Ready && current.profile != profile) {
                    _state.value = LifePageState.Loading
                }

                var zone = ZoneId.systemDefault()
                val initialNow = Instant.now()
                val initialToday = initialNow.atZone(zone).toLocalDate()
                val reusable = prewarm.snapshot?.takeIf { snapshot ->
                    snapshot.profile == profile &&
                        snapshot.nextBirthday != null &&
                        snapshot.zone == zone &&
                        snapshot.capturedAt.atZone(zone).toLocalDate() == initialToday
                }
                var cachedDate: LocalDate? = if (reusable != null) initialToday else null
                var cachedBirthday: LocalDate? = reusable?.nextBirthday

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

    /** Initial page state from the prewarm snapshot, or a normal loading state. */
    private fun seededState(): LifePageState {
        val snapshot = prewarm.snapshot ?: return LifePageState.Loading
        val zone = ZoneId.systemDefault()
        // A "no profile yet" snapshot can be out of date for a moment (the user
        // may have just finished onboarding or saved a date in Settings), so it
        // is not worth seeding a possibly wrong Missing frame; the store read
        // that follows is cheap. Seeding is for the expensive lunar result.
        val profile = snapshot.profile ?: return LifePageState.Loading
        val nextBirthday = snapshot.nextBirthday ?: return LifePageState.Loading
        if (snapshot.zone != zone) return LifePageState.Loading
        if (snapshot.capturedAt.atZone(zone).toLocalDate() != LocalDate.now(zone)) {
            return LifePageState.Loading
        }
        return runCatching {
            LifePageState.Ready(
                profile = profile,
                metrics = BirthdayAgeCalculator.calculate(
                    profile = profile,
                    now = Instant.now(),
                    zone = zone,
                    nextBirthday = nextBirthday,
                ),
            )
        }.getOrDefault(LifePageState.Loading)
    }

    private companion object {
        const val TAG = "LifeClock"
    }
}
