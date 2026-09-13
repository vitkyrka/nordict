package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import se.whitchurch.nordict.SearchResult
import se.whitchurch.nordict.Word

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
    val wordUrl: ((String) -> HttpUrl)?,
    val searchUrl: (String) -> HttpUrl,
    val parse: (page: String, uri: HttpUrl) -> List<Word>,
    val searchResults: (body: String) -> List<SearchResult>
)