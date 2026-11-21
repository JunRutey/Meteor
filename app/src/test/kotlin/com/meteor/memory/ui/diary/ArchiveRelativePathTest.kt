package com.meteor.memory.ui.diary

import com.meteor.memory.core.storage.StorageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 索引删除地址（T10）。
 *
 * `SearchService.removeRecord` 以「档案根下的相对路径」寻址；这里的结果必须与
 * `MeteorIndex.upsert` 写入的 `source_path` 完全一致，否则删除后索引里会留下一条
 * 指向已删文件的幽灵记录。
 */
class ArchiveRelativePathTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "meteor-archive")

    @Test
    fun `diary file resolves to the timeline relative path`() {
        val file = File(
            root,
            "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}/${StorageLayout.DIARY}/2026/2026-11-21.md",
        )
        val expected = "${StorageLayout.TIMELINE}/${StorageLayout.MOOD}/" +
            "${StorageLayout.DIARY}/2026/2026-11-21.md"
        assertEquals(expected, archiveRelativePath(root, file))
    }

    @Test
    fun `relative path always uses forward slashes`() {
        // The index stores `/`-separated paths on every platform; a Windows `\`
        // would make removeRecord delete nothing.
        val file = File(root, "a/b/c.md")
        val path = archiveRelativePath(root, file)
        assertEquals("a/b/c.md", path)
        assertFalse(path.contains('\\'))
    }

    @Test
    fun `a file directly under the root keeps just its name`() {
        assertEquals("note.md", archiveRelativePath(root, File(root, "note.md")))
    }
}
