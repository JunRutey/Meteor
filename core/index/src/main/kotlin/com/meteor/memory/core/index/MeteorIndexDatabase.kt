package com.meteor.memory.core.index

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Opens the derived index database.
 *
 * `onUpgrade` drops everything: the index is a cache, and a schema change is
 * cheaper to absorb by rebuilding than by writing migrations (archive R4).
 */
internal class MeteorIndexDatabase(context: Context) :
    SQLiteOpenHelper(context, IndexSchema.DATABASE_NAME, null, IndexSchema.VERSION) {

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        IndexSchema.dropAll.forEach(db::execSQL)
        createSchema(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        IndexSchema.dropAll.forEach(db::execSQL)
        createSchema(db)
    }

    fun createSchema(db: SQLiteDatabase) {
        db.execSQL(IndexSchema.createRecords)
        db.execSQL(IndexSchema.createFts)
        db.execSQL(IndexSchema.createTimestampIndex)
    }
}
