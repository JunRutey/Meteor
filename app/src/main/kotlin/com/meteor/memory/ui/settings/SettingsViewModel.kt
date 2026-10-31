package com.meteor.memory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.feature.settings.AppSettings
import com.meteor.memory.feature.settings.DarkMode
import com.meteor.memory.feature.settings.FontScale
import com.meteor.memory.feature.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
    )

    fun setDarkMode(mode: DarkMode) {
        viewModelScope.launch { repository.setDarkMode(mode) }
    }

    fun setFontScale(scale: FontScale) {
        viewModelScope.launch { repository.setFontScale(scale) }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAnimationsEnabled(enabled) }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setAppLockEnabled(enabled) }
    }
}
