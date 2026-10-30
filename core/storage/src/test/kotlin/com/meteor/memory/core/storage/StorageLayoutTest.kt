package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class StorageLayoutTest {

    private val root = File("/data/user/0/com.meteor.memory/files/我的人生")
    private val layout = StorageLayout(root)
    private val date = LocalDate.of(2025, 3, 14)

    private fun relative(file: File) = file.relativeTo(root).invariantSeparatorsPath

    @Test
    fun `diary routes under timeline mood diary year`() {
        assertEquals(
            "时光轴/心情/日记/2025/2025-03-14.md",
            relative(layout.fileFor(null, RecordType.DIARY, date)),
        )
    }

    @Test
    fun `mood routes under timeline mood`() {
        assertEquals(
            "时光轴/心情/2025-03-14.md",
            relative(layout.fileFor(null, RecordType.MOOD, date)),
        )
    }

    @Test
    fun `profile without person goes to self`() {
        assertEquals(
            "记忆档案/自己/profile.md",
            relative(layout.fileFor(null, RecordType.PROFILE, date)),
        )
    }

    @Test
    fun `profile with person goes under relations`() {
        assertEquals(
            "记忆档案/人际关系/Abc_123-x/profile.md",
            relative(layout.fileFor("Abc_123-x", RecordType.PROFILE, date)),
        )
    }

    @Test
    fun `chat log goes under legacy chat person`() {
        assertEquals(
            "数字遗产/聊天记录/Abc_123/2025-03-14.md",
            relative(layout.fileFor("Abc_123", RecordType.CHAT_LOG, date)),
        )
    }

    @Test
    fun `persona goes under legacy persona`() {
        assertEquals(
            "数字遗产/数字人格/Abc_123/persona.md",
            relative(layout.fileFor("Abc_123", RecordType.PERSONA, date)),
        )
    }

    @Test
    fun `chat log requires a person id`() {
        assertThrows(IllegalArgumentException::class.java) {
            layout.fileFor(null, RecordType.CHAT_LOG, date)
        }
    }

    @Test
    fun `person id cannot escape the tree`() {
        for (evil in listOf("../../etc", "a/b", "..", "a b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) {
                layout.fileFor(evil, RecordType.CHAT_LOG, date)
            }
        }
    }

    @Test
    fun `images stay under legacy images person`() {
        assertTrue(relative(layout.imagesDir("Abc_123")).endsWith("数字遗产/图片/Abc_123"))
    }
}
