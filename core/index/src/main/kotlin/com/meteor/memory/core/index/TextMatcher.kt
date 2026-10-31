package com.meteor.memory.core.index

/**
 * Ranking and snippet extraction applied after the storage engine has filtered
 * candidates.
 *
 * Doing this in Kotlin rather than SQL keeps every backend (FTS5 / FTS4 / LIKE)
 * ranked identically — FTS4 has no `bm25()` at all, so a SQL-side score would
 * make results order differently depending on the device.
 */
object TextMatcher {

    fun countMatches(text: String, terms: List<String>): Int =
        terms.count { term -> text.contains(term, ignoreCase = true) }

    fun snippet(body: String, terms: List<String>, maxChars: Int): String {
        val limit = maxChars.coerceIn(MIN_SNIPPET, MAX_SNIPPET)
        val flat = body.replace('\n', ' ').trim()
        if (flat.length <= limit) return flat

        val firstHit = terms
            .map { flat.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull() ?: 0

        val start = (firstHit - limit / 4).coerceIn(0, (flat.length - limit).coerceAtLeast(0))
        val end = (start + limit).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end) + suffix
    }

    private const val MIN_SNIPPET = 40
    private const val MAX_SNIPPET = 2000
}
