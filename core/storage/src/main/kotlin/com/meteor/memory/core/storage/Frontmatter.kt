package com.meteor.memory.core.storage

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The five plaintext frontmatter fields allowed on disk (archive §5.1).
 *
 * Nothing else may enter frontmatter: `display_name`, `relationship` and the
 * whole body belong in the ciphertext.
 */
data class DocumentHeader(
    val personId: String?,
    val type: RecordType,
    val timestamp: Instant,
    val source: RecordSource,
)

/** A parsed record: header, encoding scheme, and the still-encoded payload. */
data class ParsedFile(
    val header: DocumentHeader,
    val encScheme: String,
    val payload: String,
)

/**
 * Strict parser/serializer for the `---` fenced header.
 *
 * Hand-rolled on purpose: frontmatter is exactly five scalar keys, so pulling a
 * YAML library in would add a dependency and an attack surface for no gain.
 *
 * Two schemes exist on disk:
 *  - [ENC_SCHEME]      : the record as stored inside the app (payload is base64 ciphertext)
 *  - [PLAINTEXT_SCHEME]: inside an exported backup zip (payload is readable body)
 */
object Frontmatter {

    const val ENC_SCHEME = "aes-gcm-v1"
    const val PLAINTEXT_SCHEME = "plaintext"

    const val KEY_ENC = "meteor-enc"
    const val KEY_PERSON_ID = "person_id"
    const val KEY_TYPE = "type"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SOURCE = "source"

    private const val FENCE = "---"
    private val REQUIRED_KEYS = setOf(KEY_ENC, KEY_TYPE, KEY_TIMESTAMP, KEY_SOURCE)
    private val KNOWN_SCHEMES = setOf(ENC_SCHEME, PLAINTEXT_SCHEME)

    fun encode(header: DocumentHeader, encScheme: String = ENC_SCHEME): String = buildString {
        require(encScheme in KNOWN_SCHEMES) { "unknown scheme $encScheme" }
        append(FENCE).append('\n')
        append(KEY_ENC).append(": ").append(encScheme).append('\n')
        append(KEY_PERSON_ID).append(": ").append(header.personId.orEmpty()).append('\n')
        append(KEY_TYPE).append(": ").append(header.type.wire).append('\n')
        append(KEY_TIMESTAMP).append(": ")
            .append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(header.timestamp.atOffset(ZoneOffset.UTC)))
            .append('\n')
        append(KEY_SOURCE).append(": ").append(header.source.wire).append('\n')
        append(FENCE).append('\n')
    }

    /**
     * Splits a whole file into header and payload.
     *
     * @return null when the file is not a Meteor record (missing fence, missing
     * required key, unknown scheme, unparseable timestamp). Callers treat null
     * as "not ours" rather than repairing it.
     */
    fun split(text: String): ParsedFile? {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.size < 3 || lines.first().trim() != FENCE) return null

        val closeIndex = (1 until lines.size).firstOrNull { lines[it].trim() == FENCE } ?: return null
        val headerLines = lines.subList(1, closeIndex)
        val payload = lines.subList(closeIndex + 1, lines.size).joinToString("\n").trim('\n')

        val raw = LinkedHashMap<String, String>()
        for (line in headerLines) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            raw[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }

        if (!raw.keys.containsAll(REQUIRED_KEYS)) return null
        val encScheme = raw.getValue(KEY_ENC)
        if (encScheme !in KNOWN_SCHEMES) return null

        val type = RecordType.fromWire(raw.getValue(KEY_TYPE)) ?: return null
        val source = RecordSource.fromWire(raw.getValue(KEY_SOURCE)) ?: return null
        val timestamp = runCatching { Instant.parse(raw.getValue(KEY_TIMESTAMP)) }.getOrNull() ?: return null
        val personId = raw[KEY_PERSON_ID]?.takeIf { it.isNotBlank() }

        return ParsedFile(
            header = DocumentHeader(personId = personId, type = type, timestamp = timestamp, source = source),
            encScheme = encScheme,
            payload = payload,
        )
    }
}
