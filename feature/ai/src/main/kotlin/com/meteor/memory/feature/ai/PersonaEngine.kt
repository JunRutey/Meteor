package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.PersonProfile
import com.meteor.memory.core.storage.RecordType
import java.time.Instant

/**
 * Deterministic derivation of a person's "digital persona" from the archive.
 *
 * Deliberately model-free (ADR-026): every output is a count, a timestamp, a
 * term that literally occurs in a record, or a stored line copied verbatim.
 * Nothing is paraphrased, so nothing can be invented.
 *
 * "The person's records" are:
 *  - records whose frontmatter `person_id` is theirs (profile, chat logs), and
 *  - diaries whose body links to them, i.e. contains `meteor://person/<id>`.
 */
object PersonaEngine {

    private const val MAX_TOPICS = 8
    private const val MAX_QUOTES = 3
    private const val MAX_QUOTE_CHARS = 80

    /** `[小明](meteor://person/abc)` → `小明`; images collapse to nothing. */
    private val METEOR_MD_LINK = Regex("""\[([^\]]*)]\(meteor://[^)\s]*\)""")
    private val METEOR_URI = Regex("""meteor://[^\s)\]]+""")
    private val WHITESPACE = Regex("""\s+""")

    fun snapshot(
        personId: String,
        profile: PersonProfile?,
        corpus: List<Document>,
        generatedAt: Instant,
        savedAt: Instant? = null,
    ): PersonaSnapshot {
        val link = personLink(personId)
        val own = corpus.filter { it.type != RecordType.PERSONA && it.personId == personId }
        val mentions = corpus.filter { it.type == RecordType.DIARY && it.body.contains(link) }
        val chatLogs = own.filter { it.type == RecordType.CHAT_LOG }

        // The profile body is an ASCII header plus notes; only the notes are
        // prose, and the person's own name must not become their top "topic".
        val nameTokens = TextFeatures.tokens(profile?.displayName.orEmpty()).toSet()
        val text = buildString {
            profile?.notes?.takeIf { it.isNotBlank() }?.let { append(normalizeLinks(it)).append('\n') }
            chatLogs.forEach { append(normalizeLinks(it.body)).append('\n') }
            mentions.forEach { append(normalizeLinks(it.body)).append('\n') }
        }

        val topics = TextFeatures.tokens(text)
            .filter { it !in nameTokens }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_TOPICS)
            .map { PersonaTopic(it.key, it.value) }

        val timeline = (own + mentions).sortedBy { it.timestamp }
        val quotes = (mentions.map { it to true } + chatLogs.map { it to false })
            .sortedByDescending { it.first.timestamp }
            .mapNotNull { (document, requiresLink) -> quoteOf(document, requiresLink) }
            .take(MAX_QUOTES)

        return PersonaSnapshot(
            personId = personId,
            displayName = profile?.displayName,
            relation = profile?.relation,
            recordCount = own.size,
            diaryMentions = mentions.size,
            firstSeen = timeline.firstOrNull()?.timestamp,
            lastSeen = timeline.lastOrNull()?.timestamp,
            topics = topics,
            quotes = quotes,
            generatedAt = generatedAt,
            savedAt = savedAt,
        )
    }

    private fun quoteOf(document: Document, requiresLink: Boolean): PersonaQuote? {
        val line = document.body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && (!requiresLink || it.contains(PERSON_LINK_PREFIX)) }
            ?: return null

        val text = normalizeLinks(line).replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        return PersonaQuote(at = document.timestamp, text = text.take(MAX_QUOTE_CHARS))
    }

    private fun normalizeLinks(text: String): String =
        text.replace(METEOR_MD_LINK, "\$1").replace(METEOR_URI, "")
}
