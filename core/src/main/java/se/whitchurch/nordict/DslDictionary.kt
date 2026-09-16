package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

abstract class DslDictionary(
    client: OkHttpClient,
    protected val apiBaseUrl: HttpUrl = "https://ws.dsl.dk".toHttpUrlOrNull()!!,
    protected val siteBaseUrl: HttpUrl = "https://ordnet.dk".toHttpUrlOrNull()!!
) : Dictionary(client) {
    abstract val shortName: String

    private fun getEntryUri(id: String, paramName: String = "entry_id"): HttpUrl =
        siteBaseUrl.newBuilder()!!
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

        val doc = Jsoup.parse(page, "${apiBaseUrl}/${shortName}/")
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
        // Route on path (not just host) so a single MockWebServer can stand in
        // for both bases in tests; the production hosts always differ.
        return when {
            uri.host == siteBaseUrl.host && uri.pathSegments.lastOrNull() == "ordbog" ->
                getMainSiteWord(uri)

            uri.host == apiBaseUrl.host && uri.pathSegments.lastOrNull() == "query" ->
                getApiWord(uri)

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

        return DdoParser.parse(page, newUri, tag, "${siteBaseUrl}/${shortName}/").firstOrNull()
    }

    private fun getInflectedResults(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()

        val url = apiBaseUrl.newBuilder()!!
            .addPathSegment(shortName)
            .addPathSegment("query")
            .addQueryParameter("q", query)
            .addQueryParameter("app", "android")
            .addQueryParameter("version", "2.1.5")
            .build()

        val page = fetch(url.toString())
        val doc = Jsoup.parse(page, "${apiBaseUrl}/${shortName}/")

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

        results.addAll(getInflectedResults(query))

        val url = apiBaseUrl.newBuilder()!!
            .addPathSegment(shortName)
            .addPathSegment("livesearch")
            .addQueryParameter("text", query)
            .addQueryParameter("size", "50")
            .build()

        results.addAll(
            DdoParser.parseSearch(publicApiRequest(url.toString())) { word ->
                apiBaseUrl.newBuilder()!!
                    .addPathSegment(shortName)
                    .addPathSegment("query")
                    .addQueryParameter("app", "android")
                    .addQueryParameter("version", "2.1.5")
                    .addQueryParameter("q", word)
                    .build()
            }
        )

        return results.distinctBy { it.mTitle }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        private val MAIN_SITE_CONTENT_PATTERN =
            Pattern.compile("class=\"ar(?:tikel)?\" id=\"([0-9]+)\"", Pattern.DOTALL)
    }
}