package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class LeRobertDictionary(
    client: OkHttpClient,
    private val baseUrl: String = "https://dictionnaire.lerobert.com"
) : Dictionary(client) {
    override val tag: String = "ROB"
    override val flagCode: String = "fr"
    override val lang: String = "fr"

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != baseUrl.toHttpUrlOrNull()!!.host) {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())
        val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val words = LeRobertParser.parse(page, newUri, tag, finalBaseUrl)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

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
            .addPathSegment("autocomplete.json")
            .addQueryParameter("q", query)
            .addQueryParameter("t", "def")
            .build()

        val body = fetchBody(url.toString())
        if (body.isEmpty()) return emptyList()

        return LeRobertParser.parseSearch(body) { page ->
            base.resolve(page)!!
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val REFPARAM = "__ref"
    }
}
