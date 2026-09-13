package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

class DidacDictionary(client: OkHttpClient, private val baseUrl: String = "https://www.diccionari.cat") : Dictionary(client) {
    override val tag: String = "DIDAC"
    override val flag: Int = R.drawable.flag_ca
    override val lang: String = "ca"
    override fun init() = Unit

    private fun fetchJson(requestUrl: String): String {
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
        val uriBuilder = Uri.parse("$baseUrl/search_api_autocomplete/didac")
            .buildUpon()
            .appendQueryParameter("display", "page_1")
            .appendQueryParameter("filter", "search_api_fulltext_cust")
            .appendQueryParameter("q", query)

        val body = fetchJson(uriBuilder.build().toString())
        if (body.isEmpty()) return emptyList()

        // The autocomplete payload carries entry paths ("/didac/cap1") and
        // bare completion words ("rebutjar" from "rebutja"); both resolve
        // against the base site.
        return DidacParser.parseSearch(body) { path ->
            Uri.parse("$baseUrl$path").toHttpUrl()
        }
    }

    override fun fullSearch(query: String): List<SearchResult> {
        // DIDAC has no isolated word URL: the search view embeds every
        // matching entry inline, so a full search is a full word-page fetch.
        val uriBuilder = Uri.parse("$baseUrl/cerca/didac")
            .buildUpon()
            .appendQueryParameter("search_api_fulltext_cust", query)
            .appendQueryParameter("show", "title")

        val pageUri = uriBuilder.build()
        val page = fetch(pageUri.toString())
        if (page.isEmpty()) return emptyList()

        return DidacParser.parse(page, pageUri.toHttpUrl(), tag).map { word ->
            val summary = word.definitions.firstOrNull()?.glosses?.firstOrNull()?.definition
                ?: ""
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

        val words = DidacParser.parse(page, newUri.toHttpUrl(), tag)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM)
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
        val wanted = uri.lastPathSegment
        if (wanted != null) {
            val wantedSlug = wanted.replace(" ", "-").lowercase()
            words.firstOrNull {
                it.mSlug == wanted || it.mSlug == wantedSlug ||
                    it.mTitle == wanted || slugify(it.mTitle) == wantedSlug
            }?.let { return it }
        }

        return words[0]
    }

    private fun slugify(title: String): String = title.trim().replace(" ", "-").lowercase()

    companion object {
        const val NAME = "DIDAC"
        const val REFPARAM = "__ref"
    }
}