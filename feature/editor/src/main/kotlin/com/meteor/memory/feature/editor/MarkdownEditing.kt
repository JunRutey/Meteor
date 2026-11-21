package com.meteor.memory.feature.editor

import com.meteor.memory.core.storage.ImageStore
import com.meteor.memory.core.storage.MediaStore

/** One image reference found in a record body. */
data class AttachedImage(
    /** Bare path, usable with `ImageStore.load` / `delete`. */
    val token: String,
    /** Alt text written by the author, empty when the reference has none. */
    val label: String,
)

/**
 * One ADR-052 media reference found in a record body.
 *
 * [id] is what `MediaStore.find` / `delete` take; the kind is deliberately absent
 * because it lives in the encrypted metadata sidecar, not in the body.
 */
data class AttachedMedia(
    /** 32-hex media id, usable with `MediaStore.load` / `delete`. */
    val id: String,
    /** Alt text written by the author, empty when the reference has none. */
    val label: String,
)

/**
 * Markdown body manipulation, kept pure so it can be unit-tested.
 *
 * The editing surface itself is now plain text (ADR-021): no markup is hidden,
 * so there is no caret mapping to get wrong. What this object still owns is the
 * things the toolbar can insert — legacy images, media (image/video/audio, ADR-052)
 * and links — plus editing or removing a reference again.
 */
object MarkdownEditing {

    private val REFERENCE = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val MULTIPLE_SPACES = Regex(" {2,}")

    fun appendImage(body: String, token: String, alt: String = ""): String =
        appendBlock(body, ImageStore.markdownFor(token, alt))

    /**
     * Appends `![alt](meteor://media/<id>)` as its own block (ADR-052).
     *
     * The block shape (blank line before, trailing newline) is the same one
     * [appendImage] produces, so a media reference opened in the editor becomes an
     * inline player card exactly like an image becomes a picture card.
     */
    fun appendMedia(body: String, id: String, alt: String = ""): String =
        appendBlock(body, MediaStore.markdownFor(id, alt))

    fun appendLink(body: String, target: String, label: String?): String {
        val text = label?.takeIf { it.isNotBlank() } ?: target
        return appendInline(body, "[$text]($target)")
    }

    /**
     * Every image referenced by [body], in document order and without duplicates.
     *
     * [AttachedImage.token] is the bare path, because that is what
     * `ImageStore.load` / `delete` take; the `meteor://image/` prefix only exists
     * inside a body.
     */
    fun imagesOf(body: String): List<AttachedImage> =
        REFERENCE.findAll(body)
            .mapNotNull { match ->
                val target = match.groupValues[2]
                if (!target.startsWith(ImageStore.IMAGE_SCHEME)) return@mapNotNull null
                AttachedImage(
                    token = target.removePrefix(ImageStore.IMAGE_SCHEME),
                    label = match.groupValues[1],
                )
            }
            .distinctBy { it.token }
            .toList()

    /** Every `meteor://media/` reference in [body], in document order, deduplicated. */
    fun mediaOf(body: String): List<AttachedMedia> =
        REFERENCE.findAll(body)
            .mapNotNull { match ->
                val target = match.groupValues[2].trim()
                if (!target.startsWith(MediaStore.MEDIA_SCHEME)) return@mapNotNull null
                val id = target.removePrefix(MediaStore.MEDIA_SCHEME)
                id.takeIf { it.isNotBlank() }?.let { AttachedMedia(id = it, label = match.groupValues[1]) }
            }
            .distinctBy { it.id }
            .toList()

    /**
     * Drops one image reference from [body].
     *
     * A line that held nothing but the reference disappears completely —
     * otherwise every deletion would leave a growing pile of blank lines — while
     * an inline reference only loses itself and the space next to it.
     *
     * @return [body] unchanged when the reference is not there.
     */
    fun removeImage(body: String, token: String): String {
        if (token.isBlank()) return body
        return removeReference(body, ImageStore.IMAGE_SCHEME + token)
    }

    /**
     * Drops one `meteor://media/` reference from [body] (ADR-052).
     *
     * Same line rules as [removeImage]: a reference alone on its line takes the
     * line with it. Only the Markdown goes — the encrypted payload is the caller's
     * decision, exactly like an image file (ADR-020).
     *
     * @return [body] unchanged when no reference points at [id].
     */
    fun removeMedia(body: String, id: String): String {
        if (id.isBlank()) return body
        return removeReference(body, MediaStore.MEDIA_SCHEME + id)
    }

    /**
     * Rewrites the alt text of the reference pointing at [token].
     *
     * Alt text doubles as the picture's description (ADR-049): it is already a
     * Markdown field, so a description costs no new syntax and stays readable in
     * exports, the search index and anything else that parses the file.
     *
     * @return [body] unchanged when no reference points at [token].
     */
    fun setImageCaption(body: String, token: String, caption: String): String {
        if (token.isBlank()) return body
        return setReferenceCaption(body, ImageStore.IMAGE_SCHEME + token, caption)
    }

    /**
     * Rewrites the alt text of the `meteor://media/<id>` reference (ADR-052).
     *
     * For a picture the alt text is the caption; for a video or an audio file it
     * is the title the player card shows. One field, one Markdown string, no new
     * syntax.
     *
     * @return [body] unchanged when no reference points at [id].
     */
    fun setMediaCaption(body: String, id: String, caption: String): String {
        if (id.isBlank()) return body
        return setReferenceCaption(body, MediaStore.MEDIA_SCHEME + id, caption)
    }

    /**
     * Keeps a caption from breaking the surrounding Markdown: brackets would end
     * the alt text early and a line break would split the reference in two, so
     * both collapse into the nearest harmless character.
     */
    fun sanitizeCaption(raw: String): String =
        raw.replace('[', '（')
            .replace(']', '）')
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()

    private fun appendBlock(body: String, block: String): String {
        val trimmed = body.trimEnd()
        return when {
            trimmed.isEmpty() -> "$block\n"
            trimmed.endsWith("\n") -> "$trimmed$block\n"
            else -> "$trimmed\n\n$block\n"
        }
    }

    /**
     * The one implementation of "drop the reference pointing at [scheme + key]".
     *
     * The `raw` line is rebuilt from the *whole* Markdown, not from a partial
     * match, so a body with two references to the same target loses both — which is
     * what the author means when they delete the card they are looking at.
     */
    private fun removeReference(body: String, target: String): String {
        val reference = Regex("""!\[[^\]]*]\(${Regex.escape(target)}\)""")
        if (!reference.containsMatchIn(body)) return body

        val kept = body.split("\n").mapNotNull { line ->
            val stripped = reference.replace(line, "")
            when {
                stripped == line -> line
                // Line carried only the reference: drop it instead of leaving a gap.
                stripped.isBlank() -> null
                else -> stripped.replace(MULTIPLE_SPACES, " ").trim()
            }
        }
        // Removing the first or last line must not leave the body with an empty
        // line in front of (or behind) everything else.
        return kept.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }

    private fun setReferenceCaption(body: String, target: String, caption: String): String {
        val reference = Regex("""!\[[^\]]*]\(${Regex.escape(target)}\)""")
        if (!reference.containsMatchIn(body)) return body

        val replacement = "![${sanitizeCaption(caption)}]($target)"
        return reference.replace(body, Regex.escapeReplacement(replacement))
    }

    private fun appendInline(body: String, inline: String): String =
        if (body.isEmpty() || body.endsWith(" ") || body.endsWith("\n")) body + inline else "$body $inline"
}
