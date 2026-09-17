package se.whitchurch.nordict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry
import java.util.*

class OrdbokenDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATBASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_HISTORY)

        db.insert(HistoryEntry.TABLE_NAME, null, ContentValues().apply {
            put(HistoryEntry.COLUMN_NAME_TITLE, "ordbok")
            put(HistoryEntry.COLUMN_NAME_DICT, "SO")
            put(HistoryEntry.COLUMN_NAME_URL, "https://svenska.se/so/?sok=ordbok")
            put(HistoryEntry.COLUMN_NAME_SUMMARY, "")
            put(HistoryEntry.COLUMN_NAME_DATE, Date().time)
        })

        db.insert(HistoryEntry.TABLE_NAME, null, ContentValues().apply {
            put(HistoryEntry.COLUMN_NAME_TITLE, "ordbog")
            put(HistoryEntry.COLUMN_NAME_DICT, "DDO")
            put(
                HistoryEntry.COLUMN_NAME_URL,
                "https://ordnet.dk/ddo/ordbog?entry_id=11037894&query=."
            )
            put(HistoryEntry.COLUMN_NAME_SUMMARY, "")
            put(HistoryEntry.COLUMN_NAME_DATE, Date().time)
        })
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 5) {
            // Preserve history: combined words need the sources probe list.
            // Old rows keep the default (single-dictionary open).
            try {
                db.execSQL(
                    "ALTER TABLE " + HistoryEntry.TABLE_NAME + " ADD COLUMN " +
                        HistoryEntry.COLUMN_NAME_SOURCES + " TEXT DEFAULT ''"
                )
                return
            } catch (e: Exception) {
                // Column already exists (or another schema issue): fall through
                // to a clean recreate.
            }
        }
        db.execSQL(SQL_DELETE_HISTORY)
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        val DATABASE_VERSION = 5
        val DATBASE_NAME = "Ordboken.db"
        private val SQL_CREATE_HISTORY = "CREATE TABLE " + HistoryEntry.TABLE_NAME + " (" +
                HistoryEntry._ID + " INTEGER PRIMARY KEY," +
                HistoryEntry.COLUMN_NAME_DICT + " TEXT," +
                HistoryEntry.COLUMN_NAME_TITLE + " TEXT," +
                HistoryEntry.COLUMN_NAME_SUMMARY + " TEXT," +
                HistoryEntry.COLUMN_NAME_URL + " TEXT," +
                HistoryEntry.COLUMN_NAME_SOURCES + " TEXT DEFAULT ''," +
                HistoryEntry.COLUMN_NAME_DATE + " INTEGER," +
                "UNIQUE (" + HistoryEntry.COLUMN_NAME_URL +
                ") ON CONFLICT REPLACE" +
                " )"
        private val SQL_DELETE_HISTORY = "DROP TABLE IF EXISTS " + HistoryEntry.TABLE_NAME
    }
}
