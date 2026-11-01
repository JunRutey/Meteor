package com.meteor.memory.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.StorageRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Markdown implementation of the frozen [EditorModule].
 *
 * Bodies stay plain Markdown inside the encrypted file, which is what keeps the
 * data readable without this module (archive R3) and search-friendly for
 * `:core-index`.
 */
class MarkdownEditorModule(
    private val router: StorageRouter,
    private val imageStore: ImageStore,
) : EditorModule {

    override fun open(file: File): Document = router.read(file)

    override suspend fun save(doc: Document) {
        withContext(Dispatchers.IO) { router.write(doc.file, doc) }
    }

    override fun render(doc: Document): @Composable () -> Unit = {
        MarkdownPreview(body = doc.body)
    }

    override fun insertImage(doc: Document, imageBytes: ByteArray, personId: String?): Document {
        val compressed = ImageCompressor.compress(imageBytes)
        val token = imageStore.save(compressed, personId)
        return doc.copy(body = MarkdownEditing.appendImage(doc.body, token))
    }

    override fun insertLink(doc: Document, target: String, label: String?): Document =
        doc.copy(body = MarkdownEditing.appendLink(doc.body, target, label))
}

/** Alias kept so the frozen signature reads naturally in Kotlin. */
private typealias ComposableUnit = () -> Unit

/**
 * Downscales to a sane maximum edge and re-encodes as JPEG.
 *
 * Same limits as the AI image path (archive §10) so a photo costs the same
 * wherever it is referenced.
 */
object ImageCompressor {

    private const val MAX_EDGE = 1568
    private const val QUALITY = 80

    fun compress(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longestEdge = max(bounds.outWidth, bounds.outHeight)
        val sampleSize = Integer.highestOneBit((longestEdge / MAX_EDGE).coerceAtLeast(1))
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes
        val longest = max(original.width, original.height)
        val scaled = if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).roundToInt().coerceAtLeast(1),
                (original.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            original
        }

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        }
    }
}
