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
import com.meteor.memory.core.storage.AlbumEntry
import com.meteor.memory.core.storage.AlbumKind
import com.meteor.memory.core.storage.AlbumRepository
import com.meteor.memory.feature.gallery.GalleryKind
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

/** A decrypted cache file ready to be handed to the system share sheet. */
data class ShareRequest(
    val uri: Uri,
    val mimeType: String,
)

/** Everything the media viewer needs to render one item and its actions. */
data class MediaViewerState(
    val ref: String = "",
    val kind: GalleryKind = GalleryKind.IMAGE,
    val fileName: String? = null,
    val bitmap: ImageBitmap? = null,
    val hasContent: Boolean = false,
    val loading: Boolean = true,
    val dateLabel: String? = null,
    /** Plaintext extracted at import, for `GalleryKind.FILE` only (ADR-065). */
    val fileText: String? = null,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val sharing: Boolean = false,
    val shareRequest: ShareRequest? = null,
    val error: ImageViewerError = ImageViewerError.None,
)

/**
 * Viewer for one `media/{ref}` destination, where `ref` is a full
 * `meteor://media/<id>` or legacy `meteor://image/<token>` URL.
 *
 * Images decode in-memory like the old `ImageViewerViewModel`; videos and audio
 * are not decoded here — the screen plays them through the `feature-media`
 * player cards, which decrypt large files to a cache file themselves. Share and
 * delete always go through the repository's ref-level operations.
 */
@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val albumRepository: AlbumRepository,
    private val appLog: AppLog,
) : ViewModel() {

    private val ref: String =
        Uri.decode(checkNotNull(savedStateHandle.get<String>(ARG_REF)))

    private val _state = MutableStateFlow(MediaViewerState(ref = ref))
    val state: StateFlow<MediaViewerState> = _state.asStateFlow()

    @Volatile
    private var loadedBytes: ByteArray? = null

    /**
     * Entry resolved while loading. `entry(ref)` walks the whole legacy image
     * directory for `meteor://image/` refs, so sharing reuses this instead of
     * paying for the same scan a second time.
     */
    @Volatile
    private var loadedEntry: AlbumEntry? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { load() }
                loadedEntry = loaded.entry
                _state.update {
                    it.copy(
                        kind = loaded.kind,
                        fileName = loaded.fileName,
                        bitmap = loaded.bitmap,
                        hasContent = loaded.hasContent,
                        dateLabel = loaded.dateLabel,
                        fileText = loaded.fileText,
                    )
                }
                if (!loaded.hasContent) appLog.warn(TAG, "media missing or undecodable")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appLog.warn(TAG, "media load failed", error)
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
                runCatching { albumRepository.delete(ref) }.getOrDefault(false)
            }
            if (removed) {
                appLog.info(TAG, "media deleted")
                releaseLoadedBytes()
                _state.update { it.copy(deleting = false, deleted = true) }
            } else {
                appLog.warn(TAG, "media delete failed")
                _state.update {
                    it.copy(deleting = false, error = ImageViewerError.DeleteFailed)
                }
            }
        }
    }

    fun share() {
        if (_state.value.sharing) return
        _state.update { it.copy(sharing = true, error = ImageViewerError.None) }
        viewModelScope.launch {
            try {
                val request = withContext(Dispatchers.IO) { buildShareRequest() }
                if (request == null) {
                    appLog.warn(TAG, "share failed: media unavailable")
                    releaseLoadedBytes()
                    _state.update {
                        it.copy(sharing = false, error = ImageViewerError.ShareFailed)
                    }
                } else {
                    _state.update { it.copy(sharing = false, shareRequest = request) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                appLog.warn(TAG, "share failed", error)
                releaseLoadedBytes()
                _state.update {
                    it.copy(sharing = false, error = ImageViewerError.ShareFailed)
                }
            }
        }
    }

    /** Called once the chooser was shown; the request is one-shot. */
    fun onShareLaunched() {
        releaseLoadedBytes()
        _state.update { it.copy(shareRequest = null) }
    }

    /** Called when the share intent could not be built or started. */
    fun onShareLaunchedFailed(error: Throwable? = null) {
        releaseLoadedBytes()
        appLog.warn(TAG, "no activity for share intent", error)
        _state.update { it.copy(shareRequest = null, error = ImageViewerError.ShareFailed) }
    }

    private fun releaseLoadedBytes() {
        loadedBytes = null
    }

    override fun onCleared() {
        releaseLoadedBytes()
        super.onCleared()
    }

    private class Loaded(
        val kind: GalleryKind,
        val fileName: String?,
        val bitmap: ImageBitmap?,
        val hasContent: Boolean,
        val dateLabel: String?,
        val fileText: String? = null,
        val entry: AlbumEntry? = null,
    )

    private fun load(): Loaded {
        val entry = albumRepository.entry(ref)
        if (entry == null) {
            return Loaded(GalleryKind.IMAGE, null, null, hasContent = false, dateLabel = null)
        }
        val kind = AlbumMapping.toUiKind(entry.kind)
        val dateLabel = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(entry.importedAt)

        // An imported document has no pixels to decode: its body is the text the
        // importer extracted, and "no text" is a normal outcome (PDF, images).
        if (entry.kind == AlbumKind.FILE) {
            val text = albumRepository.readFileText(ref)
            return Loaded(kind, entry.fileName, null, hasContent = true, dateLabel, text, entry)
        }

        if (entry.kind != AlbumKind.IMAGE) {
            return Loaded(
                kind, entry.fileName, null, hasContent = true, dateLabel = dateLabel, entry = entry,
            )
        }

        val bytes = albumRepository.loadBytes(ref)
            ?: return Loaded(
                kind, entry.fileName, null, false, dateLabel, entry = entry,
            )
        val options = sampledImageOptions(bytes, MAX_IMAGE_EDGE)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        if (bitmap != null) loadedBytes = bytes
        return Loaded(kind, entry.fileName, bitmap, hasContent = bitmap != null, dateLabel, entry = entry)
    }

    private suspend fun buildShareRequest(): ShareRequest? {
        val entry = loadedEntry ?: albumRepository.entry(ref) ?: return null
        val mimeType = entry.mimeType?.takeIf { it.isNotBlank() }
            ?: MediaShareFile.mimeTypeForKind(entry.kind)
        val dir = File(context.cacheDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        pruneOldShares(dir)

        val file = File(
            dir,
            MediaShareFile.fileName(ref, entry.fileName, mimeType, System.currentTimeMillis()),
        )
        val written = withContext(Dispatchers.IO) {
            runCatching {
                file.outputStream().use { out -> albumRepository.copyDecrypted(ref, out) }
            }.getOrNull()
        }
        if (written == null) return null

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
        const val ARG_REF = "ref"
        private const val TAG = "MediaViewer"
        private const val MAX_IMAGE_EDGE = 2048
        private const val SHARE_DIR = "share"
        private const val SHARE_AUTHORITY_SUFFIX = ".share"
        private const val SHARE_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
