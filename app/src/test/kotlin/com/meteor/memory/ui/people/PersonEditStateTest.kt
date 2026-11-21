package com.meteor.memory.ui.people

import com.meteor.memory.core.storage.Relation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人物编辑页的「未保存修改」判定（T10）。
 *
 * 只有 称呼 / 关系 / 标签 / 备注 / 正文 与加载时的档案不同才算脏；标签库
 * （customTags）是即时持久化的设置，不参与这个判断，否则只浏览标签就会弹返回确认。
 */
class PersonEditStateTest {

    private val baseline = PersonEditBaseline(
        displayName = "Ann",
        relation = Relation.FRIEND,
        note = "classmate",
        notes = "body",
        tags = listOf("school"),
    )

    private fun cleanState(): PersonEditState = PersonEditState(
        displayName = "Ann",
        relation = Relation.FRIEND,
        note = "classmate",
        notes = "body",
        tags = listOf("school"),
        baseline = baseline,
    )

    @Test
    fun `a freshly loaded profile is clean`() {
        assertFalse(cleanState().hasUnsavedChanges)
    }

    @Test
    fun `a new person page with the default name is clean`() {
        // New profiles start from `personId.take(8)` as the default name; that
        // is the loaded baseline, not a pending edit.
        val state = PersonEditState(
            displayName = "abc12345",
            baseline = PersonEditBaseline(displayName = "abc12345"),
        )
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `every editable field counts as a change`() {
        val clean = cleanState()
        assertTrue(clean.copy(displayName = "Ann B").hasUnsavedChanges)
        assertTrue(clean.copy(relation = Relation.FAMILY).hasUnsavedChanges)
        assertTrue(clean.copy(note = "neighbour").hasUnsavedChanges)
        assertTrue(clean.copy(notes = "body with a picture").hasUnsavedChanges)
        assertTrue(clean.copy(tags = listOf("school", "work")).hasUnsavedChanges)
    }

    @Test
    fun `reordering tags is a change too`() {
        // Tags are stored in order, so a reorder is a real edit worth confirming.
        val two = baseline.copy(tags = listOf("school", "work"))
        val state = cleanState().copy(tags = listOf("work", "school"), baseline = two)
        assertTrue(state.hasUnsavedChanges)
    }

    @Test
    fun `tag library changes alone are not unsaved profile edits`() {
        // Remembering a label persists in settings immediately; the profile file
        // does not change, so there is nothing to lose by going back.
        val state = cleanState().copy(customTags = listOf("colleague"))
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `advancing the baseline after a save makes the state clean`() {
        val edited = cleanState().copy(displayName = "Ann B")
        assertTrue(edited.hasUnsavedChanges)

        val saved = edited.copy(
            baseline = edited.baseline.copy(displayName = "Ann B"),
            saved = true,
        )
        assertFalse(saved.hasUnsavedChanges)
    }

    @Test
    fun `edits made while saving stay unsaved`() {
        // The baseline advances to the saved snapshot; text entered during the
        // write must still trigger the confirmation.
        val state = cleanState().copy(
            displayName = "Ann B",
            baseline = baseline.copy(displayName = "Ann"),
        )
        assertTrue(state.hasUnsavedChanges)
    }
}
