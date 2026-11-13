package com.meteor.memory.feature.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFilesTest {

    private class FakeTransport : HttpTransport {
        var fields: List<Pair<String, String>> = emptyList()
        var deletedUrl: String? = null

        override suspend fun post(url: String, headers: Map<String, String>, body: String) =
            HttpResponse(500, "")

        override suspend fun postMultipart(
            url: String,
            headers: Map<String, String>,
            fields: List<Pair<String, String>>,
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
        ): HttpResponse {
            this.fields = fields
            return HttpResponse(
                200,
                """{"id":"file-api-1","filename":"$fileName","purpose":"${fields.first().second}"}""",
            )
        }

        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "")

        override suspend fun delete(url: String, headers: Map<String, String>): HttpResponse {
            deletedUrl = url
            return HttpResponse(200, """{"deleted":true}""")
        }
    }

    private fun config(model: String = "deepseek-flash") = AiConfig(
        provider = AiPresets.DEEPSEEK,
        model = model,
        apiKey = "k",
    )

    @Test
    fun `upload sets purpose before file and expires after thirty days`() = runBlocking {
        val transport = FakeTransport()

        val file = AiFiles.upload(config(), "report.pdf", "application/pdf", byteArrayOf(1), transport)

        assertEquals("file-api-1", file.id)
        assertEquals("rich_layout_recognition", file.purpose)
        assertTrue(file.uploadedAt.toEpochMilli() > 0)
        assertEquals(
            listOf(
                "purpose" to "rich_layout_recognition",
                "expires_after[anchor]" to "created_at",
                "expires_after[seconds]" to AiFiles.FILE_EXPIRY_SECONDS.toString(),
            ),
            transport.fields,
        )
    }

    @Test
    fun `code and ebook files are accepted as user data`() = runBlocking {
        val transport = FakeTransport()

        val code = AiFiles.upload(config(), "Main.kt", "text/plain", byteArrayOf(1), transport)
        val book = AiFiles.upload(config(), "book.mobi", "application/x-mobipocket-ebook", byteArrayOf(1), transport)

        assertEquals("user_data", code.purpose)
        assertEquals("user_data", book.purpose)
    }

    @Test
    fun `unsupported files and other models are rejected before upload`() {
        val unsupported = assertThrows(AiProviderException::class.java) {
            runBlocking { AiFiles.upload(config(), "archive.zip", "application/zip", byteArrayOf(1), FakeTransport()) }
        }
        assertEquals("unsupported-file-type", unsupported.message)

        val model = assertThrows(AiProviderException::class.java) {
            runBlocking {
                AiFiles.upload(config("deepseek-chat"), "a.pdf", "application/pdf", byteArrayOf(1), FakeTransport())
            }
        }
        assertEquals("files-require-deepseek-flash", model.message)
    }

    @Test
    fun `delete uses official files endpoint`() = runBlocking {
        val transport = FakeTransport()

        AiFiles.delete(config(), "file-api-1", transport)

        assertEquals("https://api.deepseek.com/files/file-api-1", transport.deletedUrl)
    }

    @Test
    fun `delete still works after switching to another DeepSeek model`() = runBlocking {
        val transport = FakeTransport()

        AiFiles.delete(config("deepseek-chat"), "file-api-1", transport)

        assertEquals("https://api.deepseek.com/files/file-api-1", transport.deletedUrl)
    }
}
