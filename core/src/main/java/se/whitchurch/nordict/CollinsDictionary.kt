package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

abstract class CollinsDictionary(
    client: OkHttpClient,
    protected open val baseUrl: String = "https://www.collinsdictionary.com"
) : Dictionary(client) {
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

        // No __ref: the parser orders the main dictionary headword first.
        return words[0]
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
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val base = baseUrl.toHttpUrlOrNull()!!
        val url = base.resolve("/autocomplete/")!!.newBuilder()!!
            .addQueryParameter("q", trimmed)
            .addQueryParameter("dictCode", dictCode)
            .build()

        val body = fetchBody(url.toString())
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