package se.whitchurch.nordict

import android.net.Uri
import android.text.Html
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

class LeRobertDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "ROB"
    override val lang: String = "fr"
    override val flag: Int = R.drawable.flag_rob
    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach {
            if (it != REFPARAM)
                builder.appendQueryParameter(it, uri.getQueryParameter(it))
        }
        val newUri = builder.build()

        val page = fetch(newUri.toString())

        val words = LeRobertParser.parse(page, newUri, tag)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    private fun publicApiRequest(requestUrl: String): JSONArray {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(NAME, "Unexpected response: " + response.code)
            return JSONArray()
        }

        return JSONArray(response.body?.string())
    }

    override fun search(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val uriBuilder =
            Uri.parse("https://dictionnaire.lerobert.com/autocomplete.json").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("t", "def")

        try {
            val items = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val display = item.getString("display")
                var page = item.getString("page")
                val title = StringBuilder(
                    Html.fromHtml(display, Html.FROM_HTML_MODE_LEGACY).toString()
                ).toString()

                if (page.startsWith("/conjugaison/")) {
                    page = page.replace("/conjugaison/", "/definition/")
                }

                val uri = Uri.parse("https://dictionnaire.lerobert.com${page}")
                results.add(SearchResult(title, uri))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "LeRobert"
        const val REFPARAM = "__ref"
    }
}