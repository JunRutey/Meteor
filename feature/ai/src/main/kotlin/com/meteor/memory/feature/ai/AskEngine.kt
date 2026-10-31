package com.meteor.memory.feature.ai

import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordType
import kotlin.math.max
import kotlin.math.min

/**
 * Retrieval-only answering: no model, no generation, no sentence the archive
 * cannot show. A hit is a verbatim window of one stored record plus its date.
 *
 * Ranking is TF-IDF-lite over [TextFeatures] tokens: a query term that appears
 * in almost every record contributes little, a rare one contributes more.
 * Ties fall back to recency so the order stays deterministic.
 */
object AskEngine {

    /** Moods are bare codes and personas are derived, so neither is a source. */
    private val SEARCHABLE = setOf(RecordType.DIARY, RecordType.PROFILE, RecordType.CHAT_LOG)

    private const val SNIPPET_BEFORE = 40
    private const val SNIPPET_AFTER = 120
    private const val ELLIPSIS = "\u2026"

    fun answer(
        request: AskRequest,
        corpus: List<Document>,
        recordId: (Document) -> String = { it.file.name },
    ): AskAnswer {
        val scoped = scopedCorpus(request.personId, corpus)
        val queryTokens = TextFeatures.tokens(request.question).distinct()
        if (queryTokens.isEmpty() || scoped.isEmpty()) {
            return AskAnswer(request.question, scoped.size, emptyList())
        }

        val tokenSets = scoped.map { TextFeatures.tokens(it.body).toSet() }

        val documentFrequency = HashMap<String, Int>()
        tokenSets.forEach { set -> set.forEach { token -> documentFrequency[token] = (documentFrequency[token] ?: 0) + 1 } }

        val weights: Map<String, Double> = queryTokens.associateWith { token ->
            TextFeatures.weight(documentFrequency[token] ?: 0, scoped.size)
        }

        val scored = scoped.mapIndexedNotNull { index, document ->
            val present = tokenSets[index]
            val score = weights.entries.filter { it.key in present }.sumOf { it.value }
            if (score <= 0.0) null else document to score
        }.sortedWith(
            compareByDescending<Pair<Document, Double>> { it.second }
                .thenByDescending { it.first.timestamp },
        )

        val matches = scored.take(request.maxHits.coerceIn(1, 20)).map { (document, score) ->
            AskMatch(
                at = document.timestamp,
                type = document.type,
                personId = document.personId,
                snippet = snippetOf(document.body, weights),
                score = score,
                recordId = recordId(document),
            )
        }
        return AskAnswer(request.question, scoped.size, matches)
    }

    private fun scopedCorpus(personId: String?, corpus: List<Document>): List<Document> {
        val link = personId?.let(::personLink)
        return corpus.filter { document ->
            document.type in SEARCHABLE && when {
                personId == null -> true
                document.personId == personId -> true
                else -> link != null && document.body.contains(link)
            }
        }
    }

    /** The window is chosen around the strongest query term actually present. */
    private fun snippetOf(body: String, weights: Map<String, Double>): String {
        val token = weights.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .firstNotNullOfOrNull { candidate -> body.indexOf(candidate, ignoreCase = true).takeIf { it >= 0 } }
            ?: return body.take(SNIPPET_AFTER).replace('\n', ' ').trim()

        val start = max(0, token - SNIPPET_BEFORE)
        val end = min(body.length, token + SNIPPET_AFTER)
        return buildString {
            if (start > 0) append(ELLIPSIS)
            append(body.substring(start, end).replace('\n', ' ').trim())
            if (end < body.length) append(ELLIPSIS)
        }
    }
}
