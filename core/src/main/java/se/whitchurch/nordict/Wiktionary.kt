package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

abstract class Wiktionary(client: OkHttpClient) : Dictionary(client) {
    abstract val shortName: String

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != "${shortName}.m.wiktionary.org") {
            return null;
        }

        val page = fetch(uri.toString())

        val newUri = uri.withoutRefParam()

        val words = WiktionaryParser.parse(page, newUri, tag, shortName)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    private fun publicApiRequest(requestUrl: String): String {
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
        val results = ArrayList<SearchResult>()
        val uri = HttpUrl.Builder()
            .scheme("https")
            .host("${shortName}.wiktionary.org")
            .addPathSegments("w/rest.php/v1/search/title")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()

        val pages = try {
            JsonParser.parseString(publicApiRequest(uri.toString())).asJsonObject.getAsJsonArray("pages")
        } catch (e: Exception) {
            return results
        }

        for (el in pages) {
            if (!el.isJsonObject) continue
            val page = el.asJsonObject
            val title = page.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val id = page.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: continue

            results.add(
                SearchResult(
                    title,
                    "https://${shortName}.m.wiktionary.org/?curid=$id".toHttpUrlOrNull() ?: continue
                )
            )
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val REFPARAM = "__ref"
    }
}