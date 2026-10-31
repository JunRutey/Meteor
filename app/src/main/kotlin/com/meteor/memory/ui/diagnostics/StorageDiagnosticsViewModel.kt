package com.meteor.memory.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.StorageLayout
import com.meteor.memory.ui.search.SearchIndexSummary
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StoredFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val looksEncrypted: Boolean,
    val head: String,
)

data class StorageSnapshot(
    val rootPath: String,
    val fileCount: Int,
    val files: List<StoredFileInfo>,
)

data class IndexSummary(
    val records: Int,
    val types: Int,
    val persons: Int,
)/**
 * DEBUG ONLY. Reads the private directory as raw bytes so the encrypted form can
 * be inspected on-device, and rebuilds the FTS index so v0.2 can be verified
 * without a cable or a debugger.
 */
@HiltViewModel
class StorageDiagnosticsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val searchService: SearchService,
    private val appLog: AppLog,
) : ViewModel() {

    private val root = File(context.filesDir, StorageLayout.ROOT_NAME)

    private val _snapshot = MutableStateFlow<StorageSnapshot?>(null)
    val snapshot: StateFlow<StorageSnapshot?> = _snapshot.asStateFlow()

    private val _indexSummary = MutableStateFlow<SearchIndexSummary?>(null)
    val indexSummary: StateFlow<SearchIndexSummary?> = _indexSummary.asStateFlow()

    private val _indexBusy = MutableStateFlow(false)
    val indexBusy: StateFlow<Boolean> = _indexBusy.asStateFlow()

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _snapshot.value = withContext(Dispatchers.IO) {
                val all = if (root.isDirectory) {
                    root.walkTopDown().filter { it.isFile }.toList()
                } else {
                    emptyList()
                }
                StorageSnapshot(
                    rootPath = root.absolutePath,
                    fileCount = all.size,
                    files = all.sortedByDescending { it.lastModified() }.take(MAX_FILES).map { it.describe() },
                )
            }
        }
    }

    fun rebuildIndex() {
        if (_indexBusy.value) return
        _indexBusy.value = true
        _indexError.value = null

        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { searchService.rebuild() } }
            result.fold(
                onSuccess = { appLog.info(TAG, "index rebuilt") },
                onFailure = { appLog.error(TAG, "index rebuild failed", it) },
            )
            _indexError.value = result.exceptionOrNull()?.message

            if (result.isSuccess) {
                _indexSummary.value = withContext(Dispatchers.IO) { searchService.summary() }
            }
            _indexBusy.value = false
        }
    }

    private fun File.describe(): StoredFileInfo {
        // Image payloads are raw ciphertext with no frontmatter, so the
        // `aes-gcm-v1` marker never appears in them (archive §5.2).
        val isImage = name.endsWith(".enc", ignoreCase = true)
        val head = if (isImage) {
            ""
        } else {
            runCatching {
                val raw = readBytes().take(HEAD_BYTES).toByteArray()
                String(raw, Charsets.UTF_8).filter { it == '\n' || it.code in 32..126 }
            }.getOrDefault("")
        }
        return StoredFileInfo(
            relativePath = relativeTo(root).invariantSeparatorsPath,
            sizeBytes = length(),
            looksEncrypted = isImage || head.contains("aes-gcm-v1"),
            head = head,
        )
    }

    private companion object {
        const val TAG = "Index"
        const val MAX_FILES = 30
        const val HEAD_BYTES = 220
    }
}
