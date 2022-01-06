package se.whitchurch.nordict

import android.net.Uri
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
        val page = fetch(uri.toString())

        val words = LeRobertParser.parse(page, uri, tag)
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
            Uri.parse("https://dictionnaire.lerobert.com/autocomplete.json").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("t", "def")

        try {
            val items = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.getString("display")
                val page = item.getString("page")

                results.add(
                    SearchResult(
                        title,
                        Uri.parse("https://dictionnaire.lerobert.com${page}")
                    )
                )
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "LeRobert"
    }
}