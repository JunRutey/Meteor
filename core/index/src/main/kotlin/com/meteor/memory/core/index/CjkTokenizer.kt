package com.meteor.memory.core.index

/**
 * Chinese-first tokenizer for SQLite FTS5 `unicode61` (see ADR-015).
 *
 * A CJK run becomes overlapping bigrams; a run of length one stays a single
 * token so that one-character queries still match. Latin and digit runs become
 * lowercased words. Everything else is a separator.
 *
 * Indexing and querying must use this same function, otherwise the two sides
 * disagree about what a term is.
 */
object CjkTokenizer {

    fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val tokens = ArrayList<String>(text.length)
        val buffer = StringBuilder()
        var cjkRun = StringBuilder()

        fun flushLatin() {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString().lowercase())
                buffer.setLength(0)
            }
        }

        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val run = cjkRun.toString()
            if (run.length == 1) {
                tokens.add(run)
            } else {
                for (i in 0 until run.length - 1) {
                    tokens.add(run.substring(i, i + 2))
                }
            }
            cjkRun = StringBuilder()
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushLatin()
                    cjkRun.append(ch)
                }

                ch.isLetterOrDigit() -> {
                    flushCjk()
                    buffer.append(ch)
                }

                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()

        return tokens
    }

    /** Indexable text for one record body. */
    fun indexedText(text: String): String = tokenize(text).joinToString(" ")

    /**
     * FTS5 MATCH expression for a raw user query.
     *
     * Tokens are quoted (they may collide with FTS5 syntax) and joined with OR
     * so a partially matching phrase still returns results; BM25 does the
     * ranking. Returns an empty string when the query holds no usable token.
     */
    fun matchExpression(query: String): String {
        val tokens = tokenize(query).distinct()
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF
    }
}
