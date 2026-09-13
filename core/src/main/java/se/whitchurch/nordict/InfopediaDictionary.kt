package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class InfopediaDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "INFOPEDIA"
    override val flagCode: String = "pt"
    override val lang: String = "pt"

    override fun search(query: String): List<SearchResult> {
        val uri = HttpUrl.Builder()
            .scheme("https")
            .host("www.infopedia.pt")
            .addPathSegments("dicionarios/lingua-portuguesa/sugestao-pesquisa")
            .addPathSegment(query)
            .build()

        val page = fetch(uri.toString())
        if (page.isEmpty()) {
            return ArrayList();
        }

        val html = try {
            JsonParser.parseString(page).asJsonObject.get("html")?.asString
        } catch (e: Exception) {
            null
        } ?: return ArrayList()

        return InfopediaParser.parseSearch(html)
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: HttpUrl): Word? {
        if (uri.host != "www.infopedia.pt") {
            return null
        }

        val newUri = uri.withoutRefParam()
        val page = fetch(newUri.toString())

        val words = InfopediaParser.parse(page, newUri, tag)
        if (words.isEmpty()) return null

        val ref = uri.queryParameter(REFPARAM) ?: return words[0]

        val candidates = words.filter { ref in it.xrefs }
        if (candidates.isEmpty()) {
            return words[0]
        }

        return candidates[0]
    }

    companion object {
        const val REFPARAM = "__ref"
    }
}