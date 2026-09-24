package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class DidacDictionary(client: OkHttpClient, private val baseUrl: String = "https://www.diccionari.cat") : Dictionary(client) {
    override val tag: String = "DIDAC"
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
            .addPathSegments("search_api_autocomplete/didac")
            .addQueryParameter("display", "page_1")
            .addQueryParameter("filter", "search_api_fulltext_cust")
            .addQueryParameter("q", query)
            .build()

        val body = fetchJson(url.toString())
        if (body.isEmpty()) return emptyList()

        // The autocomplete payload carries entry paths ("/didac/cap1") and
        // bare completion words ("rebutjar" from "rebutja"); both resolve
        // against the base site.
        return DidacParser.parseSearch(body) { path ->
            "$baseUrl$path".toHttpUrlOrNull()!!
        }
    }

    override fun fullSearch(query: String): List<SearchResult> {
        // DIDAC has no isolated word URL: the search view embeds every
        // matching entry inline, so a full search is a full word-page fetch.
        val pageUri = baseUrl.toHttpUrlOrNull()!!
            .newBuilder()!!
            .addPathSegments("cerca/didac")
            .addQueryParameter("search_api_fulltext_cust", query)
            .addQueryParameter("show", "title")
            .build()

        val page = fetch(pageUri.toString())
        if (page.isEmpty()) return emptyList()

        return DidacParser.parse(page, pageUri, tag).map { word ->
            val summary = word.definitions.firstOrNull()?.glosses?.firstOrNull()?.definition
                ?: ""
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

        val words = DidacParser.parse(page, newUri, tag)
        if (words.isEmpty()) return fallbackGet(newUri, uri.queryParameter(REFPARAM))

        val ref = uri.queryParameter(REFPARAM)
        if (ref != null) {
            val candidates = words.filter { ref in it.xrefs }
            if (candidates.isEmpty()) {
                return words[0]
            }
            return candidates[0]
        }

        // Search can point straight at a locution word ("al cap de", served
        // from the same page as its plain homographs "cap") or at a numbered
        // homograph ("cap1"). Resolve the requested headword from the URL
        // instead of always returning the first word.
        val wanted = uri.pathSegments.lastOrNull()
        if (wanted != null) {
            val wantedSlug = wanted.replace(" ", "-").lowercase()
            words.firstOrNull {
                it.mSlug == wanted || it.mSlug == wantedSlug ||
                    it.mTitle == wanted || slugify(it.mTitle) == wantedSlug
            }?.let { return it }
        }

        return words[0]
    }

    // A bare autocomplete completion (e.g. "repenjar" from "repenja") resolves
    // to /didac/<slug>, but the real headword may not exist under that slug
    // (DIDAC lists "repenjar-se", so /didac/repenjar is a 404 and parses to
    // nothing). Fall back to the cerca search view for the slug and return
    // its first entry instead of failing the whole lookup.
    private fun fallbackGet(newUri: HttpUrl, ref: String?): Word? {
        if ("cerca" in newUri.pathSegments) return null
        val slug = newUri.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
        if (slug == "didac") return null

        val pageUri = baseUrl.toHttpUrlOrNull()!!
            .newBuilder()!!
            .addPathSegments("cerca/didac")
            .addQueryParameter("search_api_fulltext_cust", slug)
            .addQueryParameter("show", "title")
            .build()
        val page = fetch(pageUri.toString())
        if (page.isEmpty()) return null
        val words = DidacParser.parse(page, pageUri, tag)
        if (words.isEmpty()) return null

        if (ref != null) {
            val candidates = words.filter { ref in it.xrefs }
            if (candidates.isEmpty()) {
                return words[0]
            }
            return candidates[0]
        }
        val wantedSlug = slug.replace(" ", "-").lowercase()
        words.firstOrNull {
            it.mSlug == slug || it.mSlug == wantedSlug ||
                it.mTitle == slug || slugify(it.mTitle) == wantedSlug
        }?.let { return it }
        return words[0]
    }

    private fun slugify(title: String): String = title.trim().replace(" ", "-").lowercase()

    companion object {
        const val REFPARAM = "__ref"
    }
}