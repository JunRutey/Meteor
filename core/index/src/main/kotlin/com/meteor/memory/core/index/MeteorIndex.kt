package com.meteor.memory.core.index

import android.content.ContentValues
import android.content.Context
import com.meteor.memory.core.storage.Document
import com.meteor.memory.core.storage.RecordScanner
import com.meteor.memory.core.storage.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * SQLite FTS5 implementation of the frozen [IndexModule] contract.
 *
 * Content comes from [RecordScanner], which decrypts through `:core-storage`;
 * this class never touches a key or a file directly (archive R6).
 */
class MeteorIndex(
    context: Context,
    private val rootDir: File,
    private val scanner: RecordScanner,
) : IndexModule {

    private val database = MeteorIndexDatabase(context)
    private val writeLock = Mutex()

    // --- IndexModule -------------------------------------------------------

    override suspend fun search(plan: QueryPlan): List<SearchHit> = withContext(Dispatchers.IO) {
        val expression = CjkTokenizer.matchExpression(plan.ftsExpression)
        if (expression.isEmpty()) return@withContext emptyList()

        val arguments = mutableListOf(expression)
        val sql = StringBuilder()
            .append(
                """
                SELECT r.${IndexSchema.COLUMN_RECORD_ID}, r.${IndexSchema.COLUMN_PERSON_ID},
                       r.${IndexSchema.COLUMN_TYPE}, r.${IndexSchema.COLUMN_TIMESTAMP},
                       r.${IndexSchema.COLUMN_SOURCE_PATH}, r.${IndexSchema.COLUMN_BODY},
                       bm25(${IndexSchema.TABLE_FTS}) AS rank
                FROM ${IndexSchema.TABLE_FTS}
                JOIN ${IndexSchema.TABLE_RECORDS} r
                  ON r.${IndexSchema.COLUMN_RECORD_ID} = ${IndexSchema.TABLE_FTS}.${IndexSchema.COLUMN_RECORD_ID}
                WHERE ${IndexSchema.TABLE_FTS} MATCH ?
                """.trimIndent(),
            )

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
            arguments.add(range.endInclusive.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli().toString())
        }

        // bm25() is lower-is-better and negative; ascending order is best-first.
        sql.append(" ORDER BY rank LIMIT ${plan.maxHits.coerceIn(1, MAX_HITS)}")

        val snippetTerms = CjkTokenizer.tokenize(plan.ftsExpression).distinct()
        val hits = mutableListOf<SearchHit>()
        database.readableDatabase.rawQuery(sql.toString(), arguments.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val body = cursor.getString(COLUMN_BODY_INDEX)
                hits.add(
                    SearchHit(
                        recordId = cursor.getString(0),
                        personId = if (cursor.isNull(1)) null else cursor.getString(1),
                        type = RecordType.fromWire(cursor.getString(2)) ?: RecordType.DIARY,
                        timestamp = Instant.ofEpochMilli(cursor.getLong(3)),
                        snippet = snippetOf(body, snippetTerms, plan.snippetChars),
                        score = -cursor.getDouble(6),
                        sourcePath = cursor.getString(4),
                    ),
                )
            }
        }
        hits
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
                        // Filled in v0.5 once profile records are parsed during indexing.
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
                IndexSchema.dropAll.forEach(db::execSQL)
                database.createSchema(db)
                scanner.scan().forEach { upsert(db, it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        Unit
    }

    /** Incremental update for one saved record; keeps `rebuild()` as the fallback. */
    suspend fun index(document: Document) = withContext(Dispatchers.IO) {
        writeLock.withLock { upsert(database.writableDatabase, document) }
        Unit
    }

    suspend fun remove(sourcePath: String) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val db = database.writableDatabase
            val id = IndexSchema.recordIdFor(sourcePath)
            db.delete(IndexSchema.TABLE_RECORDS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(id))
            db.delete(IndexSchema.TABLE_FTS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(id))
        }
        Unit
    }

    // --- internals ---------------------------------------------------------

    private fun upsert(db: android.database.sqlite.SQLiteDatabase, document: Document) {
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
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )

        db.delete(IndexSchema.TABLE_FTS, "${IndexSchema.COLUMN_RECORD_ID} = ?", arrayOf(recordId))
        val ftsRow = ContentValues().apply {
            put(IndexSchema.COLUMN_TOKENS, CjkTokenizer.indexedText(document.body))
            put(IndexSchema.COLUMN_RECORD_ID, recordId)
        }
        db.insert(IndexSchema.TABLE_FTS, null, ftsRow)
    }

    private fun snippetOf(body: String, terms: List<String>, maxChars: Int): String {
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

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")

    private companion object {
        const val COLUMN_BODY_INDEX = 5
        const val MAX_HITS = 100
        const val MIN_SNIPPET = 40
        const val MAX_SNIPPET = 2000
        val ZONE: ZoneId = ZoneId.systemDefault()
    }
}

private fun File.relativeToOrNull(other: File): File? = runCatching { relativeTo(other) }.getOrNull()
