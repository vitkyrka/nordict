package se.whitchurch.nordict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

import se.whitchurch.nordict.OrdbokenContract.FavoritesEntry

class FavoritesFragment : CommonListFragment(FavoritesEntry.TABLE_NAME) {

    override fun getCursor(db: SQLiteDatabase): Cursor {
        val projection = arrayOf(
            FavoritesEntry._ID,
            FavoritesEntry.COLUMN_NAME_DICT,
            FavoritesEntry.COLUMN_NAME_TITLE,
            FavoritesEntry.COLUMN_NAME_SUMMARY,
            FavoritesEntry.COLUMN_NAME_URL
        )
        val sortOrder = FavoritesEntry.COLUMN_NAME_TITLE + " ASC"
        return db.query(FavoritesEntry.TABLE_NAME, projection, null, null, null, null, sortOrder)
    }

    companion object {

        fun newInstance(): FavoritesFragment {
            return FavoritesFragment()
        }
    }
}
