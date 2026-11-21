package com.meteor.memory.ui.diary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「未保存修改」判定（T10）。
 *
 * 返回确认只该保护真的会被丢掉的内容：空的新日记不该弹窗，改动过的正文和
 * 插入过图片/媒体的正文都必须弹窗。基线无法从 `document.body` 现算——插入
 * 媒体也会重写 shell 文档——所以用保存/加载时的 [DiaryEditorState.savedBody]。
 */
class DiaryEditStateTest {

    @Test
    fun `a brand new empty diary has nothing to lose`() {
        val state = DiaryEditorState(text = "", savedBody = "")
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `typed text is an unsaved change`() {
        val state = DiaryEditorState(text = "hello", savedBody = "")
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun `a loaded diary without edits is clean`() {
        val state = DiaryEditorState(text = "hello", savedBody = "hello")
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `editing a loaded diary is dirty`() {
        val state = DiaryEditorState(text = "hello!", savedBody = "hello")
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun `clearing a stored diary still counts as an edit`() {
        // Deleting all text is a change too; returning silently would keep the
        // old body and the user would think the delete was saved.
        val state = DiaryEditorState(text = "", savedBody = "hello")
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun `advancing the baseline after a save makes the state clean`() {
        val typed = DiaryEditorState(text = "hello", savedBody = "")
        assertTrue(typed.hasUnsavedChanges)

        val saved = typed.copy(savedBody = typed.text, saved = true)
        assertFalse(saved.hasUnsavedChanges)
    }

    @Test
    fun `text typed while saving stays unsaved`() {
        // The baseline advances to the snapshot that was written, not to the
        // live text, so edits made during the write are still protected.
        val state = DiaryEditorState(text = "hello, world", savedBody = "hello")
        assertTrue(state.hasUnsavedChanges)
    }
}
