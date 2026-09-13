package se.whitchurch.nordict

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

abstract class DslDictionary(client: OkHttpClient) : Dictionary(client) {
    abstract val shortName: String

    private fun getEntryUri(id: String, paramName: String = "entry_id"): HttpUrl =
        "https://ordnet.dk/".toHttpUrlOrNull()!!
            .newBuilder()!!
            .addPathSegment(shortName)
            .addPathSegment("ordbog")
            .addQueryParameter(paramName, id)
            .addQueryParameter("query", ".")
            .build()

    private fun getApiWord(uri: HttpUrl): Word? {
        val page = fetch(uri.toString())

        val matcher = DslDictionary.MAIN_SITE_CONTENT_PATTERN.matcher(page)
        if (!matcher.find()) {
            return null
        }

        val id = matcher.group(1)
        val word = getMainSiteWord(getEntryUri(id))
            ?: return null

        val doc = Jsoup.parse(page, "https://ws.dsl.dk/${shortName}/")
        val wordLinks = doc.select(".short-result ul li a")
        for (wordLink in wordLinks) {
            val href = wordLink.attr("href")

            if (!href.startsWith("#")) {
                continue
            }

            val otherId = href.substring(1)
            word.addHomograph(SearchResult(wordLink.text(), getEntryUri(otherId)))
        }

        return word
    }

    override fun get(uri: HttpUrl): Word? {
        return when {
            uri.host == "ordnet.dk" -> getMainSiteWord(uri)
            uri.host == "ws.dsl.dk" -> getApiWord(uri)
            else -> null
        }
    }

    protected fun getMainSiteWord(uri: HttpUrl): Word? {
        var id = uri.queryParameter("entry_id")
        val newUri: HttpUrl

        // Replace query with . to avoid duplicates in history
        if (id != null) {
            newUri = getEntryUri(id)
        } else {
            id = uri.queryParameter("subentry_id") ?: return null
            newUri = getEntryUri(id, "subentry_id")
        }

        val page = fetch(newUri.toString())

        return DdoParser.parse(page, newUri, tag)
    }

    private fun getInflectedResults(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("ws.dsl.dk")
            .addPathSegment(shortName)
            .addPathSegment("query")
            .addQueryParameter("q", query)
            .addQueryParameter("app", "android")
            .addQueryParameter("version", "2.1.5")
            .build()

        val page = fetch(url.toString())
        val doc = Jsoup.parse(page, "https://ws.dsl.dk/${shortName}/")

        doc.select(".ar").forEach {
            val id = it.id()
            val k = it.selectFirst(".k") ?: return@forEach
            results.add(
                SearchResult(
                    k.text(),
                    getEntryUri(id)
                )
            )
        }

        return results
    }

    private fun publicApiRequest(requestUrl: String): JsonArray {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
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
        val results = ArrayList<SearchResult>()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("ws.dsl.dk")
            .addPathSegment(shortName)
            .addPathSegment("livesearch")
            .addQueryParameter("text", query)
            .addQueryParameter("size", "50")
            .build()

        results.addAll(getInflectedResults(query))

        val words = publicApiRequest(url.toString())
        for (el in words) {
            if (!el.isJsonPrimitive) continue

            val word = el.asString
            results.add(
                SearchResult(
                    word,
                    HttpUrl.Builder()
                        .scheme("https")
                        .host("ws.dsl.dk")
                        .addPathSegment(shortName)
                        .addPathSegment("query")
                        .addQueryParameter("app", "android")
                        .addQueryParameter("version", "2.1.5")
                        .addQueryParameter("q", word)
                        .build()
                )
            )
        }

        return results.distinctBy { it.mTitle }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        private val MAIN_SITE_CONTENT_PATTERN =
            Pattern.compile("class=\"ar(?:tikel)?\" id=\"([0-9]+)\"", Pattern.DOTALL)
    }
}