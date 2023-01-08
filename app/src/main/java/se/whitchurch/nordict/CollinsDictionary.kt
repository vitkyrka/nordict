package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

abstract class CollinsDictionary(client: OkHttpClient) : Dictionary(client) {
    abstract val dictCode: String

    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        if (uri.host != "www.collinsdictionary.com") {
            return null;
        }

        val page = fetch(uri.toString())

        val words = CollinsParser.parse(page, uri, tag, dictCode)
        if (words.isEmpty()) return null

        return words[0]
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
            Uri.parse("https://www.collinsdictionary.com/autocomplete/").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("dictCode", dictCode)

        try {
            val items = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.getString("title")

                val uri =
                    Uri.parse("https://www.collinsdictionary.com/dictionary/${dictCode}/${title}")
                results.add(SearchResult(title, uri))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "Collins"
    }
}