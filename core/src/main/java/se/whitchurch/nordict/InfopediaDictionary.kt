package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class InfopediaDictionary(
    client: OkHttpClient,
    private val baseUrl: String = "https://www.infopedia.pt",
    pageFetcher: PageFetcher? = null
) : Dictionary(client, pageFetcher ?: OkHttpPageFetcher(client)) {
    override val tag: String = "INFOPEDIA"
    override val flagCode: String = "pt"
    override val lang: String = "pt"

    override fun search(query: String): List<SearchResult> {
        val base = baseUrl.toHttpUrlOrNull()!!
        val uri = base.newBuilder()!!
            .addPathSegments("dicionarios/lingua-portuguesa/sugestao-pesquisa")
            .addPathSegment(query)
            .build()

        // The endpoint serves the `{"html": ...}` autocomplete JSON only to
        // XHR callers (the site's own jQuery `dataType: "json"` request); a
        // plain page load gets the full word-page HTML instead, which parses
        // to nothing here.
        val result = pageFetcher.fetch(uri.toString(), SEARCH_HEADERS)
        if (!result.isSuccessful) {
            log.severe("Unexpected response: " + result.code)
            return ArrayList()
        }
        val page = result.body
        if (page.isEmpty()) {
            return ArrayList()
        }

        val html = try {
            JsonParser.parseString(page).asJsonObject.get("html")?.asString
        } catch (e: Exception) {
            null
        } ?: return ArrayList()

        return InfopediaParser.parseSearch(html) { title ->
            base.newBuilder()!!
                .addPathSegments("dicionarios/lingua-portuguesa")
                .addPathSegment(title)
                .build()
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

        val words = InfopediaParser.parse(page, newUri, tag, finalBaseUrl)
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

        /**
         * Headers the site's own autocomplete request sends (jQuery
         * `dataType: "json"`): without them `sugestao-pesquisa` serves the
         * full word-page HTML instead of the `{"html": ...}` JSON.
         */
        val SEARCH_HEADERS = mapOf(
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }
}