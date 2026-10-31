package com.meteor.memory.core.index

/**
 * Which full-text engine the device actually supports.
 *
 * Android ships whatever SQLite build the OEM compiled, so FTS5 is *not*
 * guaranteed — Xiaomi/HyperOS on API 36 rejects `CREATE VIRTUAL TABLE ... USING
 * fts5` with `no such module: fts5`. FTS4 with the `unicode61` tokenizer is the
 * broadly available middle ground; if even that is missing we fall back to a
 * plain `LIKE` scan so search degrades instead of breaking.
 */
enum class SearchBackend {
    FTS5,
    FTS4,
    LIKE,
}

/**
 * Schema for the derived index.
 *
 * Nothing here is authoritative: `rebuild()` recreates the database from
 * `:core-storage` at any time (archive R4), which is also why schema upgrades
 * simply drop everything.
 */
internal object IndexSchema {

    const val DATABASE_NAME = "meteor_index.db"
    const val VERSION = 2

    const val TABLE_RECORDS = "records"
    const val TABLE_FTS = "records_fts"
    const val TABLE_META = "meta"

    const val COLUMN_RECORD_ID = "record_id"
    const val COLUMN_PERSON_ID = "person_id"
    const val COLUMN_TYPE = "type"
    const val COLUMN_TIMESTAMP = "timestamp"
    const val COLUMN_SOURCE_PATH = "source_path"
    const val COLUMN_BODY = "body"
    const val COLUMN_TOKENS = "tokens"

    const val META_BACKEND = "backend"

    val createRecords = """
        CREATE TABLE $TABLE_RECORDS (
            $COLUMN_RECORD_ID   TEXT PRIMARY KEY NOT NULL,
            $COLUMN_PERSON_ID   TEXT,
            $COLUMN_TYPE        TEXT NOT NULL,
            $COLUMN_TIMESTAMP   INTEGER NOT NULL,
            $COLUMN_SOURCE_PATH TEXT NOT NULL,
            $COLUMN_BODY        TEXT NOT NULL
        )
    """.trimIndent()

    val createMeta = """
        CREATE TABLE $TABLE_META (
            key   TEXT PRIMARY KEY NOT NULL,
            value TEXT NOT NULL
        )
    """.trimIndent()

    val createTimestampIndex =
        "CREATE INDEX idx_records_timestamp ON $TABLE_RECORDS($COLUMN_TIMESTAMP DESC)"

    val createFts5 = """
        CREATE VIRTUAL TABLE $TABLE_FTS USING fts5(
            $COLUMN_TOKENS,
            $COLUMN_RECORD_ID UNINDEXED,
            tokenize = 'unicode61'
        )
    """.trimIndent()

    // FTS4 needs the tokenizer named explicitly; unicode61 keeps CJK bigrams
    // intact, which the 'simple' tokenizer would discard.
    val createFts4 = """
        CREATE VIRTUAL TABLE $TABLE_FTS USING fts4(
            $COLUMN_TOKENS,
            $COLUMN_RECORD_ID,
            notindexed=$COLUMN_RECORD_ID,
            tokenize=unicode61
        )
    """.trimIndent()

    val dropAll = listOf(
        "DROP TABLE IF EXISTS $TABLE_FTS",
        "DROP TABLE IF EXISTS $TABLE_RECORDS",
        "DROP TABLE IF EXISTS $TABLE_META",
    )

    /** Stable identifier for a record: derived from its path, so re-indexing is idempotent. */
    fun recordIdFor(sourcePath: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(sourcePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
