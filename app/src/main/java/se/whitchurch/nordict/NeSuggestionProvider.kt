package se.whitchurch.nordict

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns

class NeSuggestionProvider : ContentProvider() {

    override fun delete(arg0: Uri, arg1: String?, arg2: Array<String>?): Int {
        return 0
    }

    override fun getType(arg0: Uri): String? {
        return null
    }

    override fun insert(arg0: Uri, arg1: ContentValues?): Uri? {
        return null
    }

    override fun onCreate(): Boolean {
        return false
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val columns = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA
        )
        val cursor = MatrixCursor(columns)
        val q = uri.lastPathSegment

        if (q == SearchManager.SUGGEST_URI_PATH_QUERY) {
            val lastWord = Ordboken.getInstance(context!!).currentWord

            if (lastWord != null) {
                cursor.addRow(arrayOf(0, lastWord.mTitle, "", null, lastWord.uri, lastWord.mTitle))
            }
            return cursor
        }

        val results = Ordboken.getInstance(context!!).search(q!!, 100)
        val flag = Ordboken.getInstance(context!!).currentFlag
        var j = 0

        for (result in results) {
            cursor.addRow(
                arrayOf(
                    j,
                    result.mTitle,
                    result.mSummary,
                    flag,
                    result.uri,
                    result.mTitle
                )
            )
            j += 1
        }

        return cursor
    }

    override fun update(arg0: Uri, arg1: ContentValues?, arg2: String?, arg3: Array<String>?): Int {
        return 0
    }
}
