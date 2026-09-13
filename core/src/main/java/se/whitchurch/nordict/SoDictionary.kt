package se.whitchurch.nordict

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class SoDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "SO"
    override val flagCode: String = "se"
    override val lang: String = "se"

    private fun searchApiRequest(requestUrl: String): JsonArray {
        val request = Request.Builder().url(requestUrl)
            // Server uses Referer to determine whether to link to so/ or tre/
            .addHeader("Referer", "https://svenska.se/so/")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            log.severe("Unexpected response: " + response.code)
            return JsonArray()
        }

        val body = response.body?.string() ?: return JsonArray()

        return try {
            JsonParser.parseString(body).asJsonArray
        } catch (e: Exception) {
            JsonArray()
        }
    }

    override fun search(query: String): List<SearchResult> {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("svenska.se")
            .addPathSegments("wp-admin/admin-ajax.php")
            .addQueryParameter("action", "tri_autocomplete")
            .addQueryParameter("term", query)
            .build()

        val results = ArrayList<SearchResult>()

        val words = searchApiRequest(url.toString())
        for (el in words) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val link = obj.get("link")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            results.add(SearchResult(label, "https://svenska.se/$link".toHttpUrlOrNull() ?: continue))
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != "svenska.se") {
            return null
        }

        val page = fetch(uri.toString())

        if (!page.contains("class=\"artikel so\"")) {
            return null
        }

        val words = SoParser.parse(page, tag)
        if (words.isEmpty()) {
            val urls = SoParser.parseDisambiguation(page)
            if (urls.isEmpty())
                return null

            // Just take the first one for now.  In some cases this list contains
            // links to the same headword (but with different ids in the URL), for
            // example when searching for "för".  In other cases it contains list
            // to different headwords, e.g. "illasinnad".  If we grab all the
            // headwords then we would get duplicate entries for the first case.
            // We also don't have support for different headwords from this function.
            return get(urls[0])
        }

        val ref = uri.queryParameter("ref") ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            log.fine("no candidates for xref")
            return words[0]
        }

        return candidates[0]
    }
}