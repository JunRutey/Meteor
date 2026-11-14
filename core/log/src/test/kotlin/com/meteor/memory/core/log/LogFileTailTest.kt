package com.meteor.memory.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogFileTailTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `returns a short file unchanged`() {
        val file = temporaryFolder.newFile("short.log").apply { writeText("one\ntwo\n") }
        assertEquals("one\ntwo\n", LogFileTail.read(file, maxBytes = 1024))
    }

    @Test
    fun `keeps the newest lines and drops the partial first line`() {
        val file = temporaryFolder.newFile("long.log").apply {
            writeText("old line\n" + "x".repeat(200) + "\nnew line\n")
        }

        val tail = LogFileTail.read(file, maxBytes = 80)

        assertTrue(tail.startsWith("earlier lines omitted\n"))
        assertTrue(tail.contains("new line"))
        assertTrue("older content must be gone", !tail.contains("old line"))
    }

    @Test
    fun `returns an empty string for an empty file`() {
        val file = temporaryFolder.newFile("empty.log")
        assertEquals("", LogFileTail.read(file))
    }
}
