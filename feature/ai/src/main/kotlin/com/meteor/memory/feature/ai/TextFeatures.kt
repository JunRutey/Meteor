package com.meteor.memory.feature.ai

/**
 * Link scheme the editor writes into a body when a record mentions someone
 * (`[小明](meteor://person/<person_id>)`). Kept in one place so deriving a
 * persona and scoping a question agree on what "mentions this person" means.
 */
internal const val PERSON_LINK_PREFIX = "meteor://person/"

internal fun personLink(personId: String): String = "$PERSON_LINK_PREFIX$personId"

/**
 * Language-neutral tokenisation for the local engine.
 *
 * Same reasoning as ADR-015 (no native tokenizer): CJK runs become overlapping
 * bigrams, latin/digit runs become lowercase words. It is intentionally *not*
 * `:core-index`'s `CjkTokenizer` — `:feature-ai` must keep building when
 * `:core-index` is excluded (archive R3).
 *
 * Note this is a lexical splitter, not a word segmenter: it hardcodes no
 * language data at all, so there is no dictionary to maintain or to leak.
 */
object TextFeatures {

    /** Deterministic token list for [text]; order follows the input. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val run = StringBuilder()
        var han = false

        fun flush() {
            if (run.isEmpty()) return
            if (han) {
                if (run.length == 1) {
                    out.add(run.toString())
                } else {
                    for (index in 0 until run.length - 1) {
                        out.add(run.substring(index, index + 2))
                    }
                }
            } else if (run.length >= 2) {
                out.add(run.toString().lowercase())
            }
            run.setLength(0)
        }

        for (ch in text) {
            when {
                isHan(ch) -> {
                    if (!han) {
                        flush()
                        han = true
                    }
                    run.append(ch)
                }

                ch.isLetterOrDigit() -> {
                    if (han) {
                        flush()
                        han = false
                    }
                    run.append(ch)
                }

                else -> {
                    flush()
                    han = false
                }
            }
        }
        flush()
        return out
    }

    /**
     * Rarity weight: a token that occurs in most records discriminates little,
     * so it contributes less. Using document frequency instead of a hand-written
     * stopword list keeps the engine free of hardcoded language data.
     */
    fun weight(documentFrequency: Int, documents: Int): Double = when {
        documentFrequency <= 0 || documents <= 0 -> 0.0
        else -> kotlin.math.ln(1.0 + documents.toDouble() / documentFrequency)
    }

    private fun isHan(ch: Char): Boolean =
        Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
}
