package se.whitchurch.nordict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import se.whitchurch.nordict.OrdbokenContract.HistoryEntry

class HistoryFragment : CommonListFragment(HistoryEntry.TABLE_NAME) {

    override fun getCursor(db: SQLiteDatabase): Cursor {
        val projection = arrayOf(
            HistoryEntry._ID,
            HistoryEntry.COLUMN_NAME_DICT,
            HistoryEntry.COLUMN_NAME_TITLE,
            HistoryEntry.COLUMN_NAME_SUMMARY,
            HistoryEntry.COLUMN_NAME_URL,
            HistoryEntry.COLUMN_NAME_DATE
        )
        val sortOrder = HistoryEntry.COLUMN_NAME_DATE + " DESC"
        return db.query(
            HistoryEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder,
            "100"
        )
    }

    companion object {

        fun newInstance(): HistoryFragment {
            return HistoryFragment()
        }
    }
}
