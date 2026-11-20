package com.meteor.memory.ui.search

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.Mood
import com.meteor.memory.core.storage.RecordSource
import com.meteor.memory.core.storage.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant

class SearchableDocumentTest {

    private val labels = mapOf(Mood.HAPPY to "开心", Mood.SAD to "难过")

    private fun labelOf(mood: Mood): String = labels[mood] ?: mood.code

    @Test
    fun `mood record is indexed by its label`() {
        val happy = document(RecordType.MOOD, "happy")
        val sad = document(RecordType.MOOD, "sad")

        assertEquals("开心", searchableDocument(happy, ::labelOf).body)
        assertEquals("难过", searchableDocument(sad, ::labelOf).body)
    }

    @Test
    fun `every mood of a day is indexed`() {
        val moods = document(RecordType.MOOD, "happy\nsad")

        assertEquals("开心 难过", searchableDocument(moods, ::labelOf).body)
    }

    @Test
    fun `unknown mood codes are dropped instead of indexed raw`() {
        val mood = document(RecordType.MOOD, "future-mood\nhappy")

        assertEquals("开心", searchableDocument(mood, ::labelOf).body)
    }

    @Test
    fun `unknown mood code is left as stored`() {
        val mood = document(RecordType.MOOD, "future-mood")

        assertEquals("future-mood", searchableDocument(mood, ::labelOf).body)
    }

    @Test
    fun `other records keep their body`() {
        val diary = document(RecordType.DIARY, "今天很开心")

        assertEquals("今天很开心", searchableDocument(diary, ::labelOf).body)
    }

    private fun document(type: RecordType, body: String): Document = Document(
        file = File("${type.wire}.md"),
        personId = null,
        type = type,
        timestamp = Instant.parse("2026-11-15T02:00:00Z"),
        source = RecordSource.USER,
        body = body,
    )
}
