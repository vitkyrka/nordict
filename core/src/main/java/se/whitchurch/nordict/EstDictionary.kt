package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class EstDictionary(client: OkHttpClient, private val baseUrl: String = "https://www.rae.es/diccionario-estudiante") : Dictionary(client) {
    override val tag: String = "EST"
    override val flagCode: String = "es"
    override val lang: String = "es"

    private fun fetchBody(requestUrl: String): String {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            log.severe("Unexpected response: " + response.code)
            return ""
        }

        return response.body?.string() ?: ""
    }

    override fun search(query: String): List<SearchResult> {
        val base = baseUrl.toHttpUrlOrNull()!!
        val url = base.newBuilder()!!
            .addPathSegment("srv")
            .addPathSegment("keys")
            .addQueryParameter("q", query)
            .build()

        val body = fetchBody(url.toString())
        if (body.isEmpty()) return emptyList()

        return EstParser.parseSearch(body) { item ->
            base.newBuilder()!!.addPathSegment(item).build()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != baseUrl.toHttpUrlOrNull()!!.host) {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())

        val words = EstParser.parse(page, newUri, tag, baseUrl)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM)
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
        val wanted = uri.pathSegments.lastOrNull()
        if (wanted != null) {
            words.firstOrNull { it.mSlug == wanted || it.mTitle == wanted }?.let { return it }
        }

        return words[0]
    }

    companion object {
        const val REFPARAM = "__ref"
    }
}