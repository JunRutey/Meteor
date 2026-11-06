package com.meteor.memory.core.storage

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PrivateArchiveRecordDeleterTest {

    @Test
    fun `deletes only routed diary and mood entries`() {
        val root = createTempDir(prefix = "archive-delete-test")
        try {
            val router = TestRouter(root)
            val day = LocalDate.parse("2026-01-03")
            val diary = router.route(null, RecordType.DIARY, day).apply {
                parentFile?.mkdirs()
                writeText("encrypted diary")
            }
            val mood = router.route(null, RecordType.MOOD, day).apply {
                parentFile?.mkdirs()
                writeText("encrypted mood")
            }
            val other = File(root, "profile.md").apply { writeText("untouched") }
            val deleter = PrivateArchiveRecordDeleter(router)

            assertEquals(ArchiveDeleteResult.DELETED, deleter.deleteDiary(day))
            assertEquals(ArchiveDeleteResult.DELETED, deleter.deleteMood(day))
            assertEquals(ArchiveDeleteResult.NOT_FOUND, deleter.deleteDiary(day))
            assertEquals("untouched", other.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private class TestRouter(private val root: File) : StorageRouter {
        override fun route(personId: String?, type: RecordType, date: LocalDate): File =
            File(root, "${type.wire}/$date.md")

        override fun read(file: File): Document = error("not used")

        override fun write(file: File, doc: Document) = error("not used")

        override suspend fun exportTo(zipUri: Uri) = Unit

        override suspend fun importFrom(zipUri: Uri) = Unit
    }
}
