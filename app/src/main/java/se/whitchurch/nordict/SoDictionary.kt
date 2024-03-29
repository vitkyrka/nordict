package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

class SoDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "SO"
    override val flag: Int = R.drawable.flag_se
    override val lang: String = "se"
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

        return JSONArray(body)
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder = Uri.parse("https://svenska.se/wp-admin/admin-ajax.php").buildUpon()

        uriBuilder.appendQueryParameter("action", "tri_autocomplete")
        uriBuilder.appendQueryParameter("term", query)

        val results = ArrayList<SearchResult>()

        try {
            val words = searchApiRequest(uriBuilder.build().toString())

            for (i in 0 until words.length()) {
                val word = words.getJSONObject(i)
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
            val urls = SoParser.parseDisambiguation(page)
            if (urls.isEmpty())
                return null

            // Just take the first one for now.  In some cases this list contains
            // links to the same headword (but with different ids in the URL), for
            // example when searching for "för".  In other cases it contains list
            // to different headwords, e.g. "illasinnad".  If we grab all the
            // headwords then we would get duplicate entries for the first case.
            // We also don't have support for different headwords from this function.
            return get(urls[0])
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