package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

class EstDictionary(client: OkHttpClient, private val baseUrl: String = "https://www.rae.es/diccionario-estudiante") : Dictionary(client) {
    override val tag: String = "EST"
    override val flag: Int = R.drawable.flag_es
    override val lang: String = "es"
    override fun init() = Unit

    private fun fetchBody(requestUrl: String): String {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(NAME, "Unexpected response: " + response.code)
            return ""
        }

        return response.body?.string() ?: ""
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder = Uri.parse("$baseUrl/srv/keys").buildUpon()
        uriBuilder.appendQueryParameter("q", query)

        val body = fetchBody(uriBuilder.build().toString())
        if (body.isEmpty()) return emptyList()

        return EstParser.parseSearch(body) { item ->
            Uri.parse(baseUrl).buildUpon().appendPath(item).build().toHttpUrl()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: Uri): Word? {
        if (uri.host != Uri.parse(baseUrl).host) {
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

        val words = EstParser.parse(page, newUri.toHttpUrl(), tag, baseUrl)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM)
        if (ref != null) {
            val candidates = words.filter { ref in it.xrefs }
            if (candidates.isEmpty()) {
                return words[0]
            }
            return candidates[0]
        }

        // Search can point straight at a .sols sub-entry ("muerte natural"),
        // which is served from its parent lemma's page. Resolve the requested
        // headword from the URL instead of always returning the first word.
        val wanted = uri.lastPathSegment
        if (wanted != null) {
            words.firstOrNull { it.mSlug == wanted || it.mTitle == wanted }?.let { return it }
        }

        return words[0]
    }

    companion object {
        const val NAME = "EST"
        const val REFPARAM = "__ref"
    }
}
