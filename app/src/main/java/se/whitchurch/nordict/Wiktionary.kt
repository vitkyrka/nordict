package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

abstract class Wiktionary(client: OkHttpClient) : Dictionary(client) {
    abstract val shortName: String
    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        if (uri.host != "${shortName}.m.wiktionary.org") {
            return null;
        }

        val page = fetch(uri.toString())

        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach {
            if (it != REFPARAM)
                builder.appendQueryParameter(it, uri.getQueryParameter(it))
        }
        val newUri = builder.build()

        val words = WiktionaryParser.parse(page, newUri, tag, shortName)
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

        val result = JSONObject(response.body?.string())
        return result.getJSONArray("pages")
    }

    override fun search(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val uriBuilder =
            Uri.parse("https://${shortName}.wiktionary.org/w/rest.php/v1/search/title").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("limit", 10.toString())

        try {
            val pages = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val title = page.getString("title")
                val id = page.getInt("id")

                results.add(
                    SearchResult(
                        title,
                        Uri.parse("https://${shortName}.m.wiktionary.org/?curid=${id}")
                    )
                )
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "Wiktionary"
        const val REFPARAM = "__ref"
    }
}