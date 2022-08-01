package se.whitchurch.nordict

import android.net.Uri
import okhttp3.OkHttpClient

class LingueeDictionary(client: OkHttpClient) : Dictionary(client) {
    override val tag: String = "LINGPT"
    override val flag: Int = R.drawable.flag_pt
    override val lang: String = "pt"
    override fun init() = Unit

    override fun search(query: String): List<SearchResult> {
        val uriBuilder =
            Uri.parse("https://www.linguee.pt/portugues-ingles/search").buildUpon()

        uriBuilder.appendQueryParameter("qe", query)
        uriBuilder.appendQueryParameter("source", "auto")
        uriBuilder.appendQueryParameter("cw", "703")
        uriBuilder.appendQueryParameter("ch", "1332")

        val uri = uriBuilder.build()
        return LingueeParser.parseSearch(fetch(uri.toString()), uri)
    }

    override fun fullSearch(query: String): List<SearchResult> = search(query)

    override fun get(uri: Uri): Word? {
        if (uri.host != "www.linguee.pt") {
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

        val words = LingueeParser.parse(page, newUri, tag)
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