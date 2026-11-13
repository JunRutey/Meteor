package com.meteor.memory.feature.ai

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant

object AiFiles {

    suspend fun upload(
        config: AiConfig,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        transport: HttpTransport = UrlConnectionTransport(),
    ): AiFileReference = uploadInternal(
        config = config,
        fileName = fileName,
        mimeType = mimeType,
        size = bytes.size.toLong(),
    ) { fields, resolvedMime ->
        transport.postMultipart(
            url = config.filesEndpoint,
            headers = authHeaders(config),
            fields = fields,
            fileName = fileName,
            mimeType = resolvedMime,
            bytes = bytes,
        )
    }

    /** Uploads from disk so a 100 MiB document is never fully buffered in RAM. */
    suspend fun upload(
        config: AiConfig,
        fileName: String,
        mimeType: String,
        file: File,
        transport: HttpTransport = UrlConnectionTransport(),
    ): AiFileReference {
        if (!file.isFile) throw AiProviderException(0, "file-read-failed")
        return uploadInternal(
            config = config,
            fileName = fileName,
            mimeType = mimeType,
            size = file.length(),
        ) { fields, resolvedMime ->
            transport.postMultipart(
                url = config.filesEndpoint,
                headers = authHeaders(config),
                fields = fields,
                fileName = fileName,
                mimeType = resolvedMime,
                file = file,
            )
        }
    }

    private suspend fun uploadInternal(
        config: AiConfig,
        fileName: String,
        mimeType: String,
        size: Long,
        post: suspend (fields: List<Pair<String, String>>, mimeType: String) -> HttpResponse,
    ): AiFileReference {
        requireOfficialDeepSeek(config, requireFlash = true)
        if (fileName.isBlank() || fileName.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) {
            throw AiProviderException(0, "invalid-file-name")
        }
        val purpose = purposeFor(fileName)
        if (size <= 0 || size > maxBytesFor(fileName).toLong()) {
            throw AiProviderException(0, "file-size-out-of-range")
        }
        val response = post(
            listOf(
                "purpose" to purpose,
                "expires_after[anchor]" to "created_at",
                "expires_after[seconds]" to FILE_EXPIRY_SECONDS.toString(),
            ),
            mimeType.ifBlank { mimeTypeFor(fileName) },
        )
        if (response.code !in 200..299) {
            throw AiProviderException(response.code, AiProtocol.errorMessage(response.body))
        }
        val root = runCatching { JSONObject(response.body) }
            .getOrElse { throw AiProviderException(0, "invalid-file-response") }
        val id = root.optString("id").takeIf(String::isNotBlank)
            ?: throw AiProviderException(0, "missing-file-id")
        return AiFileReference(id, fileName, purpose, Instant.now())
    }

    private fun authHeaders(config: AiConfig): Map<String, String> {
        val key = config.apiKey?.takeIf(String::isNotBlank)
            ?: throw AiProviderException(0, "missing-api-key")
        return mapOf("Authorization" to "Bearer $key")
    }

    suspend fun delete(
        config: AiConfig,
        fileId: String,
        transport: HttpTransport = UrlConnectionTransport(),
    ) {
        // Deleting a file is account-level and independent of the model. Only
        // the endpoint/key are needed, so switching models must not strand it.
        requireOfficialDeepSeek(config, requireFlash = false)
        if (!FILE_ID.matches(fileId)) throw AiProviderException(0, "invalid-file-id")
        val key = config.apiKey?.takeIf(String::isNotBlank)
            ?: throw AiProviderException(0, "missing-api-key")
        val encodedId = URLEncoder.encode(fileId, Charsets.UTF_8.name())
        val response = transport.delete(
            url = "${config.filesEndpoint}/$encodedId",
            headers = mapOf("Authorization" to "Bearer $key"),
        )
        if (response.code !in 200..299) {
            throw AiProviderException(response.code, AiProtocol.errorMessage(response.body))
        }
        val deleted = runCatching { JSONObject(response.body).optBoolean("deleted") }.getOrNull()
        if (deleted != true) throw AiProviderException(0, "file-delete-not-confirmed")
    }

    private fun requireOfficialDeepSeek(config: AiConfig, requireFlash: Boolean) {
        if (!config.isConfigured()) throw AiProviderException(0, "not-configured")
        if (config.provider != AiPresets.DEEPSEEK || !config.officialDeepSeekEndpoint) {
            throw AiProviderException(0, "files-require-deepseek-flash")
        }
        if (requireFlash && config.resolvedModel != "deepseek-flash") {
            throw AiProviderException(0, "files-require-deepseek-flash")
        }
    }

    private fun purposeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf", "doc", "docx", "ppt", "pptx", "bmp", "tif", "tiff" -> "rich_layout_recognition"
        in FILE_USER_DATA_EXTENSIONS -> "user_data"
        else -> throw AiProviderException(0, "unsupported-file-type")
    }

    fun maxBytesFor(fileName: String): Int = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp" -> MAX_IMAGE_BYTES
        "pdf", "doc", "docx", "ppt", "pptx", "bmp", "tif", "tiff" -> MAX_DOCUMENT_BYTES
        else -> MAX_OTHER_BYTES
    }

    fun isSupported(fileName: String): Boolean = runCatching { purposeFor(fileName) }.isSuccess

    private fun mimeTypeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "txt", "md" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        in FILE_TEXT_EXTENSIONS -> "text/plain"
        else -> "application/octet-stream"
    }

    private val FILE_TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "json", "xml", "yaml", "yml", "html", "css", "toml", "ini", "properties",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go",
        "rs", "rb", "php", "sh", "sql", "gradle",
    )

    private val FILE_USER_DATA_EXTENSIONS = FILE_TEXT_EXTENSIONS + setOf(
        "jpg", "jpeg", "png", "gif", "webp", "xls", "xlsx", "epub", "mobi",
    )

    private val FILE_ID = Regex("[A-Za-z0-9_-]{1,256}")

    const val FILE_EXPIRY_SECONDS = 30 * 24 * 60 * 60
    const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
    const val MAX_DOCUMENT_BYTES = 100 * 1024 * 1024
    const val MAX_OTHER_BYTES = 32 * 1024 * 1024
    const val MAX_FILE_NAME_BYTES = 512
}
