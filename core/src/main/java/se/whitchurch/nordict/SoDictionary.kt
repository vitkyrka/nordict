package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class SoDictionary(
    client: OkHttpClient,
    baseUrl: String = "https://svenska.se"
) : Dictionary(client) {
    private val base: HttpUrl? = baseUrl.toHttpUrlOrNull()

    override val tag: String = "SO"
    override val flagCode: String = "se"
    override val lang: String = "se"

    override fun search(query: String): List<SearchResult> {
        val b = base ?: return emptyList()
        val url = b.newBuilder()
            .addPathSegments("api/autocomplete")
            .addQueryParameter("q", query)
            .addQueryParameter("size", "10")
            .build()
        val body = fetch(url.toString())
        if (body.isEmpty()) return emptyList()
        return SoParser.parseSearch(body) { id -> articleUrl(b, id) }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (base == null || uri.host != base.host) {
            return null
        }
        val newUri = uri.withoutRefParam()

        // The canonical word URL built by search is /api/article/so/<l_nr>; the
        // article id may also arrive as a query parameter (?id=<l_nr>) from the
        // old-style ?activeTab=so&q=<word> links.
        val id = articleId(newUri) ?: return null
        val url = articleUrl(base, id)
        val page = fetch(url.toString())
        if (page.isEmpty()) return null

        return SoParser.parse(page, url, tag, base.toString()).firstOrNull()
    }

    private fun articleId(uri: HttpUrl): String? {
        uri.queryParameter("id")?.let { if (it.isNotEmpty()) return it }
        val segments = uri.pathSegments
        if (segments.size == 4 && segments[0] == "api" && segments[1] == "article" && segments[2] == "so") {
            return segments[3].takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun articleUrl(base: HttpUrl, id: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("api/article/so")
            .addPathSegment(id)
            .build()
}