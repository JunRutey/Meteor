package com.meteor.memory.ui.log

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.log.LogExportError
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
    data class Done(val fileName: String) : LogUiState
    data class Failed(val error: LogExportError) : LogUiState
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logExporter: LogExporter,
    private val appLog: AppLog,
) : ViewModel() {

    private val _state = MutableStateFlow<LogUiState>(LogUiState.Idle)
    val state: StateFlow<LogUiState> = _state.asStateFlow()

    /** Offered to the system picker; the user still chooses the folder/name. */
    fun suggestedFileName(): String = logExporter.suggestedFileName()

    fun export(destination: Uri) {
        if (_state.value == LogUiState.Working) return
        _state.value = LogUiState.Working

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { logExporter.export(destination) }
            val name = result.displayName
            if (result.success && name != null) {
                appLog.info(TAG, "log exported as $name")
                _state.value = LogUiState.Done(name)
            } else {
                val error = result.error ?: LogExportError.WRITE_FAILED
                appLog.error(TAG, "log export failed: $error")
                _state.value = LogUiState.Failed(error)
            }
        }
    }

    private companion object {
        const val TAG = "LogExport"
    }
}
