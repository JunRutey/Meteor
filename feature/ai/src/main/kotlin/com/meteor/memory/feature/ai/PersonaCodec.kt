package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Relation
import java.time.Instant

/**
 * Body format of `数字遗产/数字人格/<person_id>/persona.md`.
 *
 * Same shape as a person's `profile.md` — ASCII header, a `---` separator, then
 * a free body — so the file stays readable inside the plaintext backup export.
 * The body is the model-written layered persona; unknown header keys are
 * ignored by older readers (ADR-027).
 *
 * ```
 * name: 小明
 * relation: friend
 * provider: deepseek
 * skill: meteor-persona-distill
 * generated: 2026-10-31T09:00:00Z
 * records: 1
 * diaries: 1
 * topic: 爬山 2
 * quote: 2025-03-14T10:00:00Z|那天下雨
 * ---
 * # 小明 — 数字人格
 * ## Layer 0：硬规则
 * ...
 * ```
 */
object PersonaCodec {

    private const val SEPARATOR = "---"

    const val KEY_NAME = "name"
    const val KEY_RELATION = "relation"
    const val KEY_PROVIDER = "provider"
    const val KEY_SKILL = "skill"
    const val KEY_GENERATED = "generated"
    const val KEY_RECORDS = "records"
    const val KEY_DIARIES = "diaries"
    const val KEY_FIRST = "first"
    const val KEY_LAST = "last"
    const val KEY_TOPIC = "topic"
    const val KEY_QUOTE = "quote"

    fun encode(snapshot: PersonaSnapshot): String = buildString {
        snapshot.displayName?.takeIf { it.isNotBlank() }?.let {
            append(KEY_NAME).append(": ").append(oneLine(it)).append('\n')
        }
        snapshot.relation?.let { append(KEY_RELATION).append(": ").append(it.code).append('\n') }
        snapshot.provider?.takeIf { it.isNotBlank() }?.let {
            append(KEY_PROVIDER).append(": ").append(oneLine(it)).append('\n')
        }
        snapshot.skill?.takeIf { it.isNotBlank() }?.let {
            append(KEY_SKILL).append(": ").append(oneLine(it)).append('\n')
        }
        append(KEY_GENERATED).append(": ").append(snapshot.generatedAt.toString()).append('\n')
        append(KEY_RECORDS).append(": ").append(snapshot.recordCount).append('\n')
        append(KEY_DIARIES).append(": ").append(snapshot.diaryMentions).append('\n')
        snapshot.firstSeen?.let { append(KEY_FIRST).append(": ").append(it.toString()).append('\n') }
        snapshot.lastSeen?.let { append(KEY_LAST).append(": ").append(it.toString()).append('\n') }
        snapshot.topics.forEach {
            append(KEY_TOPIC).append(": ").append(it.term).append(' ').append(it.count).append('\n')
        }
        snapshot.quotes.forEach {
            append(KEY_QUOTE).append(": ").append(it.at.toString())
                .append('|').append(oneLine(it.text)).append('\n')
        }
        append(SEPARATOR).append('\n')
        snapshot.body.trim().takeIf { it.isNotEmpty() }?.let { append(it).append('\n') }
    }

    /**
     * @return null when the body is not a persona snapshot (no separator, no
     * `generated` line). Callers treat that as "not written by us" rather than
     * repairing it — the same stance as [com.meteor.memory.core.storage.PersonProfileCodec].
     */
    fun decode(personId: String, body: String): PersonaSnapshot? {
        val normalized = body.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        val headerEnd = lines.indexOfFirst { it.trim() == SEPARATOR }
        if (headerEnd < 0) return null

        var displayName: String? = null
        var relation: Relation? = null
        var provider: String? = null
        var skill: String? = null
        var generatedAt: Instant? = null
        var records = 0
        var diaries = 0
        var firstSeen: Instant? = null
        var lastSeen: Instant? = null
        val topics = ArrayList<PersonaTopic>()
        val quotes = ArrayList<PersonaQuote>()

        for (line in lines.subList(0, headerEnd)) {
            if (line.isBlank()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when (key) {
                KEY_NAME -> displayName = value.takeIf { it.isNotBlank() }
                KEY_RELATION -> relation = Relation.fromCode(value)
                KEY_PROVIDER -> provider = value.takeIf { it.isNotBlank() }
                KEY_SKILL -> skill = value.takeIf { it.isNotBlank() }
                KEY_GENERATED -> generatedAt = instantOrNull(value)
                KEY_RECORDS -> records = value.toIntOrNull() ?: 0
                KEY_DIARIES -> diaries = value.toIntOrNull() ?: 0
                KEY_FIRST -> firstSeen = instantOrNull(value)
                KEY_LAST -> lastSeen = instantOrNull(value)
                KEY_TOPIC -> parseTopic(value)?.let(topics::add)
                KEY_QUOTE -> parseQuote(value)?.let(quotes::add)
            }
        }

        val generated = generatedAt ?: return null
        return PersonaSnapshot(
            personId = personId,
            displayName = displayName,
            relation = relation,
            recordCount = records,
            diaryMentions = diaries,
            firstSeen = firstSeen,
            lastSeen = lastSeen,
            topics = topics,
            quotes = quotes,
            generatedAt = generated,
            body = lines.drop(headerEnd + 1).joinToString("\n").trim(),
            provider = provider,
            skill = skill,
        )
    }

    private fun parseTopic(value: String): PersonaTopic? {
        val split = value.lastIndexOf(' ')
        if (split <= 0) return null
        val count = value.substring(split + 1).toIntOrNull() ?: return null
        val term = value.substring(0, split).trim()
        return if (term.isEmpty()) null else PersonaTopic(term, count)
    }

    private fun parseQuote(value: String): PersonaQuote? {
        val split = value.indexOf('|')
        if (split <= 0) return null
        val at = instantOrNull(value.substring(0, split)) ?: return null
        val text = value.substring(split + 1).trim()
        return if (text.isEmpty()) null else PersonaQuote(at, text)
    }

    private fun instantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()
}
