package com.meteor.memory.feature.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
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
    }
}
