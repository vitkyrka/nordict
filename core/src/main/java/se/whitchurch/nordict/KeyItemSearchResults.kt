package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl

/**
 * Decodes RAE `/srv/keys` search responses into [SearchResult]s. Both RAE
 * dictionaries that parse words (`DleParser`, `EstParser`) share this response
 * shape: a JSON array of `"headword|label"` strings — the label is dropped and
 * any embedded HTML is stripped from the headword.
 *
 * The app's `DleDictionary.search`/`EstDictionary.search` and the desktop CLI
 * all go through it, so the mapping stays identical everywhere.
 */
object KeyItemSearchResults {

    fun parse(body: String, uriOf: (item: String) -> HttpUrl): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        try {
            val array = JsonParser.parseString(body)
            if (!array.isJsonArray) return results
            array.asJsonArray.forEach { element ->
                if (!element.isJsonPrimitive) return@forEach
                val item = element.asString
                    .split("|").first()
                    .replace("<[^>]+?>".toRegex(), "")
                if (item.isNotEmpty()) results.add(SearchResult(item, uriOf(item)))
            }
        } catch (_: Exception) {
        }
        return results
    }
}