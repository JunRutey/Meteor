package com.meteor.memory.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogExportFormatTest {

    @Test
    fun `sorts files by name and keeps the header first`() {
        val document = LogExportFormat.document(
            header = "HEADER\n",
            files = listOf(
                LogFileContent("meteor-2025-03-14.log", "second"),
                LogFileContent("meteor-2025-03-13.log", "first"),
            ),
        )

        assertEquals(
            "HEADER\n" +
                "## meteor-2025-03-13.log\n" +
                "first\n" +
                "## meteor-2025-03-14.log\n" +
                "second\n",
            document,
        )
    }

    @Test
    fun `reports when there are no log files`() {
        val document = LogExportFormat.document("HEADER\n", emptyList())
        assertEquals("HEADER\nno log files yet\n", document)
    }

    @Test
    fun `keeps the tail of an oversized file`() {
        val text = "old\n" + "x".repeat(LogExportFormat.MAX_CHARS_PER_FILE) + "\nnew\n"

        val document = LogExportFormat.document(
            header = "",
            files = listOf(LogFileContent("meteor-2025-03-14.log", text)),
        )

        assertTrue(document.contains("## meteor-2025-03-14.log"))
        assertTrue(document.contains("\nnew\n"))
        assertTrue("the omitted prefix must be gone", !document.contains("old\n"))
    }

    @Test
    fun `trims older sections when the total is too large`() {
        val chunk = "y".repeat(LogExportFormat.MAX_CHARS_PER_FILE)
        val document = LogExportFormat.document(
            header = "HEADER\n",
            files = (1..6).map { index ->
                LogFileContent("meteor-2025-03-0$index.log", chunk)
            },
        )

        assertTrue(document.startsWith("HEADER\n"))
        assertTrue(document.contains("earlier lines omitted"))
        assertTrue(document.length <= LogExportFormat.MAX_TOTAL_CHARS + 64)
    }
}
