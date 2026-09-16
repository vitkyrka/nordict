package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Base class for the three diccionari.cat releases that share one page shape
 * beyond DIDAC — the monolingual GDLC and the bilingual catala-castella
 * (CA-ES) and catala-angles (CA-EN) — served by the shared [DiccionariParser].
 * Each subclass only supplies the Drupal node class, the autocomplete block
 * key, the `/cerca/…` search-view path, and the entry URL path prefix
 * ([entryPath], e.g. "catala-castella", used to resolve autocomplete
 * completion words into entry URLs).
 */
abstract class DiccionariDictionary(
    client: OkHttpClient,
    override val tag: String,
    private val nodeClass: String,
    private val bilingual: Boolean,
    private val autocompleteKey: String,
    private val cercaPath: String,
    private val entryPath: String,
    private val baseUrl: String = "https://www.diccionari.cat"
) : Dictionary(client) {
    override val flagCode: String = "ca"
    override val lang: String = "ca"

    private fun fetchJson(requestUrl: String): String {
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
        val url = baseUrl.toHttpUrlOrNull()!!
            .newBuilder()!!
            .addPathSegments("search_api_autocomplete/$autocompleteKey")
            .addQueryParameter("display", "page_1")
            .addQueryParameter("filter", "search_api_fulltext_cust")
            .addQueryParameter("q", query)
            .build()

        val body = fetchJson(url.toString())
        if (body.isEmpty()) return emptyList()

        // The autocomplete payload carries entry paths ("/catala-castella/cap1")
        // and bare completion words ("rebutjar" from "rebutja"); both resolve
        // against the base site.
        return DiccionariParser.parseSearch(body, entryPath) { path ->
            "$baseUrl$path".toHttpUrlOrNull()!!
        }
    }

    override fun fullSearch(query: String): List<SearchResult> {
        // Like DIDAC, the search view embeds every matching entry inline, so a
        // full search is a full word-page fetch.
        val pageUri = baseUrl.toHttpUrlOrNull()!!
            .newBuilder()!!
            .addPathSegments("cerca/$cercaPath")
            .addQueryParameter("search_api_fulltext_cust", query)
            .addQueryParameter("show", "title")
            .build()

        val page = fetch(pageUri.toString())
        if (page.isEmpty()) return emptyList()

        return DiccionariParser.parse(page, pageUri, tag, nodeClass, bilingual).map { word ->
            val summary = word.definitions.firstOrNull()?.glosses?.firstOrNull()?.definition ?: ""
            SearchResult(word.mTitle, summary, word.uri)
        }
    }

    override fun get(uri: HttpUrl): Word? {
        val base = baseUrl.toHttpUrlOrNull()!!
        if (uri.host != base.host) {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())

        val words = DiccionariParser.parse(page, newUri, tag, nodeClass, bilingual)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM)
        if (ref != null) {
            val candidates = words.filter { ref in it.xrefs }
            if (candidates.isEmpty()) {
                return words[0]
            }
            return candidates[0]
        }

        // Search can point straight at a locution word ("Cap Verd", served
        // from the same page as its plain homographs "cap") or at a numbered
        // homograph ("cap1"). Resolve the requested headword from the URL
        // instead of always returning the first word.
        val wanted = uri.pathSegments.lastOrNull()
        if (wanted != null) {
            val wantedNorm = normalizeSlug(wanted)
            words.firstOrNull {
                it.mSlug == wanted || it.mTitle == wanted || normalizeSlug(it.mTitle) == wantedNorm
            }?.let { return it }
        }

        return words[0]
    }

    // URL slugs drop punctuation and diacritics ("Canaveral, cap" becomes
    // "canaveral-cap", "cap-xebró" becomes "cap-xebro"), so comparisons go
    // through the same normalization on both sides.
    private fun normalizeSlug(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("""[\p{Mn}]"""), "").lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
    }

    companion object {
        internal const val REFPARAM = "__ref"
    }
}