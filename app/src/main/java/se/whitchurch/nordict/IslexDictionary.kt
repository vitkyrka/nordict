package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class IslexDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "ISL"
    override val flag: Int = R.drawable.flag_is
    override val lang: String = "is"
    override fun init() = Unit

    private fun searchApiRequest(requestUrl: String): JSONArray {
        val request = Request.Builder().url(requestUrl)
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e("ISLEX", "Unexpected response: " + response.code)
            return JSONArray()
        }

        val body = response.body?.string() ?: return JSONArray()

        val obj = JSONObject(body);
        return obj.getJSONArray("results");
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder =
            Uri.parse("https://islex.arnastofnun.is/django/api/es/flettur/").buildUpon()

        uriBuilder.appendQueryParameter("fletta", "$query*")
        uriBuilder.appendQueryParameter("simple", "true")

        val results = ArrayList<SearchResult>()

        try {
            val words = searchApiRequest(uriBuilder.build().toString())

            for (i in 0 until words.length()) {
                val word = words.getJSONObject(i)
                var flid = word.getInt("flid")
                var fletta = word.getString("fletta")
                val ofl = word.getString("ofl")
                results.add(
                    SearchResult(
                        "$fletta $ofl",
                        Uri.parse("https://islex.arnastofnun.is/django/api/es/fletta/$flid/")
                    )
                )
            }
        } catch (e: JSONException) {
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: Uri): Word? {
        val page = fetch(uri.toString())

        val words = IslexParser.parse(page, uri, tag)
        if (words.isEmpty()) return null

        return words[0]
    }
}