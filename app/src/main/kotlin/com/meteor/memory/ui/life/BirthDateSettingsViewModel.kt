package com.meteor.memory.ui.life

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BirthDateSettingsState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val initialProfile: BirthProfile? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class BirthDateSettingsViewModel @Inject constructor(
    private val profileStore: BirthdayProfileStore,
    private val appLog: AppLog,
) : ViewModel() {

    private val _state = MutableStateFlow(BirthDateSettingsState())
    val state: StateFlow<BirthDateSettingsState> = _state.asStateFlow()

    fun load() {
        if (_state.value.saving) return
        _state.value = _state.value.copy(loading = true, loadFailed = false)
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { profileStore.read() }
                _state.value = _state.value.copy(loading = false, initialProfile = profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "could not read birth profile", failure)
                _state.value = _state.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun save(profile: BirthProfile) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { profileStore.save(profile) }
                _state.value = _state.value.copy(
                    saving = false,
                    initialProfile = profile,
                    saved = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.error(TAG, "could not save birth profile", failure)
                _state.value = _state.value.copy(saving = false, saveFailed = true)
            }
        }
    }

    private companion object {
        const val TAG = "BirthDateSettings"
    }
}
