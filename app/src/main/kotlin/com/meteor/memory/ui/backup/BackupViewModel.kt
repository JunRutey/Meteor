package com.meteor.memory.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.backup.BackupResult
import com.meteor.memory.core.backup.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BackupOutcome {
    data class Exported(val files: Int, val bytes: Long) : BackupOutcome
    data class Imported(val files: Int) : BackupOutcome
    data class Failed(val message: String?) : BackupOutcome
}

data class BackupUiState(
    val busy: Boolean = false,
    val outcome: BackupOutcome? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupModule: BackupModule,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun export(destination: Uri) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true)

        viewModelScope.launch {
            val outcome = when (val result = backupModule.exportPlaintext(destination)) {
                is BackupResult.Success -> BackupOutcome.Exported(result.fileCount, result.bytes)
                is BackupResult.Failure -> BackupOutcome.Failed(result.message)
            }
            _state.value = BackupUiState(busy = false, outcome = outcome)
        }
    }

    fun import(source: Uri) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true)

        viewModelScope.launch {
            val outcome = when (val result = backupModule.importFrom(source)) {
                is ImportResult.Success -> BackupOutcome.Imported(result.fileCount)
                is ImportResult.Failure -> BackupOutcome.Failed(result.message)
            }
            _state.value = BackupUiState(busy = false, outcome = outcome)
        }
    }

    fun clearOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }
}
