package com.meteor.memory.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeyMaskTest {

    @Test
    fun `a long key keeps only a short head and tail`() {
        assertEquals("sk-********cdef", maskApiKey("sk-1234567890abcdef"))
    }

    @Test
    fun `a short key is hidden completely`() {
        assertEquals("********", maskApiKey("sk-1"))
    }

    @Test
    fun `surrounding whitespace never leaks into the mask`() {
        assertEquals("sk-********cdef", maskApiKey("  sk-1234567890abcdef  "))
    }
}
