package com.meteor.memory.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class PersonQueriesTest {

    private val root = Files.createTempDirectory("meteor-people").toFile()

    private val router = object : StorageRouter {
        override fun route(personId: String?, type: RecordType, date: LocalDate): File {
            require(personId != null)
            return File(
                root,
                "${StorageLayout.ARCHIVE}/${StorageLayout.RELATIONS}/$personId/profile.md",
            )
        }

        override fun read(file: File): Document = Document(
            file = file,
            personId = file.parentFile?.name,
            type = RecordType.PROFILE,
            timestamp = Instant.EPOCH,
            source = RecordSource.USER,
            body = file.readText(),
        )

        override fun write(file: File, doc: Document) {
            file.parentFile?.mkdirs()
            file.writeText(doc.body)
        }

        override suspend fun exportTo(zipUri: android.net.Uri) = Unit

        override suspend fun importFrom(zipUri: android.net.Uri) = Unit
    }

    private val queries = PrivatePersonQueries(root, router)

    @Test
    fun `create persists custom tags and list exposes them`() {
        val id = queries.create(
            displayName = "小明",
            relation = Relation.FRIEND,
            note = "",
            notes = "",
            tags = listOf("同事", "大学同学"),
        )

        val entries = queries.list()
        val entry = entries.single { it.personId == id }
        assertEquals("小明", entry.displayName)
        assertEquals(listOf("同事", "大学同学"), entry.tags)
        assertEquals(listOf("同事", "大学同学"), queries.read(id)!!.tags)
    }

    @Test
    fun `delete removes the whole person directory`() {
        val id = queries.create(
            displayName = "小红",
            relation = null,
            note = "",
            notes = "",
            tags = emptyList(),
        )
        val dir = File(root, "${StorageLayout.ARCHIVE}/${StorageLayout.RELATIONS}/$id")
        assertTrue(dir.isDirectory)

        assertEquals(PersonDeleteResult.DELETED, queries.delete(id))
        assertFalse(dir.exists())
        assertEquals(PersonDeleteResult.NOT_FOUND, queries.delete(id))
    }

    @Test
    fun `delete rejects ids that could escape the people directory`() {
        assertEquals(PersonDeleteResult.FAILED, queries.delete("../profile"))
        assertEquals(PersonDeleteResult.FAILED, queries.delete(""))
    }
}
