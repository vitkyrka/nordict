package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class LeRobertDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "ROB"
    override val flagCode: String = "fr"
    override val lang: String = "fr"

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != "dictionnaire.lerobert.com") {
            return null;
        }

        val newUri = uri.withoutRefParam()

        val page = fetch(newUri.toString())

        val words = LeRobertParser.parse(page, newUri, tag)
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
            .host("dictionnaire.lerobert.com")
            .addPathSegment("autocomplete.json")
            .addQueryParameter("q", query)
            .addQueryParameter("t", "def")
            .build()

        val items = try {
            JsonParser.parseString(publicApiRequest(uri.toString())).asJsonArray
        } catch (e: Exception) {
            return results
        }

        for (el in items) {
            if (!el.isJsonObject) continue
            val item = el.asJsonObject
            val display = item.get("display")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            var page = item.get("page")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val title = Jsoup.parse(display).text()

            if (page.startsWith("/conjugaison/")) {
                page = page.replace("/conjugaison/", "/definition/")
            }

            val url = "https://dictionnaire.lerobert.com$page".toHttpUrlOrNull() ?: continue
            results.add(SearchResult(title, url))
        }

        return results
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val REFPARAM = "__ref"
    }
}