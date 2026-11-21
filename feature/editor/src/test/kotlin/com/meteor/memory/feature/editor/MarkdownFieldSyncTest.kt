package com.meteor.memory.feature.editor

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The field ⇄ block sync rule behind the formatting toggles (ADR-051).
 *
 * The v1.4 regression this pins down: a formatting action rewrote the block but
 * the field never showed the rewrite, so bold and colour looked like no-ops and
 * the next keystroke deleted the markers again.
 */
class MarkdownFieldSyncTest {

    @Test
    fun `a rewrite that restores a caret installs the new text too`() {
        val field = resyncedField("今天**天气很**好", TextRange(7, 7))
        assertEquals("今天**天气很**好", field.text)
        assertEquals(TextRange(7, 7), field.selection)
    }

    @Test
    fun `a rewrite without a caret lands at the end`() {
        val field = resyncedField("新文字", null)
        assertEquals("新文字", field.text)
        assertEquals(TextRange(3, 3), field.selection)
    }

    @Test
    fun `a restored selection keeps its range`() {
        val field = resyncedField("**重点**", TextRange(2, 4))
        assertEquals("**重点**", field.text)
        assertEquals(TextRange(2, 4), field.selection)
    }

    @Test
    fun `a restored caret is clamped into the rewritten text`() {
        // A rewrite may shorten the block; the caret must stay in range
        // instead of pointing past the end.
        val field = resyncedField("短", TextRange(5, 9))
        assertEquals(TextRange(1, 1), field.selection)
    }

    @Test
    fun `an empty rewrite is still installed`() {
        val field = resyncedField("", TextRange(2, 2))
        assertEquals("", field.text)
        assertEquals(TextRange(0, 0), field.selection)
    }
}
