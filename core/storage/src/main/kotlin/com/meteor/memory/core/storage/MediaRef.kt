package com.meteor.memory.core.storage

/**
 * A reference to one media file inside a record body, old or new (ADR-052 §3).
 *
 * Two generations of links coexist forever: images imported before ADR-052 are
 * stored as `meteor://image/<token>` (or a bare path token in old bodies), while
 * new media use `meteor://media/<id>`. Everything that resolves a reference goes
 * through [parse] so the two shapes are handled in exactly one place.
 */
sealed interface MediaRef {
    /** New encrypted media; [id] is 32 lowercase hex characters. */
    data class Media(val id: String) : MediaRef

    /** Legacy image token, i.e. the path-shaped string [ImageStore.save] returned. */
    data class LegacyImage(val token: String) : MediaRef

    /**
     * An imported document in [AttachmentStore] (ADR-065); [id] is 32 lowercase
     * hex characters, the same opaque shape the store writes.
     */
    data class File(val id: String) : MediaRef

    companion object {
        /** Scheme for an imported file reference, e.g. `meteor://file/<id>`. */
        const val FILE_SCHEME = "meteor://file/"

        /**
         * Parses `meteor://media/<id>`, `meteor://file/<id>`,
         * `meteor://image/<token>` or a bare legacy token; returns null for
         * anything else (external URLs, records, links).
         */
        fun parse(urlOrToken: String): MediaRef? {
            val raw = urlOrToken.trim()
            if (raw.isEmpty()) return null
            return when {
                raw.startsWith(MediaStore.MEDIA_SCHEME) -> {
                    val id = raw.removePrefix(MediaStore.MEDIA_SCHEME)
                    id.takeIf(MediaStore.MEDIA_ID_PATTERN::matches)?.let(::Media)
                }
                raw.startsWith(FILE_SCHEME) -> {
                    val id = raw.removePrefix(FILE_SCHEME)
                    id.takeIf(MediaStore.MEDIA_ID_PATTERN::matches)?.let(::File)
                }
                raw.startsWith(ImageStore.IMAGE_SCHEME) -> {
                    val token = raw.removePrefix(ImageStore.IMAGE_SCHEME)
                    token.takeIf(String::isNotBlank)?.let(::LegacyImage)
                }
                // A bare token is what pre-scheme bodies stored: a path-shaped
                // string produced by `ImageStore.save`. Anything with a scheme, a
                // query or free text is not a legacy token.
                raw.contains("://") || raw.contains('?') || raw.contains('#') -> null
                isLegacyTokenShaped(raw) -> LegacyImage(raw)
                else -> null
            }
        }

        /** Markdown image reference for a new media id. */
        fun markdown(id: String, alt: String = ""): String = MediaStore.markdownFor(id, alt)

        /**
         * Every token `ImageStore.save` can produce is at least
         * `<legacy>/<images>/<owner>/<file>.enc`, so a bare token must contain a
         * slash and end in the encrypted suffix; arbitrary body text never does.
         */
        private fun isLegacyTokenShaped(raw: String): Boolean {
            val normalized = raw.replace('\\', '/')
            if (normalized.startsWith("/")) return false
            if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
            return normalized.contains('/') && normalized.endsWith(EncryptedImageStore.EXTENSION)
        }
    }
}
