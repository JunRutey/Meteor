package com.meteor.memory.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPolicyTest {

    @Test
    fun `info survives non-verbose sessions`() {
        assertTrue(LogPolicy.persists(LogLevel.INFO, verbose = false))
    }

    @Test
    fun `debug needs verbose`() {
        assertFalse(LogPolicy.persists(LogLevel.DEBUG, verbose = false))
        assertTrue(LogPolicy.persists(LogLevel.DEBUG, verbose = true))
    }

    @Test
    fun `warnings and errors are always kept`() {
        listOf(LogLevel.WARN, LogLevel.ERROR).forEach { level ->
            assertTrue(LogPolicy.persists(level, verbose = false))
            assertTrue(LogPolicy.persists(level, verbose = true))
        }
    }
}
