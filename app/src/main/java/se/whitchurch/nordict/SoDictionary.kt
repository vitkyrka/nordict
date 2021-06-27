package se.whitchurch.nordict

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.text.Html
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

class SoDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "SO"
    override val flag: Int = R.drawable.flag_se
    override fun init() = Unit

    private fun searchApiRequest(requestUrl: String): JSONArray {
        val request = Request.Builder().url(requestUrl)
                // Server uses Referer to determine whether to link to so/ or tre/
                .addHeader("Referer", "https://svenska.se/so/")
                .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e("SO", "Unexpected response: " + response.code)
            return JSONArray()
        }

        val body = response.body?.string() ?: return JSONArray()

        return JSONArray(body.substring(1, body.length - 1))
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder = Uri.parse("https://svenska.se/wp-admin/admin-ajax.php").buildUpon()

        uriBuilder.appendQueryParameter("action", "tri_autocomplete")
        uriBuilder.appendQueryParameter("term", query)

        val results = ArrayList<SearchResult>()

        try {
            val words = searchApiRequest(uriBuilder.build().toString())

            for (i in 0 until words.length()) {
                val word = words.getJSONObject(i);
                val label = word.getString("label")
                val link = word.getString("link")
                results.add(SearchResult(label, Uri.parse("https://svenska.se/$link")))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

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