package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DiaryFilesTest {

    @Test
    fun `parses a canonical diary filename`() {
        assertEquals(LocalDate.of(2025, 3, 14), DiaryFiles.parseDate("2025-03-14.md"))
    }

    @Test
    fun `rejects non-diary files`() {
        for (name in listOf("profile.md", "persona.md", "2025-03-14.txt", "2025-3-14.md", "README.md", "2025-03-14.md.bak")) {
            assertNull("should reject $name", DiaryFiles.parseDate(name))
        }
    }

    @Test
    fun `rejects impossible dates`() {
        assertNull(DiaryFiles.parseDate("2025-02-30.md"))
        assertNull(DiaryFiles.parseDate("2025-13-01.md"))
    }

    @Test
    fun `formats the filename for a date`() {
        assertEquals("2025-03-14.md", DiaryFiles.fileNameFor(LocalDate.of(2025, 3, 14)))
        assertEquals("2025-01-05.md", DiaryFiles.fileNameFor(LocalDate.of(2025, 1, 5)))
    }
}
