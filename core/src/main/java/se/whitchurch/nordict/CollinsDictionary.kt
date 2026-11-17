package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

abstract class CollinsDictionary(
    client: OkHttpClient,
    protected open val baseUrl: String = "https://www.collinsdictionary.com",
    pageFetcher: PageFetcher? = null
) : Dictionary(client, pageFetcher ?: OkHttpPageFetcher(client)) {
    abstract val dictCode: String

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != baseUrl.toHttpUrlOrNull()!!.host) {
            return null;
        }

        val ref = uri.queryParameter(REFPARAM)
        val buildUri = if (ref != null) {
            uri.withoutRefParam()
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

        // No __ref: the parser orders the easy-learning headword first.
        return words[0]
    }

    private fun fetchBody(url: String): String {
        val result = pageFetcher.fetch(url, mapOf("Accept" to "application/json"))
        if (!result.isSuccessful) {
            log.severe("Unexpected response: " + result.code)
            return ""
        }
        return result.body
    }

    /**
     * Collins' autocomplete moved to `POST /api/autocomplete` with a JSON
     * `{dictcode, term}` body; the old `GET /autocomplete/?q=&dictCode=`
     * endpoint is now a 404 page route on their Next.js site.
     */
    private fun autocompleteBody(base: HttpUrl, term: String): String {
        val url = base.resolve("/api/autocomplete")!!.toString()
        val body = """{"dictcode":"$dictCode","term":"$term"}"""
        val headers = mapOf("Accept" to "application/json", "Content-Type" to "application/json")

        var result = pageFetcher.fetchPost(url, headers, body)
        if (isChallenge(result.code, result.body)) {
            // A POST cannot be navigated to in the hidden WebView (the only
            // thing that solves Cloudflare's challenge), so warm the clearance
            // with a word-page GET, then retry the POST with the synced cookie.
            val slug = term.replace(" ", "-").lowercase()
            val wordUrl = base.newBuilder()!!
                .addPathSegment("dictionary")
                .addPathSegment(dictCode)
                .addPathSegment(slug)
                .build()
            fetchBody(wordUrl.toString())
            result = pageFetcher.fetchPost(url, headers, body)
        }
        if (!result.isSuccessful) {
            log.severe("Unexpected autocomplete response: " + result.code)
            return ""
        }
        return result.body
    }

    override fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val base = baseUrl.toHttpUrlOrNull()!!

        val body = autocompleteBody(base, trimmed)
        if (body.isEmpty()) return emptyList()

        return CollinsParser.parseSearch(body) { title ->
            // Multi-word headwords arrive as "efectivo en caja". Collins
            // canonical slugs are lower-cased with spaces as hyphens
            // ("efectivo-en-caja"); a raw space (or %20) URL 301-redirects
            // to a nonexistent "efectivoencaja" page, which renders a
            // spellcheck page with no parseable entry.
            val slug = title.replace(" ", "-").lowercase()
            base.newBuilder()!!
                .addPathSegment("dictionary")
                .addPathSegment(dictCode)
                .addPathSegment(slug)
                .build()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val REFPARAM = "__ref"
    }
}