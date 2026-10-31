package com.meteor.memory.ui.attachments

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.meteor.memory.core.log.AppLog
import com.meteor.memory.core.storage.AttachmentStore
import com.meteor.memory.core.storage.EncryptedAttachmentStore
import com.meteor.memory.core.storage.StoredAttachment
import com.meteor.memory.ui.search.SearchService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentStore: AttachmentStore,
) {

    suspend fun import(uri: Uri, personId: String?): StoredAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = resolveFileName(resolver, uri)
        val mimeType = resolver.getType(uri) ?: mimeTypeFor(fileName)
        val size = resolveSize(resolver, uri)
        if (size != null && size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()

        val temp = File(context.cacheDir, "attachment-import-${UUID.randomUUID()}")
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> copyBounded(input, output) }
            } ?: error("selected file cannot be opened")

            val indexedText = if (AttachmentTextExtractor.supports(fileName, mimeType)) {
                temp.inputStream().use { AttachmentTextExtractor.extract(fileName, mimeType, it) }
                    ?.takeIf(String::isNotBlank)
                    ?.let { text -> "File: ${fileName.replace('\n', ' ').replace('\r', ' ')}\n$text" }
            } else {
                null
            }

            temp.inputStream().use { input ->
                attachmentStore.save(
                    input = input,
                    fileName = fileName,
                    mimeType = mimeType,
                    personId = personId,
                    indexedText = indexedText,
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun resolveFileName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: error("selected file name is unavailable")

    private fun resolveSize(resolver: ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
            }
        }.getOrNull()

    private fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                if (total == MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
                output.write(single)
                total++
            } else {
                if (read > MAX_ATTACHMENT_BYTES - total) throw AttachmentTooLargeException()
                output.write(buffer, 0, read)
                total += read
            }
        }
    }

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> EncryptedAttachmentStore.MIME_OCTET_STREAM
        }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 256L * 1024 * 1024
    }
}

internal class AttachmentTooLargeException : Exception("attachment is too large")

@Singleton
class AttachmentImportCoordinator @Inject constructor(
    private val importer: AttachmentImporter,
    private val searchService: SearchService,
    private val appLog: AppLog,
) {

    suspend fun importAll(
        uris: List<Uri>,
        personId: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): AttachmentImportSummary {
        var imported = 0
        var failed = 0
        var skipped = 0
        for ((index, uri) in uris.withIndex()) {
            try {
                importer.import(uri, personId)
                imported++
            } catch (tooLarge: AttachmentTooLargeException) {
                skipped++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed++
                appLog.warn(TAG, "attachment import failed", failure)
            }
            onProgress(index + 1, uris.size)
        }

        val indexFailed = if (imported == 0) {
            false
        } else {
            try {
                searchService.refreshAttachmentIndex()
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appLog.warn(TAG, "attachment index refresh failed", failure)
                true
            }
        }
        return AttachmentImportSummary(
            imported = imported,
            failed = failed,
            skipped = skipped,
            indexFailed = indexFailed,
        )
    }

    private companion object {
        const val TAG = "Attachments"
    }
}

data class AttachmentImportSummary(
    val imported: Int,
    val failed: Int,
    val skipped: Int,
    val indexFailed: Boolean,
)

internal object AttachmentTextExtractor {

    fun supports(fileName: String, mimeType: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase()
        return normalizedMimeType.startsWith("text/") ||
            normalizedMimeType == "application/json" ||
            extension in TEXT_EXTENSIONS ||
            extension == "zip" ||
            normalizedMimeType in ZIP_MIME_TYPES
    }

    fun extract(fileName: String, mimeType: String, input: InputStream): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase()
        return if (extension == "zip" || normalizedMimeType in ZIP_MIME_TYPES) {
            extractZip(input)
        } else {
            val bytes = input.readBounded(MAX_TEXT_BYTES)
            val text = decode(bytes)
            if (bytes.size == MAX_TEXT_BYTES) "$text\n$TRUNCATED_MARKER" else text
        }
    }

    private fun extractZip(input: InputStream): String? {
        val result = StringBuilder()
        var totalBytes = 0
        var visited = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (visited < MAX_ZIP_ENTRIES && totalBytes < MAX_TEXT_BYTES) {
                val entry = zip.nextEntry ?: break
                visited++
                val remaining = MAX_TEXT_BYTES - totalBytes
                if (!entry.isDirectory && isTextEntry(entry.name)) {
                    val bytes = zip.readBounded(remaining)
                    if (bytes.isNotEmpty()) {
                        if (result.isNotEmpty()) result.append('\n')
                        result.append("Entry: ").append(entry.name.take(MAX_ENTRY_NAME_CHARS)).append('\n')
                            .append(decode(bytes))
                        totalBytes += bytes.size
                    }
                    if (bytes.size == remaining) {
                        result.append('\n').append(TRUNCATED_MARKER)
                        break
                    }
                } else if (!entry.isDirectory) {
                    val skippedBytes = zip.discardBounded(remaining)
                    totalBytes += skippedBytes
                    if (skippedBytes == remaining) {
                        if (result.isNotEmpty()) result.append('\n').append(TRUNCATED_MARKER)
                        break
                    }
                }
                zip.closeEntry()
            }
        }
        return result.toString().takeIf(String::isNotBlank)
    }

    private fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .recoverCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrDefault(String(bytes, Charsets.UTF_8))
        return text.removePrefix("\uFEFF")
    }

    private fun isTextEntry(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun InputStream.readBounded(limit: Int): ByteArray {
        val result = ByteArrayOutputStream(minOf(limit, READ_BUFFER_SIZE))
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var remaining = limit
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            if (read == 0) {
                val single = read()
                if (single < 0) break
                result.write(single)
                remaining--
            } else {
                result.write(buffer, 0, read)
                remaining -= read
            }
        }
        return result.toByteArray()
    }

    private fun InputStream.discardBounded(limit: Int): Int {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            if (read == 0) {
                val single = read()
                if (single < 0) break
                total++
            } else {
                total += read
            }
        }
        return total
    }

    private const val MAX_TEXT_BYTES = 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 256
    private const val MAX_ENTRY_NAME_CHARS = 256
    private const val READ_BUFFER_SIZE = 8 * 1024
    private const val TRUNCATED_MARKER = "[truncated during local indexing]"
    private val TEXT_EXTENSIONS = setOf("txt", "json", "csv", "xml", "md", "log")
    private val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
}
