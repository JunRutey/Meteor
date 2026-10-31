package com.meteor.memory.core.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Full-text index implementing the frozen [IndexModule] contract and the
 * additive [IndexUpdater].
 *
 * The storage engine is chosen at runtime by [MeteorIndexDatabase]; ranking is
 * always computed in Kotlin via [TextMatcher] so results are ordered the same
 * whether the device has FTS5, FTS4, or neither.
 *
 * Content comes from [RecordScanner], i.e. through `:core-storage`; this class
 * never touches a key or a file directly (archive R6).
 */
class MeteorIndex(
    context: Context,
    private val rootDir: File,
    private val scanner: RecordScanner,
) : IndexModule, IndexUpdater {

    private val database = MeteorIndexDatabase(context)
    private val writeLock = Mutex()

    /** Which engine is in use; exposed for diagnostics. */
    val backend: SearchBackend get() = database.backend

    // --- IndexModule -------------------------------------------------------

    override suspend fun search(plan: QueryPlan): List<SearchHit> = withContext(Dispatchers.IO) {
        val terms = CjkTokenizer.tokenize(plan.ftsExpression).distinct()
        if (terms.isEmpty()) return@withContext emptyList()

        val candidates = queryCandidates(terms, plan)

        candidates
            .map { row -> row to TextMatcher.countMatches(row.body, terms) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<Candidate, Int>> { it.second }
                    .thenByDescending { it.first.timestamp },
            )
            .take(plan.maxHits.coerceIn(1, MAX_HITS))
            .map { (row, score) ->
                SearchHit(
                    recordId = row.recordId,
                    personId = row.personId,
                    type = row.type,
                    timestamp = row.timestamp,
                    snippet = TextMatcher.snippet(row.body, terms, plan.snippetChars),
                    score = score.toDouble(),
                    sourcePath = row.sourcePath,
                )
            }
    }

    override suspend fun overview(): IndexOverview = withContext(Dispatchers.IO) {
        val db = database.readableDatabase

        val typeCounts = LinkedHashMap<RecordType, Int>()
        db.rawQuery(
            "SELECT ${IndexSchema.COLUMN_TYPE}, COUNT(*) FROM ${IndexSchema.TABLE_RECORDS} GROUP BY 1",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                RecordType.fromWire(cursor.getString(0))?.let { typeCounts[it] = cursor.getInt(1) }
            }
        }

        val persons = mutableListOf<PersonBrief>()
        db.rawQuery(
            """
            SELECT ${IndexSchema.COLUMN_PERSON_ID}, COUNT(*), MAX(${IndexSchema.COLUMN_TIMESTAMP})
            FROM ${IndexSchema.TABLE_RECORDS}
            WHERE ${IndexSchema.COLUMN_PERSON_ID} IS NOT NULL
            GROUP BY 1
            ORDER BY 3 DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                persons.add(
                    PersonBrief(
                        personId = cursor.getString(0),
                        // Filled in v0.5 once profile records are parsed while indexing.
                        displayName = null,
                        recordCount = cursor.getInt(1),
                        lastActive = Instant.ofEpochMilli(cursor.getLong(2)),
                    ),
                )
            }
        }

        val timeSpan = db.rawQuery(
            "SELECT MIN(${IndexSchema.COLUMN_TIMESTAMP}), MAX(${IndexSchema.COLUMN_TIMESTAMP}) " +
                "FROM ${IndexSchema.TABLE_RECORDS}",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val first = Instant.ofEpochMilli(cursor.getLong(0)).atZone(ZONE).toLocalDate()
                val last = Instant.ofEpochMilli(cursor.getLong(1)).atZone(ZONE).toLocalDate()
                first..last
            } else {
                null
            }
        }

        IndexOverview(persons = persons, typeCounts = typeCounts, timeSpan = timeSpan)
    }

    override suspend fun queryByPerson(personId: String): List<Record> = withContext(Dispatchers.IO) {
        val sql =
            "SELECT ${IndexSchema.COLUMN_RECORD_ID}, ${IndexSchema.COLUMN_PERSON_ID}, " +
                "${IndexSchema.COLUMN_TYPE}, ${IndexSchema.COLUMN_TIMESTAMP}, " +
                "${IndexSchema.COLUMN_SOURCE_PATH} " +
                "FROM ${IndexSchema.TABLE_RECORDS} WHERE ${IndexSchema.COLUMN_PERSON_ID} = ? " +
                "ORDER BY ${IndexSchema.COLUMN_TIMESTAMP} DESC"

        val records = mutableListOf<Record>()
        database.readableDatabase.rawQuery(sql, arrayOf(personId)).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(
                    Record(
                        recordId = cursor.getString(0),
                        personId = cursor.getString(1),
                        type = RecordType.fromWire(cursor.getString(2)) ?: RecordType.DIARY,
                        timestamp = Instant.ofEpochMilli(cursor.getLong(3)),
                        sourcePath = cursor.getString(4),
                    ),
                )
            }
        }
        records
    }

    override suspend fun rebuild() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                database.recreate(db)
                scanner.scan().forEach { upsert(db, it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        Unit
    }

    // --- IndexUpdater ------------------------------------------------------

    override suspend fun upsert(document: Document) = withContext(Dispatchers.IO) {
        writeLock.withLock { upsert(database.writableDatabase, document) }
        Unit
    }

    override suspend fun remove(sourcePath: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val db = database.writableDatabase
            val id = IndexSchema.recordIdFor(sourcePath)
            db.delete(IndexSchema.TABLE_RECORDS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(id))
            if (database.backend != SearchBackend.LIKE) {
                db.delete(IndexSchema.TABLE_FTS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(id))
            }
        }
        Unit
    }

    // --- internals ---------------------------------------------------------

    private data class Candidate(
        val recordId: String,
        val personId: String?,
        val type: RecordType,
        val timestamp: Instant,
        val sourcePath: String,
        val body: String,
    )

    /**
     * Narrows rows using whichever engine is available. `LIKE` has no notion of
     * tokens, so it matches the raw bigrams as substrings, which is exactly the
     * behaviour the tokenizer produces for Chinese anyway.
     */
    private fun queryCandidates(terms: List<String>, plan: QueryPlan): List<Candidate> {
        val backend = database.backend
        val arguments = mutableListOf<String>()

        val sql = StringBuilder("SELECT ")
            .append("r.${IndexSchema.COLUMN_RECORD_ID}, r.${IndexSchema.COLUMN_PERSON_ID}, ")
            .append("r.${IndexSchema.COLUMN_TYPE}, r.${IndexSchema.COLUMN_TIMESTAMP}, ")
            .append("r.${IndexSchema.COLUMN_SOURCE_PATH}, r.${IndexSchema.COLUMN_BODY} ")

        when (backend) {
            SearchBackend.FTS5, SearchBackend.FTS4 -> {
                sql.append("FROM ${IndexSchema.TABLE_FTS} ")
                    .append("JOIN ${IndexSchema.TABLE_RECORDS} r ")
                    .append("ON r.${IndexSchema.COLUMN_RECORD_ID} = ")
                    .append("${IndexSchema.TABLE_FTS}.${IndexSchema.COLUMN_RECORD_ID} ")
                    .append("WHERE ${IndexSchema.TABLE_FTS} MATCH ?")
                arguments.add(CjkTokenizer.matchExpression(plan.ftsExpression))
            }

            SearchBackend.LIKE -> {
                sql.append("FROM ${IndexSchema.TABLE_RECORDS} r WHERE (")
                sql.append(terms.joinToString(" OR ") { "(r.${IndexSchema.COLUMN_BODY} LIKE ?)" })
                sql.append(")")
                terms.forEach { arguments.add("%$it%") }
            }
        }

        if (plan.personIds.isNotEmpty()) {
            sql.append(" AND r.${IndexSchema.COLUMN_PERSON_ID} IN (${placeholders(plan.personIds.size)})")
            arguments.addAll(plan.personIds)
        }
        if (plan.types.isNotEmpty()) {
            sql.append(" AND r.${IndexSchema.COLUMN_TYPE} IN (${placeholders(plan.types.size)})")
            plan.types.forEach { arguments.add(it.wire) }
        }
        plan.timeRange?.let { range ->
            sql.append(" AND r.${IndexSchema.COLUMN_TIMESTAMP} BETWEEN ? AND ?")
            arguments.add(range.start.atStartOfDay(ZONE).toInstant().toEpochMilli().toString())
            arguments.add(
                range.endInclusive.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli().toString(),
            )
        }

        sql.append(" LIMIT $MAX_CANDIDATES")

        val rows = ArrayList<Candidate>()
        database.readableDatabase
            .rawQuery(sql.toString(), arguments.toTypedArray())
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(
                        Candidate(
                            recordId = cursor.getString(0),
                            personId = if (cursor.isNull(1)) null else cursor.getString(1),
                            type = RecordType.fromWire(cursor.getString(2)) ?: RecordType.DIARY,
                            timestamp = Instant.ofEpochMilli(cursor.getLong(3)),
                            sourcePath = cursor.getString(4),
                            body = cursor.getString(5),
                        ),
                    )
                }
            }
        return rows
    }

    private fun upsert(db: SQLiteDatabase, document: Document) {
        val relative = document.file.relativeToOrNull(rootDir)?.invariantSeparatorsPath
            ?: document.file.name
        val recordId = IndexSchema.recordIdFor(relative)

        val row = ContentValues().apply {
            put(IndexSchema.COLUMN_RECORD_ID, recordId)
            if (document.personId == null) putNull(IndexSchema.COLUMN_PERSON_ID)
            else put(IndexSchema.COLUMN_PERSON_ID, document.personId)
            put(IndexSchema.COLUMN_TYPE, document.type.wire)
            put(IndexSchema.COLUMN_TIMESTAMP, document.timestamp.toEpochMilli())
            put(IndexSchema.COLUMN_SOURCE_PATH, relative)
            put(IndexSchema.COLUMN_BODY, document.body)
        }
        db.insertWithOnConflict(
            IndexSchema.TABLE_RECORDS,
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )

        // No FTS table at all in LIKE mode: the records table is the search source.
        if (database.backend == SearchBackend.LIKE) return

        db.delete(IndexSchema.TABLE_FTS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(recordId))
        val ftsRow = ContentValues().apply {
            put(IndexSchema.COLUMN_TOKENS, CjkTokenizer.indexedText(document.body))
            put(IndexSchema.COLUMN_RECORD_ID, recordId)
        }
        db.insert(IndexSchema.TABLE_FTS, null, ftsRow)
    }

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private companion object {
        const val MAX_HITS = 100
        const val MAX_CANDIDATES = 500
        val ZONE: ZoneId = ZoneId.systemDefault()
    }
}

private fun File.relativeToOrNull(other: File): File? = runCatching { relativeTo(other) }.getOrNull()
