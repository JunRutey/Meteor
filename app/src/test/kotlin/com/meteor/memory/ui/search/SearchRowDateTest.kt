package com.meteor.memory.ui.search

import com.meteor.memory.core.storage.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SearchRowDateTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `back filled diary uses the date in its archive path`() {
        val hit = SearchMatch(
            recordId = "diary",
            type = RecordType.DIARY,
            timestamp = Instant.parse("2026-11-15T02:00:00Z"),
            snippet = "…",
            sourcePath = "时光轴/心情/日记/2026/2026-03-14.md",
        )

        assertEquals(LocalDate.of(2026, 3, 14), searchRowDate(hit, zone))
    }

    @Test
    fun `mood and chat records use their path date too`() {
        val mood = SearchMatch(
            recordId = "mood",
            type = RecordType.MOOD,
            timestamp = Instant.parse("2026-11-15T02:00:00Z"),
            snippet = "happy",
            sourcePath = "时光轴/心情/2026-03-15.md",
        )
        val chat = SearchMatch(
            recordId = "chat",
            type = RecordType.CHAT_LOG,
            timestamp = Instant.parse("2026-11-15T02:00:00Z"),
            snippet = "…",
            sourcePath = "数字遗产/聊天记录/person-1/2026-03-16.md",
        )

        assertEquals(LocalDate.of(2026, 3, 15), searchRowDate(mood, zone))
        assertEquals(LocalDate.of(2026, 3, 16), searchRowDate(chat, zone))
    }

    @Test
    fun `profile without a dated path falls back to the timestamp`() {
        val hit = SearchMatch(
            recordId = "profile",
            type = RecordType.PROFILE,
            timestamp = Instant.parse("2026-11-15T02:00:00Z"),
            snippet = "…",
            sourcePath = "记忆档案/人际关系/person-1/profile.md",
        )

        assertEquals(LocalDate.of(2026, 11, 15), searchRowDate(hit, zone))
    }
}
