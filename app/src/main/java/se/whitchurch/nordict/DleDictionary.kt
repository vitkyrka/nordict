package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException

class DleDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "DLE"
    override val flag: Int = R.drawable.flag_es
    override val lang: String = "es"
    override fun init() = Unit

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
            Uri.parse("https://dle.rae.es/srv/keys").buildUpon()

        uriBuilder.appendQueryParameter("q", query)

        try {
            val items = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until items.length()) {
                val item = items.getString(i)

                val uri = Uri.parse("https://dle.rae.es/${item}")
                results.add(SearchResult(item, uri))
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: Uri): Word? {
        if (uri.host != "dle.rae.es") {
            return null
        }

        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach {
            if (it != REFPARAM)
                builder.appendQueryParameter(it, uri.getQueryParameter(it))
        }
        val newUri = builder.build()
        val page = fetch(newUri.toString())

        val words = DleParser.parse(page, newUri, tag)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    companion object {
        const val NAME = "DLE"
        const val REFPARAM = "__ref"
    }
}