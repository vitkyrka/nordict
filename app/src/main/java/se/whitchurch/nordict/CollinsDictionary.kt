package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

abstract class CollinsDictionary(
    client: OkHttpClient,
    protected open val baseUrl: String = "https://www.collinsdictionary.com"
) : Dictionary(client) {
    abstract val dictCode: String

    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        if (uri.host != Uri.parse(baseUrl).host) {
            return null;
        }

        val ref = uri.getQueryParameter(REFPARAM)
        val buildUri = if (ref != null) {
            val builder = uri.buildUpon()
            builder.clearQuery()
            uri.queryParameterNames.forEach {
                if (it != REFPARAM)
                    builder.appendQueryParameter(it, uri.getQueryParameter(it))
            }
            builder.build()
        } else {
            uri
        }

        val page = fetch(buildUri.toString())

        val words = CollinsParser.parse(page, buildUri, tag, dictCode, baseUrl)
        if (words.isEmpty()) return null

        if (ref != null) {
            val candidates = words.filter { it.xrefs.contains(ref) }
            if (candidates.isNotEmpty()) return candidates[0]
        }

        // No __ref: the parser orders the main dictionary headword first.
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
            Uri.parse("$baseUrl/autocomplete/").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("dictCode", dictCode)

        try {
            val items = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.getString("title")

                val uri =
                    Uri.parse("$baseUrl/dictionary/${dictCode}/${title}")
                results.add(SearchResult(title, uri))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "Collins"
        const val REFPARAM = "__ref"
    }
}