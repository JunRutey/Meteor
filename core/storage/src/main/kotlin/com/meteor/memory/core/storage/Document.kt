package com.meteor.memory.core.storage

import java.io.File
import java.time.Instant

/** Record kinds permitted in frontmatter (`type` field). */
enum class RecordType(val wire: String, val labelKey: String) {
    PROFILE("profile", "type_profile"),
    CHAT_LOG("chat_log", "type_chat_log"),
    DIARY("diary", "type_diary"),
    PERSONA("persona", "type_persona"),
    MOOD("mood", "type_mood");

    companion object {
        fun fromWire(value: String): RecordType? = entries.firstOrNull { it.wire == value }
    }
}

/** Who authored the record (`source` field). */
enum class RecordSource(val wire: String) {
    USER("user"),
    METEOR_AI("meteor-ai");

    companion object {
        fun fromWire(value: String): RecordSource? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Decrypted, in-memory representation of one Markdown record.
 *
 * [body] is always plaintext. The `meteor-sig` marker is already stripped by
 * the time a caller sees it — see [com.meteor.memory.core.time.TimeModule.strip].
 */
data class Document(
    val file: File,
    val personId: String?,
    val type: RecordType,
    val timestamp: Instant,
    val source: RecordSource,
    val body: String,
)
