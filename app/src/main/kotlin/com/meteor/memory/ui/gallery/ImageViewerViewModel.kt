package com.meteor.memory.ui.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.feature.gallery.ImageViewerError
import com.meteor.memory.ui.common.sampledImageOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject

/** Everything the viewer needs to render one image and its actions. */
data class ImageViewerState(
    val bitmap: ImageBitmap? = null,
    val loading: Boolean = true,
    val dateLabel: String? = null,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val sharing: Boolean = false,
    val shareRequest: ShareRequest? = null,
    val error: ImageViewerError = ImageViewerError.None,
)

/** A decrypted cache file ready to be handed to the system share sheet. */
data class ShareRequest(
    val uri: Uri,
    val mimeType: String,
)

/** High-resolution viewer for one image token. */
@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val imageStore: ImageStore,
    private val appLog: AppLog,
) : ViewModel() {

    private val token: String = Uri.decode(checkNotNull(savedStateHandle.get<String>(ARG_TOKEN)))

    private val _state = MutableStateFlow(ImageViewerState())
    val state: StateFlow<ImageViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { load() }
                _state.update {
                    it.copy(bitmap = loaded?.bitmap, dateLabel = loaded?.dateLabel)
                }
                if (loaded?.bitmap == null) appLog.warn(TAG, "image missing or undecodable")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "image load failed", error)
            } finally {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    /** Deletes the encrypted file, then tells the screen to leave the viewer. */
    fun delete() {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, error = ImageViewerError.None) }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { imageStore.delete(token) }.getOrDefault(false)
            }
            if (removed) {
                appLog.info(TAG, "image deleted")
                _state.update { it.copy(deleting = false, deleted = true) }
            } else {
                appLog.warn(TAG, "image delete failed")
                _state.update {
                    it.copy(deleting = false, error = ImageViewerError.DeleteFailed)
                }
            }
        }
    }

    /**
     * Decrypts the image into a cache file and exposes its content URI.
     *
     * The copy stays on disk until a later share prunes it: a receiving app may
     * read the URI after our activity is gone, so deleting it here would race.
     */
    fun share() {
        if (_state.value.sharing) return
        _state.update { it.copy(sharing = true, error = ImageViewerError.None) }
        viewModelScope.launch {
            try {
                val request = withContext(Dispatchers.IO) { buildShareRequest() }
                if (request == null) {
                    appLog.warn(TAG, "share failed: image unavailable")
                    _state.update {
                        it.copy(sharing = false, error = ImageViewerError.ShareFailed)
                    }
                } else {
                    _state.update { it.copy(sharing = false, shareRequest = request) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appLog.warn(TAG, "share failed", error)
                _state.update {
                    it.copy(sharing = false, error = ImageViewerError.ShareFailed)
                }
            }
        }
    }

    /** Called once the chooser was shown; the request is one-shot. */
    fun onShareLaunched() {
        _state.update { it.copy(shareRequest = null) }
    }

    /** Called when an activity for the share intent could not be started. */
    fun onShareLaunchedFailed() {
        appLog.warn(TAG, "no activity for share intent")
        _state.update { it.copy(shareRequest = null, error = ImageViewerError.ShareFailed) }
    }

    private class Loaded(val bitmap: ImageBitmap?, val dateLabel: String?)

    private fun load(): Loaded {
        val bytes = imageStore.load(token) ?: return Loaded(null, null)
        val options = sampledImageOptions(bytes, MAX_IMAGE_EDGE)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?.asImageBitmap()
        val modifiedAt = imageStore.list().firstOrNull { it.token == token }?.modifiedAt
        val dateLabel = modifiedAt?.let { instant ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
        return Loaded(bitmap, dateLabel)
    }

    private fun buildShareRequest(): ShareRequest? {
        val bytes = imageStore.load(token) ?: return null
        val mimeType = ImageShareFile.mimeType(bytes)
        val dir = File(context.cacheDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        pruneOldShares(dir)

        val file = File(
            dir,
            ImageShareFile.fileName(token, mimeType, System.currentTimeMillis()),
        )
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}$SHARE_AUTHORITY_SUFFIX",
            file,
        )
        return ShareRequest(uri = uri, mimeType = mimeType)
    }

    /** Best-effort cleanup of files from earlier shares. */
    private fun pruneOldShares(dir: File) {
        val cutoff = System.currentTimeMillis() - SHARE_MAX_AGE_MS
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach { old ->
                runCatching { old.delete() }
                    .onFailure { appLog.debug(TAG, "stale share file kept: ${old.name}") }
            }
    }

    companion object {
        const val ARG_TOKEN = "token"
        private const val TAG = "ImageViewer"
        private const val MAX_IMAGE_EDGE = 2048
        private const val SHARE_DIR = "share"
        private const val SHARE_AUTHORITY_SUFFIX = ".share"
        private const val SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
