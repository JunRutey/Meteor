package com.meteor.memory.ui.search

import com.meteor.memory.core.index.CjkTokenizer
import com.meteor.memory.core.index.TextMatcher
import com.meteor.memory.core.storage.QuestionStore
import com.meteor.memory.core.storage.RecordType
import com.meteor.memory.core.storage.StoredQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searchable text for 每日一问 (ADR-074).
 *
 * Questions are encrypted `.bin` files and never go through `RecordScanner`, so
 * they are searched through this thin adapter over [QuestionStore] instead of
 * the frozen `IndexModule`. Like [AttachmentTextIndex], decrypted text never
 * reaches SQLite.
 *
 * The store is read fresh on every search rather than cached: a question is a
 * few hundred bytes, so re-reading is cheap, and it means a just-written answer
 * or a just-deleted one is reflected immediately without any invalidation hook
 * in the write paths. Nothing decrypted is retained between searches.
 *
 * One question is one hit — its text and **all** answers are concatenated — so
 * a match means "this question and its thinking", never a single answer lifted
 * out of context. Ranking reuses [TextMatcher], the same Kotlin-side ranking
 * [com.meteor.memory.core.index.MeteorIndex] uses, so a lone CJK character still
 * matches and results rank identically across backends.
 */
@Singleton
class QuestionTextIndex @Inject constructor(
    private val questionStore: QuestionStore,
) {

    suspend fun search(query: String, maxHits: Int, snippetChars: Int): List<SearchMatch> =
        withContext(Dispatchers.IO) {
            val terms = CjkTokenizer.tokenize(query).distinct()
            if (terms.isEmpty()) return@withContext emptyList()

            questionStore.list()
                .mapNotNull { question ->
                    val text = questionSearchableText(question)
                    val score = TextMatcher.countMatches(text, terms)
                    if (score == 0) return@mapNotNull null
                    SearchMatch(
                        recordId = question.id,
                        type = RecordType.QUESTION,
                        // The question's creation time is its anchor; a back-filled
                        // question still belongs to the day it was asked about.
                        timestamp = question.createdAt,
                        snippet = TextMatcher.snippet(text, terms, snippetChars),
                        score = score.toDouble(),
                    )
                }
                .sortedWith(
                    compareByDescending<SearchMatch> { it.score }
                        .thenByDescending { it.timestamp },
                )
                .take(maxHits.coerceAtLeast(1))
        }
}

/**
 * Problem text plus every answer, so "how did I answer that" is searchable.
 * Pure and top-level so the searchable-text contract is unit-testable without a
 * store or coroutines.
 */
internal fun questionSearchableText(question: StoredQuestion): String = buildString {
    append(question.text)
    question.answers.forEach { answer ->
        append('\n')
        append(answer.text)
    }
}
