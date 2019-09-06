package se.whitchurch.nordict

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.text.Html
import android.util.Log
import okhttp3.OkHttpClient

class SoDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "SO"
    override val flag: Int = R.drawable.flag_se
    private lateinit var db: SQLiteDatabase

    override fun init() {
        db = SQLiteDatabase.openDatabase("/sdcard/Android/obb/se.svenskaakademien.so16/main.16.se.svenskaakademien.so16.obb",
                null, SQLiteDatabase.OPEN_READONLY)
    }

    override fun search(query: String): List<SearchResult> {
        val args = arrayOf("^$query*")
        val cursor = db.rawQuery("select so.code, so.data from so WHERE article IN (SELECT so.article FROM so, so_fts where grundform MATCH ? AND so.rowid = so_fts.rowid) AND (code == 12 OR code == 77 OR code == 49 OR code == 40) ORDER BY rowid",
                args)
        val results = ArrayList<SearchResult>()

        var headword = ""
        var pos = ""
        var xref = ""

        while (cursor.moveToNext()) {
            val code = cursor.getInt(0)
            val data = Html.fromHtml(cursor.getString(1), Html.FROM_HTML_MODE_LEGACY).toString()

            when (code) {
                77 -> headword = data
                12 -> pos = data
                40 -> xref = data
            }

            if (code != 49) {
                continue
            }

            val declension = data
            val word = headword.replace("`", "")
                    .replace("´", "")
                    .replace("|", "")

            val summary = StringBuilder(headword).append(' ').append(pos).append(' ').append(declension)

            results.add(SearchResult(word, summary.toString(),
                    Uri.parse("https://svenska.se/so/?sok=${Uri.encode(word)}&ref=$xref")))
        }

        return results
    }

    override fun get(uri: Uri): Word? {
        if (uri.host != "svenska.se") {
            return null
        }

        val page = fetch(uri.toString())

        if (!page.contains("class=\"artikel so\"")) {
            return null
        }

        val words = SoParser.parse(page, tag)
        if (words.isEmpty()) {
            return null
        }

        val ref = uri.getQueryParameter("ref") ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            Log.i("nordict", "no candidates for xref")
            return words[0]
        }

        return candidates[0]
    }
}