package se.whitchurch.nordict

import android.net.Uri
import okhttp3.OkHttpClient
import org.json.JSONObject

class InfopediaDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "INFOPEDIA"
    override val flag: Int = R.drawable.flag_pt
    override val lang: String = "pt"
    override fun init() = Unit

    override fun search(query: String): List<SearchResult> {
        val uriBuilder =
            Uri.parse("https://www.infopedia.pt/dicionarios/lingua-portuguesa/sugestao-pesquisa/").buildUpon()

        uriBuilder.appendEncodedPath(query)

        val page = fetch(uriBuilder.build().toString())
        if (page.isEmpty()) {
            return ArrayList();
        }

        val obj = JSONObject(page)
        val html = obj.getString("html")

        return InfopediaParser.parseSearch(html)
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: Uri): Word? {
        if (uri.host != "www.infopedia.pt") {
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

        val words = InfopediaParser.parse(page, newUri, tag)
        if (words.isEmpty()) return null

        val ref = uri.getQueryParameter(REFPARAM) ?: return words[0]

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