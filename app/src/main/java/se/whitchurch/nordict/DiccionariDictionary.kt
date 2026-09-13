package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
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
    override val flag: Int = R.drawable.flag_ca
    override val lang: String = "ca"
    override fun init() = Unit

    private fun fetchJson(requestUrl: String): String {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(tag, "Unexpected response: " + response.code)
            return ""
        }

        return response.body?.string() ?: ""
    }

    override fun search(query: String): List<SearchResult> {
        val uriBuilder = Uri.parse("$baseUrl/search_api_autocomplete/$autocompleteKey")
            .buildUpon()
            .appendQueryParameter("display", "page_1")
            .appendQueryParameter("filter", "search_api_fulltext_cust")
            .appendQueryParameter("q", query)

        val body = fetchJson(uriBuilder.build().toString())
        if (body.isEmpty()) return emptyList()

        // The autocomplete payload carries entry paths ("/catala-castella/cap1")
        // and bare completion words ("rebutjar" from "rebutja"); both resolve
        // against the base site.
        return DiccionariParser.parseSearch(body, entryPath) { path ->
            Uri.parse("$baseUrl$path").toHttpUrl()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> {
        // Like DIDAC, the search view embeds every matching entry inline, so a
        // full search is a full word-page fetch.
        val uriBuilder = Uri.parse("$baseUrl/cerca/$cercaPath")
            .buildUpon()
            .appendQueryParameter("search_api_fulltext_cust", query)
            .appendQueryParameter("show", "title")

        val pageUri = uriBuilder.build()
        val page = fetch(pageUri.toString())
        if (page.isEmpty()) return emptyList()

        return DiccionariParser.parse(page, pageUri.toHttpUrl(), tag, nodeClass, bilingual).map { word ->
            val summary = word.definitions.firstOrNull()?.glosses?.firstOrNull()?.definition ?: ""
            SearchResult(word.mTitle, summary, word.uri)
        }
    }

    override fun get(uri: Uri): Word? {
        if (uri.host != Uri.parse(baseUrl).host) {
            return null
        }

        val builder = uri.buildUpon()
        builder.clearQuery()
        uri.queryParameterNames.forEach {
            if (it != REFPARAM)
                builder.appendQueryParameter(it, uri.getQueryParameter(it))
        }
        val newUri = builder.build()
        val page = fetch(newUri.toString())

        val words = DiccionariParser.parse(page, newUri.toHttpUrl(), tag, nodeClass, bilingual)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM)
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
        val wanted = uri.lastPathSegment
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