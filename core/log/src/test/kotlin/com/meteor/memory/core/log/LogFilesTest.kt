package com.meteor.memory.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class LogFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun tempFile(name: String): File =
        temporaryFolder.newFile(name).apply { setLastModified(1_000L) }

    @Test
    fun `formats and parses a date`() {
        val date = LocalDate.of(2025, 3, 14)
        assertEquals("meteor-2025-03-14.log", LogFiles.fileNameFor(date))
        assertEquals(date, LogFiles.dateOf("meteor-2025-03-14.log"))
    }

    @Test
    fun `ignores foreign file names`() {
        assertNull(LogFiles.dateOf("meteor.log"))
        assertNull(LogFiles.dateOf("other-2025-03-14.log"))
        assertNull(LogFiles.dateOf("meteor-2025-13-40.log"))
    }

    @Test
    fun `expires files older than the retention window`() {
        val today = LocalDate.of(2025, 3, 14)
        val old = tempFile("meteor-2025-03-01.log")
        val edge = tempFile("meteor-2025-03-08.log")
        val fresh = tempFile("meteor-2025-03-14.log")

        val expired = LogFiles.expired(listOf(old, edge, fresh), today, retentionDays = 7)

        assertEquals(listOf(old), expired)
    }

    @Test
    fun `never expires files it cannot parse`() {
        val today = LocalDate.of(2025, 3, 14)
        val unknown = tempFile("notes.txt")
        assertTrue(LogFiles.expired(listOf(unknown), today, retentionDays = 1).isEmpty())
    }
}
