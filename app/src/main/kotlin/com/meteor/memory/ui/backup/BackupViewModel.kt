package com.meteor.memory.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.backup.BackupModule
import com.meteor.memory.core.backup.BackupResult
import com.meteor.memory.core.backup.ImportResult
import com.meteor.memory.core.log.AppLog
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
    private val appLog: AppLog,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun export(destination: Uri) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true)

        viewModelScope.launch {
            val outcome = when (val result = backupModule.exportPlaintext(destination)) {
                is BackupResult.Success -> {
                    appLog.info(TAG, "export ok: ${result.fileCount} files, ${result.bytes} bytes")
                    BackupOutcome.Exported(result.fileCount, result.bytes)
                }
                is BackupResult.Failure -> {
                    appLog.error(TAG, "export failed", result.cause)
                    BackupOutcome.Failed(result.message)
                }
            }
            _state.value = BackupUiState(busy = false, outcome = outcome)
        }
    }

    fun import(source: Uri) {
        if (_state.value.busy) return
        _state.value = BackupUiState(busy = true)

        viewModelScope.launch {
            val outcome = when (val result = backupModule.importFrom(source)) {
                is ImportResult.Success -> {
                    appLog.info(TAG, "import ok: ${result.fileCount} files")
                    BackupOutcome.Imported(result.fileCount)
                }
                is ImportResult.Failure -> {
                    appLog.error(TAG, "import failed", result.cause)
                    BackupOutcome.Failed(result.message)
                }
            }
            _state.value = BackupUiState(busy = false, outcome = outcome)
        }
    }

    fun clearOutcome() {
        _state.value = _state.value.copy(outcome = null)
    }

    private companion object {
        const val TAG = "Backup"
    }
}
