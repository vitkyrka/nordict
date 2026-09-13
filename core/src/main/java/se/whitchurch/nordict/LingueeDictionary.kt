package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.OkHttpClient

class LingueeDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "LINGPT"
    override val flagCode: String = "pt"
    override val lang: String = "pt"

    override fun search(query: String): List<SearchResult> {
        val uri = HttpUrl.Builder()
            .scheme("https")
            .host("www.linguee.pt")
            .addPathSegments("portugues-ingles/search")
            .addQueryParameter("qe", query)
            .addQueryParameter("source", "auto")
            .addQueryParameter("cw", "703")
            .addQueryParameter("ch", "1332")
            .build()

        return LingueeParser.parseSearch(fetch(uri.toString()), uri)
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != "www.linguee.pt") {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())

        val words = LingueeParser.parse(page, newUri, tag)
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