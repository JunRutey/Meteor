package com.meteor.memory.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMarkdownTest {

    private val sample = """
        ---
        name: demo-skill
        description: "a demo"
        stage: chat
        requires-person: true
        version: "9.9.9"
        ---
        Body line one.
        Body line two.
    """.trimIndent()

    @Test
    fun `parses frontmatter and body`() {
        val skill = SkillMarkdown.parse(sample, "skills/demo.md")!!

        assertEquals("demo-skill", skill.name)
        assertEquals("a demo", skill.description)
        assertEquals(SkillStage.CHAT, skill.stage)
        assertTrue(skill.requiresPerson)
        assertEquals("9.9.9", skill.version)
        assertEquals("Body line one.\nBody line two.", skill.body)
        assertEquals("skills/demo.md", skill.source)
    }

    @Test
    fun `a missing stage is rejected`() {
        val text = sample.replace("stage: chat\n", "")
        assertNull(SkillMarkdown.parse(text, "x"))
    }

    @Test
    fun `a blank body is rejected`() {
        val text = sample.substringBefore("Body line one.").trimEnd()
        assertNull(SkillMarkdown.parse(text, "x"))
    }

    @Test
    fun `text without frontmatter is rejected`() {
        assertNull(SkillMarkdown.parse("just a note", "x"))
    }

    @Test
    fun `requires-person defaults to false`() {
        val skill = SkillMarkdown.parse(sample.replace("requires-person: true\n", ""), "x")!!
        assertFalse(skill.requiresPerson)
    }

    @Test
    fun `library only exposes chat skills to the chat screen`() {
        val distilly = sample.replace("stage: chat", "stage: distill").replace("name: demo-skill", "name: d")
        val chatty = sample.replace("name: demo-skill", "name: c")
        val library = SkillLibrary { path ->
            when (path) {
                "skills/persona-distill.md" -> distilly
                "skills/persona-chat.md" -> chatty
                else -> null
            }
        }

        val chat = library.chatSkills()

        assertEquals(1, chat.size)
        assertEquals("c", chat.first().name)
    }
}
