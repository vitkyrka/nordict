package se.whitchurch.nordict

import android.provider.BaseColumns

class OrdbokenContract {

    abstract class HistoryEntry : BaseColumns {
        companion object {
            val TABLE_NAME = "history"
            val _ID = "_id"
            val COLUMN_NAME_DICT = "dict"
            val COLUMN_NAME_DATE = "date"
            val COLUMN_NAME_TITLE = "title"
            val COLUMN_NAME_SUMMARY = "summary"
            val COLUMN_NAME_URL = "url"
        }
    }

    abstract class FavoritesEntry : BaseColumns {
        companion object {
            val TABLE_NAME = "favorites"

            /* Must match HistoryEntry because of CommonListFragment */
            val _ID = "_id"
            val COLUMN_NAME_DICT = "dict"
            val COLUMN_NAME_TITLE = "title"
            val COLUMN_NAME_SUMMARY = "summary"
            val COLUMN_NAME_URL = "url"
        }
    }
}
