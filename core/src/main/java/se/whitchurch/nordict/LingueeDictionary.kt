package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class LingueeDictionary(
    client: OkHttpClient,
    private val baseUrl: String = "https://www.linguee.pt"
) : Dictionary(client) {
    override val tag: String = "LINGPT"
    override val flagCode: String = "pt"
    override val lang: String = "pt"

    override fun search(query: String): List<SearchResult> {
        val base = baseUrl.toHttpUrlOrNull()!!
        val uri = base.newBuilder()!!
            .addPathSegments("portugues-ingles/search")
            .addQueryParameter("qe", query)
            .addQueryParameter("source", "auto")
            .addQueryParameter("cw", "703")
            .addQueryParameter("ch", "1332")
            .build()

        val body = fetch(uri.toString())
        if (body.isEmpty()) return emptyList()

        return LingueeParser.parseSearch(body) { page ->
            base.resolve(page)!!
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != baseUrl.toHttpUrlOrNull()!!.host) {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())
        val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val words = LingueeParser.parse(page, newUri, tag, finalBaseUrl)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    companion object {
        const val REFPARAM = "__ref"
    }
}