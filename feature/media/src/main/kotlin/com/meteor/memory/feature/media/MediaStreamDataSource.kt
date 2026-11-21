package com.meteor.memory.feature.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.meteor.memory.core.storage.MediaStore
import java.io.IOException
import java.io.InputStream

/**
 * ExoPlayer data source that decrypts stored media on the fly (ADR-058).
 *
 * Before this, playing a video first decrypted the whole file into `cacheDir`
 * and only then handed the copy to ExoPlayer: a 600 MB video paid a full
 * decrypt plus a 600 MB write before the first frame. Reading through
 * [MediaStore.openDecrypted] means the player only needs the opening chunks,
 * and seeking reopens the stream at the requested plaintext offset.
 *
 * The clear-text is never written to disk, so no temporary plaintext copy is
 * left behind if the process dies mid-playback.
 */
@UnstableApi
class MediaStreamDataSource(
    private val mediaStore: MediaStore,
) : BaseDataSource(/* isNetwork = */ false) {

    private var input: InputStream? = null
    private var remaining: Long = 0L
    private var currentUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val id = idOf(dataSpec.uri) ?: throw IOException("unsupported media uri: ${dataSpec.uri}")
        val media = mediaStore.find(id) ?: throw IOException("media not found: $id")
        val total = media.sizeBytes
        if (dataSpec.position > total) {
            throw IOException("position ${dataSpec.position} beyond media end $total")
        }
        val stream = try {
            mediaStore.openDecrypted(id, dataSpec.position)
        } catch (failure: Exception) {
            throw IOException("cannot open encrypted media $id", failure)
        }
        val available = total - dataSpec.position
        input = stream
        currentUri = dataSpec.uri
        opened = true
        remaining = if (dataSpec.length == C.LENGTH_UNSET) {
            available
        } else {
            minOf(dataSpec.length, available)
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), remaining).toInt()
        val read = stream.read(buffer, offset, requested)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        input?.close()
        input = null
        currentUri = null
        remaining = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val mediaStore: MediaStore) : DataSource.Factory {
        override fun createDataSource(): DataSource = MediaStreamDataSource(mediaStore)
    }

    companion object {
        /** Private scheme: nothing else in the app can mistake this for a URL. */
        private const val SCHEME = "meteormediastream"
        private const val HOST = "media"

        /** URI handed to Media3; [idOf] turns it back into a store id. */
        fun uriFor(id: String): Uri = Uri.parse("$SCHEME://$HOST/$id")

        fun idOf(uri: Uri): String? {
            if (uri.scheme != SCHEME) return null
            val id = uri.pathSegments.lastOrNull() ?: return null
            return id.takeIf(MediaStore.MEDIA_ID_PATTERN::matches)
        }
    }
}
