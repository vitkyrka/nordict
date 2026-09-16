package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

abstract class Wiktionary(
    client: OkHttpClient,
    private val baseUrl: String = ""
) : Dictionary(client) {
    abstract val shortName: String

    private val effectiveBaseUrl: String
        get() = baseUrl.ifEmpty { "https://${shortName}.m.wiktionary.org" }

    private fun fetchBody(requestUrl: String, acceptJson: Boolean = false): String {
        val builder = Request.Builder().url(requestUrl)
            .addHeader("User-Agent", USER_AGENT)
        if (acceptJson) builder.addHeader("Accept", "application/json")

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            return ""
        }

        if (!response.isSuccessful) {
            log.severe("Unexpected response: " + response.code)
            return ""
        }

        return response.body?.string() ?: ""
    }

    override fun get(uri: HttpUrl): Word? {
        val baseHost = effectiveBaseUrl.toHttpUrlOrNull()?.host ?: ""
        if (uri.host != baseHost && uri.host != "${shortName}.m.wiktionary.org" &&
            uri.host != "${shortName}.wiktionary.org"
        ) {
            return null
        }

        val page = fetchBody(uri.toString())
        val newUri = uri.withoutRefParam()

        val words = WiktionaryParser.parse(page, newUri, tag, shortName, effectiveBaseUrl)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    override fun search(query: String): List<SearchResult> {
        val base = effectiveBaseUrl.toHttpUrlOrNull() ?: return emptyList()
        val searchBase = if (base.host.endsWith(".m.wiktionary.org")) {
            base.newBuilder()!!
                .host(base.host.removeSuffix(".m.wiktionary.org") + ".wiktionary.org")
                .build()
        } else {
            base
        }

        val requestUrl = searchBase.newBuilder()!!
            .addPathSegments("w/rest.php/v1/search/title")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "10")
            .build()

        val body = fetchBody(requestUrl.toString(), acceptJson = true)
        if (body.isEmpty()) return emptyList()

        return WiktionaryParser.parseSearch(body, shortName) { id, title ->
            "https://${shortName}.m.wiktionary.org/?curid=$id".toHttpUrlOrNull()
                ?: searchBase.resolve("/wiki/$title")!!
        }
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    companion object {
        const val REFPARAM = "__ref"

        // Wikimedia's API policies reject generic user agents (the OkHttp
        // default) with HTTP 403; send a descriptive one so search and page
        // fetches work from the app and the desktop CLI.
        const val USER_AGENT = "Nordict (Android/CLI dictionary client; https://github.com/whitchurch/nordict)"
    }
}