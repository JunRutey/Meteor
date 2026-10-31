package com.meteor.memory.core.index

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Opens the index database and works out which full-text engine this device
 * actually supports (see [SearchBackend]).
 *
 * The choice is persisted in the `meta` table so a device that lacks FTS5 does
 * not pay for the failed `CREATE VIRTUAL TABLE` on every launch.
 */
internal class MeteorIndexDatabase(context: Context) :
    SQLiteOpenHelper(context, IndexSchema.DATABASE_NAME, null, IndexSchema.VERSION) {

    @Volatile
    var backend: SearchBackend = SearchBackend.LIKE
        private set

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
        backend = detectAndCreateBackend(db)
        writeMeta(db, IndexSchema.META_BACKEND, backend.name)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = recreate(db)

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        backend = resolveBackend(db)
    }

    /** Drops everything and rebuilds the schema; used by `rebuild()` and on upgrade. */
    fun recreate(db: SQLiteDatabase) {
        IndexSchema.dropAll.forEach(db::execSQL)
        createTables(db)
        backend = detectAndCreateBackend(db)
        writeMeta(db, IndexSchema.META_BACKEND, backend.name)
    }

    fun createTables(db: SQLiteDatabase) {
        db.execSQL(IndexSchema.createRecords)
        db.execSQL(IndexSchema.createMeta)
        db.execSQL(IndexSchema.createTimestampIndex)
    }

    private fun resolveBackend(db: SQLiteDatabase): SearchBackend {
        val stored = readMeta(db, IndexSchema.META_BACKEND)
            ?.let { runCatching { SearchBackend.valueOf(it) }.getOrNull() }

        if (stored != null) {
            if (stored == SearchBackend.LIKE || ftsTableExists(db)) return stored
        }
        // Unknown, or the stored backend lost its table: probe again.
        val detected = detectAndCreateBackend(db)
        writeMeta(db, IndexSchema.META_BACKEND, detected.name)
        return detected
    }

    private fun detectAndCreateBackend(db: SQLiteDatabase): SearchBackend {
        if (tryCreate(db, IndexSchema.createFts5)) return SearchBackend.FTS5
        if (tryCreate(db, IndexSchema.createFts4)) return SearchBackend.FTS4
        return SearchBackend.LIKE
    }

    private fun tryCreate(db: SQLiteDatabase, statement: String): Boolean =
        try {
            db.execSQL(statement)
            true
        } catch (e: SQLiteException) {
            // "no such module: fts5" / "unknown tokenizer: unicode61" land here.
            false
        }

    private fun ftsTableExists(db: SQLiteDatabase): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(IndexSchema.TABLE_FTS),
        ).use { it.moveToFirst() }

    private fun readMeta(db: SQLiteDatabase, key: String): String? =
        runCatching {
            db.rawQuery(
                "SELECT value FROM ${IndexSchema.TABLE_META} WHERE key=?",
                arrayOf(key),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            IndexSchema.TABLE_META,
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}
