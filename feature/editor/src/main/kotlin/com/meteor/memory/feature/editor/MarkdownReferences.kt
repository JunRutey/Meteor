package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore

/**
 * A reference that occupies a **whole line** of a body, new or legacy.
 *
 * Two generations coexist forever (ADR-052 §2):
 *
 * - `![描述](meteor://image/<token>)` — an ADR-049 image, decryptable through
 *   `ImageStore`, drawn inline as a picture;
 * - `![描述](meteor://media/<id>)` — ADR-052 media, whose **kind is not in the
 *   body**: the encrypted metadata sidecar decides whether it is a picture, a
 *   video or an audio file.
 *
 * The rule "a reference must be alone on its line" is what keeps the editor from
 * hiding characters inside a text field (ADR-049): only a whole line is lifted
 * out, so the text inside every text block stays byte-identical to storage.
 */
internal sealed interface LineReference {

    /** Markdown alt text — doubles as the description (ADR-049). */
    val caption: String

    /** `meteor://image/<token>`; [token] is the bare path `ImageStore` takes. */
    data class Image(val token: String, override val caption: String) : LineReference

    /** `meteor://media/<id>`; [id] is the 32-hex media id. */
    data class Media(val id: String, override val caption: String) : LineReference
}

internal object MarkdownReferences {

    /** Anchored on the whole line, so an inline reference stays editable text. */
    private val REFERENCE_LINE = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")

    /**
     * The reference a line stands for, or null when the line is ordinary text.
     *
     * Deliberately **scheme-strict**: a bare legacy token, a relative path and an
     * external `https://` picture all stay text. None of them can be drawn here,
     * and turning unopenable Markdown into a placeholder would hide the author's
     * own characters behind something they cannot edit.
     */
    fun of(line: String): LineReference? {
        val match = REFERENCE_LINE.matchEntire(line.trim()) ?: return null
        val caption = match.groupValues[1]
        val target = match.groupValues[2].trim()

        if (target.startsWith(ImageStore.IMAGE_SCHEME)) {
            val token = target.removePrefix(ImageStore.IMAGE_SCHEME)
            return token.takeIf { it.isNotBlank() }?.let { LineReference.Image(it, caption) }
        }

        if (target.startsWith(MediaStore.MEDIA_SCHEME)) {
            // The id is not validated here on purpose: a malformed one is a
            // *missing* reference, which the host renders as a placeholder. It is
            // still a reference — not something to hand back as internal-looking
            // text the author would have to delete by hand.
            val id = target.removePrefix(MediaStore.MEDIA_SCHEME)
            return id.takeIf { it.isNotBlank() }?.let { LineReference.Media(it, caption) }
        }

        return null
    }
}
