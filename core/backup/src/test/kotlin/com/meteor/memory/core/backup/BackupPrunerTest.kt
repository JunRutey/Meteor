package com.meteor.memory.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupPrunerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun file(name: String, modifiedAt: Long): File =
        temporaryFolder.newFile(name).apply { setLastModified(modifiedAt) }

    @Test
    fun `keeps the newest snapshots and drops the rest`() {
        val oldest = file("2025-03-10.zip", 1_000L)
        val middle = file("2025-03-11.zip", 2_000L)
        val newest = file("2025-03-12.zip", 3_000L)

        val doomed = BackupPruner.selectForDeletion(listOf(oldest, middle, newest), retention = 2)

        assertEquals(listOf(oldest), doomed)
    }

    @Test
    fun `retention of one keeps only the latest`() {
        val a = file("a.zip", 1_000L)
        val b = file("b.zip", 5_000L)

        assertEquals(listOf(a), BackupPruner.selectForDeletion(listOf(a, b), retention = 1))
    }

    @Test
    fun `nothing is deleted while under the limit`() {
        val a = file("a.zip", 1_000L)
        val b = file("b.zip", 2_000L)

        assertTrue(BackupPruner.selectForDeletion(listOf(a, b), retention = 7).isEmpty())
    }

    @Test
    fun `non-positive retention still keeps one snapshot`() {
        val a = file("a.zip", 1_000L)
        val b = file("b.zip", 9_000L)

        assertEquals(listOf(a), BackupPruner.selectForDeletion(listOf(a, b), retention = 0))
    }

    @Test
    fun `empty list is safe`() {
        assertTrue(BackupPruner.selectForDeletion(emptyList(), retention = 3).isEmpty())
    }
}
