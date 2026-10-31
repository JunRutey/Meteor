package com.meteor.memory.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.log.LogExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface LogUiState {
    data object Idle : LogUiState
    data object Working : LogUiState
    data class Done(val location: String) : LogUiState
    data class Failed(val message: String) : LogUiState
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logExporter: LogExporter,
    private val appLog: AppLog,
) : ViewModel() {

    private val _state = MutableStateFlow<LogUiState>(LogUiState.Idle)
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    fun export() {
        if (_state.value == LogUiState.Working) return
        _state.value = LogUiState.Working

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { logExporter.export() }
            if (result.success && result.location != null) {
                appLog.info(TAG, "log exported to ${result.location}")
                _state.value = LogUiState.Done(result.location)
            } else {
                val message = result.error ?: "unknown"
                appLog.error(TAG, "log export failed: $message")
                _state.value = LogUiState.Failed(message)
            }
        }
    }

    private companion object {
        const val TAG = "LogExport"
    }
}
