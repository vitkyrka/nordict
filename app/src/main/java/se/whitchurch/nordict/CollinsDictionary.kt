package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

abstract class CollinsDictionary(
    client: OkHttpClient,
    protected open val baseUrl: String = "https://www.collinsdictionary.com"
) : Dictionary(client) {
    abstract val dictCode: String

    override fun init() = Unit

    override fun get(uri: Uri): Word? {
        if (uri.host != Uri.parse(baseUrl).host) {
            return null;
        }

        val ref = uri.getQueryParameter(REFPARAM)
        val buildUri = if (ref != null) {
            val builder = uri.buildUpon()
            builder.clearQuery()
            uri.queryParameterNames.forEach {
                if (it != REFPARAM)
                    builder.appendQueryParameter(it, uri.getQueryParameter(it))
            }
            builder.build()
        } else {
            uri
        }

        val page = fetch(buildUri.toString())

        val words = CollinsParser.parse(page, buildUri.toHttpUrl(), tag, dictCode, baseUrl)
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
            Log.e(NAME, "Unexpected response: " + response.code)
            return ""
        }

        return response.body?.string() ?: ""
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder = Uri.parse("$baseUrl/autocomplete/").buildUpon()
        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("dictCode", dictCode)

        val body = fetchBody(uriBuilder.build().toString())
        if (body.isEmpty()) return emptyList()

        return CollinsParser.parseSearch(body) { title ->
            // Multi-word headwords arrive as "efectivo en caja". Collins
            // canonical slugs are lower-cased with spaces as hyphens
            // ("efectivo-en-caja"); a raw space (or %20) URL 301-redirects
            // to a nonexistent "efectivoencaja" page, which renders a
            // spellcheck page with no parseable entry.
            val slug = title.replace(" ", "-").lowercase()
            Uri.parse("$baseUrl/dictionary/${dictCode}/${slug}").toHttpUrl()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "Collins"
        const val REFPARAM = "__ref"
    }
}