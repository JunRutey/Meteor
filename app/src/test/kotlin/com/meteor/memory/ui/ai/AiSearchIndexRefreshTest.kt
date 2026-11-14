package com.meteor.memory.ui.ai

import com.meteor.memory.feature.ai.ArchiveTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSearchIndexRefreshTest {

    @Test
    fun `successful diary and mood writes request an index refresh`() {
        val tools = listOf(
            ArchiveTools.TOOL_SAVE_DIARY,
            ArchiveTools.TOOL_APPEND_DIARY,
            ArchiveTools.TOOL_SET_MOOD,
        )

        tools.forEach { tool ->
            assertTrue(isSuccessfulSearchableWrite(tool, """{"saved":true}"""))
        }
    }

    @Test
    fun `successful person writes request an index refresh`() {
        assertTrue(isSuccessfulSearchableWrite(ArchiveTools.TOOL_CREATE_PERSON, """{"created":true}"""))
        assertTrue(isSuccessfulSearchableWrite(ArchiveTools.TOOL_UPDATE_PERSON, """{"updated":true}"""))
        assertTrue(isSuccessfulSearchableWrite(ArchiveTools.TOOL_DELETE_PERSON, """{"deleted":true}"""))
    }

    @Test
    fun `failed or unrelated tool results do not request an index refresh`() {
        assertFalse(isSuccessfulSearchableWrite(ArchiveTools.TOOL_SAVE_DIARY, """{"error":"tool-failed"}"""))
        assertFalse(isSuccessfulSearchableWrite(ArchiveTools.TOOL_SET_MOOD, "not-json"))
        assertFalse(isSuccessfulSearchableWrite(ArchiveTools.TOOL_CREATE_PERSON, """{"saved":true}"""))
    }
}
