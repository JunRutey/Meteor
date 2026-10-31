package com.meteor.memory.core.index

/**
 * SQLite schema for the derived index.
 *
 * Two tables:
 *  - `records`      one row per source file, keeps the plaintext body so snippets
 *                   can be cut without touching the filesystem
 *  - `records_fts`  FTS5 table holding pre-tokenized text (ADR-015)
 *
 * Nothing here is authoritative: `rebuild()` can recreate the whole database
 * from `:core-storage` at any time (archive R4).
 */
internal object IndexSchema {

    const val DATABASE_NAME = "meteor_index.db"
    const val VERSION = 1

    const val TABLE_RECORDS = "records"
    const val TABLE_FTS = "records_fts"

    const val COLUMN_RECORD_ID = "record_id"
    const val COLUMN_PERSON_ID = "person_id"
    const val COLUMN_TYPE = "type"
    const val COLUMN_TIMESTAMP = "timestamp"
    const val COLUMN_SOURCE_PATH = "source_path"
    const val COLUMN_BODY = "body"
    const val COLUMN_TOKENS = "tokens"

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

    val createFts = """
        CREATE VIRTUAL TABLE $TABLE_FTS USING fts5(
            $COLUMN_TOKENS,
            $COLUMN_RECORD_ID UNINDEXED,
            tokenize = 'unicode61'
        )
    """.trimIndent()

    val createTimestampIndex =
        "CREATE INDEX idx_records_timestamp ON $TABLE_RECORDS($COLUMN_TIMESTAMP DESC)"

    val dropAll = listOf(
        "DROP TABLE IF EXISTS $TABLE_FTS",
        "DROP TABLE IF EXISTS $TABLE_RECORDS",
    )

    /** Stable identifier for a record: derived from its path, so re-indexing is idempotent. */
    fun recordIdFor(sourcePath: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(sourcePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
