package com.meteor.memory.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The value a text block's field must show after the block was rewritten from
 * the outside: a formatting toggle (ADR-051) or an inserted link.
 *
 * Pure so the sync rule is unit-testable. The v1.4 bold/colour regression was
 * exactly this glue dropping the rewritten text while restoring the caret: the
 * markers landed in the stored block only, the field kept showing the old run,
 * and the next keystroke rebuilt the block from that stale text and threw the
 * markers away. A rewrite the field never displays is a rewrite that never
 * happened as far as the writer can tell.
 *
 * [restoredSelection] is a **source** offset range — the same coordinates the
 * field's own selection uses — and is clamped into the rewritten text, since a
 * rewrite may have shortened it.
 */
internal fun resyncedField(value: String, restoredSelection: TextRange?): TextFieldValue {
    val selection = restoredSelection
        ?.let { requested ->
            TextRange(
                requested.start.coerceIn(0, value.length),
                requested.end.coerceIn(0, value.length),
            )
        }
        ?: TextRange(value.length)
    return TextFieldValue(text = value, selection = selection)
}
