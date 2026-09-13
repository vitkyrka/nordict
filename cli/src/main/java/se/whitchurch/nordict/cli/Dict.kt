package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import se.whitchurch.nordict.SearchResult
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordLookup

/**
 * One shared CLI dictionary: URL builders, the shared parser shape, and the
 * per-dictionary search-response decoder. `lang` is the language code an agent
 * uses to reason about the dictionary row (mirrors `Dictionary.lang` in the
 * app: dle/est/colspan = "es", didac/gdlc/ca-es/ca-en = "ca").
 */
data class Dict(
    val aliases: List<String>,
    val tag: String,
    val lang: String,
    // True when this dictionary takes part in multi-dictionary combining:
    // JSON-rendered pages and a same-language combining selection (DLE, EST,
    // COLSPAN, DIDAC, GDLC, CA-ES, CA-EN).
    val supportsCombining: Boolean = false,
    val wordUrl: ((String) -> HttpUrl)?,
    val searchUrl: (String) -> HttpUrl,
    val parse: (page: String, uri: HttpUrl) -> List<Word>,
    val searchResults: (body: String) -> List<SearchResult>
) {
    /**
     * Adapts this CLI dictionary to the shared [WordLookup] the combined engine
     * ([se.whitchurch.nordict.MultiDict]) drives. `fetch` is the CLI's HTTP
     * fetcher (injectable in tests).
     */
    fun asLookup(fetch: (HttpUrl) -> String): WordLookup {
        val self = this
        return object : WordLookup {
            override val tag = self.tag
            override fun search(query: String): List<SearchResult> =
                self.searchResults(fetch(self.searchUrl(query)))
            override fun get(uri: HttpUrl): Word? =
                self.parse(fetch(uri), uri).firstOrNull()
        }
    }
}