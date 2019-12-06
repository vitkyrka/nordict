package se.whitchurch.nordict

import android.database.Cursor
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

    fun cursorToResults(cursor: Cursor): List<SearchResult> {
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

    fun sqlSearch(where: String, args: Array<String>?): List<SearchResult> {
        val cursor = db.rawQuery("select so.code, so.data from so WHERE article IN ($where) AND (code == 12 OR code == 77 OR code == 49 OR code == 40) ORDER BY rowid",
                args)

        return cursorToResults(cursor)
    }

    override fun search(query: String): List<SearchResult> {
        return sqlSearch("SELECT so.article FROM so, so_fts where grundform MATCH ? AND so.rowid = so_fts.rowid",
                arrayOf("^$query*"))
    }

    override val filters: Map<String, String> = mapOf(
            "Colloquial" to "vard",
            "Plural Acc1 w/o -orer" to "plural2",
            "Verbs" to "verb"
    )

    override fun getNext(wordList: WordList): Word? {
        val where = when (wordList.filter) {
            "plural2" -> "code == 49 AND data LIKE \"%boj_uttal%´%</boj_uttal>\" AND data NOT LIKE \"%<boj_uttal>[-o´rer]</boj_uttal>%\" AND DATA NOT LIKE \"%t]</boj_uttal>\" AND DATA NOT LIKE \"%n]</boj_uttal>\" AND DATA NOT LIKE \"%´% <boj%\" AND DATA LIKE \"%r <boj%\""
            "verb" -> "code == 12 and data == \"verb\""
            "vard" -> "data LIKE \"vard.%\""
            else -> {
                wordList.filter = "verb"
                "code == 12 and data == \"verb\""
            }
        }

        // return sqlSearch("SELECT DISTINCT so.article FROM so LIMIT 1 OFFSET ?", arrayOf(position.toString()))
        val list = sqlSearch("SELECT DISTINCT so.article FROM so WHERE $where LIMIT 2 OFFSET ?", arrayOf(wordList.position.toString()))

        wordList.next = if (list.size > 1) list[1] else null

        if (wordList.count < 0) {
            val cursor = db.rawQuery("select COUNT(DISTINCT so.article) FROM so WHERE $where", null)

            if (cursor.moveToNext()) {
                wordList.count = cursor.getInt(0)
            }

            cursor.close()
        }

        return if (list.isNotEmpty()) get(list[0].uri) else null
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