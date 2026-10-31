package com.meteor.memory.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LogFormatterTest {

    private val at = Instant.parse("2025-03-14T13:30:07.123Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `formats level tag and message`() {
        val line = LogFormatter.line(LogLevel.WARN, "Storage", "file missing", null, at, utc)
        assertEquals("2025-03-14T13:30:07.123 [W] [Storage] file missing", line)
    }

    @Test
    fun `appends a bounded stack trace`() {
        val throwable = IllegalStateException("boom")
        val line = LogFormatter.line(LogLevel.ERROR, "App", "crashed", throwable, at, utc)

        assertTrue(line.startsWith("2025-03-14T13:30:07.123 [E] [App] crashed\n"))
        assertTrue(line.contains("IllegalStateException: boom"))
        assertTrue("stack trace must stay bounded", line.lines().size <= 13)
    }
}
