package se.whitchurch.nordict

import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.jsoup.Jsoup
import java.util.regex.Pattern

abstract class DslDictionary(client: OkHttpClient) : Dictionary(client) {
    abstract val shortName: String
    override fun init() = Unit

    private fun getEntryUri(id: String, paramName: String = "entry_id"): Uri {
        return Uri.parse("https://ordnet.dk/${shortName}/ordbog")
            .buildUpon()
            .appendQueryParameter(paramName, id)
            .appendQueryParameter("query", ".")
            .build()
    }

    private fun getApiWord(uri: Uri): Word? {
        val page = fetch(uri.toString())

        val matcher = DslDictionary.MAIN_SITE_CONTENT_PATTERN.matcher(page)
        if (!matcher.find()) {
            return null
        }

        val id = matcher.group(1)
        val word = getMainSiteWord(Uri.parse("https://ordnet.dk/ddo/ordbog?entry_id=$id&query=."))
            ?: return null

        val doc = Jsoup.parse(page, "https://ws.dsl.dk/${shortName}}/")
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

    override fun get(uri: Uri): Word? {
        return when {
            uri.host == "ordnet.dk" -> getMainSiteWord(uri)
            uri.host == "ws.dsl.dk" -> getApiWord(uri)
            else -> null
        }
    }

    protected fun getMainSiteWord(uri: Uri): Word? {
        var id = uri.getQueryParameter("entry_id")
        val newUri: Uri

        // Replace query with . to avoid duplicates in history
        if (id != null) {
            newUri = getEntryUri(id)
        } else {
            id = uri.getQueryParameter("subentry_id") ?: return null
            newUri = getEntryUri(id, "subentry_id")
        }

        val page = fetch(newUri.toString())

        return DdoParser.parse(page, newUri, tag)
    }

    private fun getInflectedResults(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()

        val uriBuilder = Uri.parse("https://ws.dsl.dk/${shortName}/query").buildUpon()

        uriBuilder.appendQueryParameter("q", query)
        uriBuilder.appendQueryParameter("app", "android")
        uriBuilder.appendQueryParameter("version", "2.1.5")

        val page = fetch(uriBuilder.build().toString())
        val doc = Jsoup.parse(page, "https://ws.dsl.do/${shortName}/")

        doc.select(".ar").forEach {
            val id = it.id()
            val k = it.selectFirst(".k") ?: return@forEach
            results.add(
                SearchResult(
                    k.text(),
                    Uri.parse("https://ordnet.dk/${shortName}/ordbog?entry_id=$id&query=.")
                )
            )
        }

        return results
    }

    private fun publicApiRequest(requestUrl: String): JSONArray {
        val request = Request.Builder().url(requestUrl)
            .addHeader("Accept", "application/json")
            .build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(NAME, "Unexpected response: " + response.code)
            return JSONArray()
        }

        return JSONArray(response.body?.string())
    }

    override fun search(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        val uriBuilder = Uri.parse("https://ws.dsl.dk/${shortName}/livesearch").buildUpon()

        uriBuilder.appendQueryParameter("text", query)
        uriBuilder.appendQueryParameter("size", 50.toString())

        results.addAll(getInflectedResults(query))

        try {
            val words = publicApiRequest(uriBuilder.build().toString())

            for (i in 0 until words.length()) {
                results.add(
                    SearchResult(
                        words.getString(i),
                        Uri.parse(
                            "https://ws.dsl.dk/${shortName}/query?app=android&version=2.1.5&q=${
                                Uri.encode(
                                    words.getString(i)
                                )
                            }"
                        )
                    )
                )
            }
        } catch (e: JSONException) {
        }

        return results.distinctBy { it.mTitle }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val NAME = "DDO"
        private val MAIN_SITE_CONTENT_PATTERN =
            Pattern.compile("class=\"ar(?:tikel)?\" id=\"([0-9]+)\"", Pattern.DOTALL)
    }
}