package com.meteor.memory.core.storage

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class MoodQueriesTest {

    @Test
    fun `save keeps at most three moods in selection order`() {
        withQueries { queries, root ->
            val day = LocalDate.parse("2026-05-01")

            queries.save(day, listOf(Mood.HAPPY, Mood.CALM, Mood.EXCITED, Mood.SAD))

            assertEquals(listOf(Mood.HAPPY, Mood.CALM, Mood.EXCITED), queries.read(day))
            assertEquals(
                mapOf(day to listOf(Mood.HAPPY, Mood.CALM, Mood.EXCITED)),
                queries.range(day.minusDays(1), day.plusDays(1)),
            )
            assertTrue(moodFile(root, day).isFile)
        }
    }

    @Test
    fun `legacy single-code record is still one mood`() {
        withQueries { queries, root ->
            val day = LocalDate.parse("2026-05-02")
            moodFile(root, day).apply {
                parentFile?.mkdirs()
                writeText("happy")
            }

            assertEquals(listOf(Mood.HAPPY), queries.read(day))
            assertEquals(listOf(Mood.HAPPY), queries.range(day, day)[day])
        }
    }

    @Test
    fun `saving an empty selection deletes the record`() {
        withQueries { queries, root ->
            val day = LocalDate.parse("2026-05-03")
            queries.save(day, listOf(Mood.HAPPY))

            queries.save(day, emptyList())

            assertFalse(moodFile(root, day).exists())
            assertTrue(queries.read(day).isEmpty())
            assertTrue(queries.range(day, day).isEmpty())
        }
    }

    @Test
    fun `toggling adds, removes and refuses a fourth mood`() {
        var selected = emptyList<Mood>()

        selected = Mood.toggled(selected, Mood.HAPPY)
        selected = Mood.toggled(selected, Mood.CALM)
        selected = Mood.toggled(selected, Mood.EXCITED)
        assertEquals(listOf(Mood.HAPPY, Mood.CALM, Mood.EXCITED), selected)

        assertEquals(selected, Mood.toggled(selected, Mood.SAD))
        assertEquals(listOf(Mood.CALM, Mood.EXCITED), Mood.toggled(selected, Mood.HAPPY))
    }

    @Test
    fun `decode accepts newlines, commas, duplicates and unknown codes`() {
        assertEquals(
            listOf(Mood.HAPPY, Mood.CALM),
            Mood.decode(" happy \n calm \n happy \n future-mood "),
        )
        assertEquals(listOf(Mood.HAPPY, Mood.CALM), Mood.decode("happy,calm"))
        assertEquals(emptyList<Mood>(), Mood.decode("future-mood"))
        assertEquals("happy\ncalm", Mood.encode(listOf(Mood.HAPPY, Mood.CALM)))
    }

    private fun withQueries(block: (MoodQueries, File) -> Unit) {
        val root = createTempDir(prefix = "mood-queries-test")
        try {
            block(PrivateMoodQueries(root, PlainRouter(root)), root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun moodFile(root: File, day: LocalDate): File = File(
        root,
        "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}/${DiaryFiles.fileNameFor(day)}",
    )

    /** Plain-text stand-in for the encrypting router; body in, body out. */
    private class PlainRouter(root: File) : StorageRouter {
        private val layout = StorageLayout(root)

        override fun route(personId: String?, type: RecordType, date: LocalDate): File =
            layout.fileFor(personId, type, date)

        override fun read(file: File): Document = Document(
            file = file,
            personId = null,
            type = RecordType.MOOD,
            timestamp = Instant.EPOCH,
            source = RecordSource.USER,
            body = file.readText(),
        )

        override fun write(file: File, doc: Document) {
            file.parentFile?.mkdirs()
            file.writeText(doc.body)
        }

        override suspend fun exportTo(zipUri: Uri) = Unit

        override suspend fun importFrom(zipUri: Uri) = Unit
    }
}
