package com.meteor.memory.ui.life

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class OnboardingSaveState(
    val saving: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profileStore: BirthdayProfileStore,
    private val settingsRepository: SettingsRepository,
    private val appLog: AppLog,
    private val prewarm: LifeStartupPrewarm,
) : ViewModel() {

    private val _saveState = MutableStateFlow(OnboardingSaveState())
    val saveState = _saveState.asStateFlow()

    fun saveBirthProfile(profile: BirthProfile) {
        if (_saveState.value.saving) return
        _saveState.value = OnboardingSaveState(saving = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { profileStore.save(profile) }
                // The process already ran the prewarm with "no profile"; make
                // the new one available to the life tab without another cold
                // start (ADR-044).
                prewarm.updateAfterSave(profile)
                settingsRepository.setOnboardingCompleted(true)
                appLog.info(TAG, "onboarding completed with birth profile")
                _saveState.value = OnboardingSaveState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "could not save onboarding birth profile", failure)
                _saveState.value = OnboardingSaveState(failed = true)
            }
        }
    }

    /**
     * Birth date is optional: Settings offers the same editor later, and the
     * life tab points at it until a profile exists.
     */
    fun skipBirthProfile() {
        if (_saveState.value.saving) return
        _saveState.value = OnboardingSaveState(saving = true)
        viewModelScope.launch {
            try {
                settingsRepository.setOnboardingCompleted(true)
                appLog.info(TAG, "onboarding completed without birth profile")
                _saveState.value = OnboardingSaveState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "could not finish onboarding without a birth profile", failure)
                _saveState.value = OnboardingSaveState(failed = true)
            }
        }
    }

    private companion object {
        const val TAG = "Onboarding"
    }
}
