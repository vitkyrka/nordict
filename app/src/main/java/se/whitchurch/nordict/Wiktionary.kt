package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.regex.Pattern

abstract class Wiktionary(client: OkHttpClient) : Dictionary(client) {
    abstract val shortName: String;
    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        val page = fetch(uri.toString())

        return WiktionaryParser.parse(page, uri, tag, shortName)
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

        val result = JSONObject(response.body?.string())
        return result.getJSONArray("pages")
    }

    override fun search(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val uriBuilder = Uri.parse("https://${shortName}.wiktionary.org/w/rest.php/v1/search/title").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("limit", 10.toString())

        try {
            val pages = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val title = page.getString("title")
                val id = page.getInt("id")

                results.add(SearchResult(title,
                        Uri.parse("https://${shortName}.m.wiktionary.org/?curid=${id}")))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "Wiktionary"
    }
}