package com.meteor.memory.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Raw HTTP result. Bodies are provider JSON, never logged. */
data class HttpResponse(val code: Int, val body: String)

/**
 * Minimal HTTP seam: the only place that touches the network, and the reason
 * the provider logic is unit-testable without any socket.
 */
interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse

    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): HttpResponse = throw AiProviderException(0, "multipart-not-supported")

    /** Streams a file from disk instead of loading it into a byte array. */
    suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        fileName: String,
        mimeType: String,
        file: File,
    ): HttpResponse = postMultipart(url, headers, fields, fileName, mimeType, file.readBytes())

    suspend fun get(url: String, headers: Map<String, String>): HttpResponse

    suspend fun delete(url: String, headers: Map<String, String>): HttpResponse =
        throw AiProviderException(0, "delete-not-supported")

    /**
     * Streams a POST response line by line (SSE `data:` lines). Default keeps
     * non-streaming fakes working by replaying the whole body once.
     */
    suspend fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit,
    ): HttpResponse {
        val response = post(url, headers, body)
        if (response.code in 200..299) {
            response.body.lineSequence().forEach { line -> if (line.isNotBlank()) onLine(line) }
        }
        return response
    }
}

/**
 * `HttpURLConnection` only — no OkHttp, no Ktor. The app ships one dependency
 * fewer, and the request shape stays auditable (ADR-027).
 */
class UrlConnectionTransport : HttpTransport {

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        execute("POST", url, headers + mapOf("Content-Type" to "application/json; charset=utf-8"), body)

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute("GET", url, headers, null)

    override suspend fun delete(url: String, headers: Map<String, String>): HttpResponse =
        execute("DELETE", url, headers, null)

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): HttpResponse = postMultipart(url, headers, fields, fileName, mimeType) { output ->
        output.write(bytes)
    }

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        fileName: String,
        mimeType: String,
        file: File,
    ): HttpResponse = postMultipart(url, headers, fields, fileName, mimeType) { output ->
        file.inputStream().use { it.copyTo(output) }
    }

    private suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
        fileName: String,
        mimeType: String,
        writeBody: (OutputStream) -> Unit,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val boundary = "meteor-${java.util.UUID.randomUUID()}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.use { output ->
                fields.forEach { (name, value) ->
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                val asciiName = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }
                    .joinToString("")
                    .take(MAX_FILE_NAME_CHARS)
                    .ifBlank { "upload" }
                val encodedName = java.net.URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write(
                    (
                        "Content-Disposition: form-data; name=\"file\"; filename=\"$asciiName\"; " +
                            "filename*=UTF-8''$encodedName\r\n"
                        ).toByteArray(Charsets.UTF_8),
                )
                output.write("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
                writeBody(output)
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(code, body.take(MAX_ERROR_BODY_CHARS))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun postStream(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                return@withContext HttpResponse(code, error.take(MAX_ERROR_BODY_CHARS))
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            }
            HttpResponse(code, "")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val MAX_ERROR_BODY_CHARS = 8_000
        const val MAX_FILE_NAME_CHARS = 128
    }
}
