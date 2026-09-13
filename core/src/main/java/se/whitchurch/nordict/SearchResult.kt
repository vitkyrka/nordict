package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearchResult(
    val mTitle: String,
    val mSummary: String = "",
    val uri: HttpUrl,
    // The ordered tags of the dictionaries that produced this result
    // (a combined multi-dictionary selection fills this; single lookups keep
    // it empty and `sources` follows suit).
    val dicts: List<String> = emptyList(),
    // The per-dictionary page for this result, in dicts order. `uri` above is
    // the first source's page.
    val sources: List<CombSource> = emptyList()
) {

    override fun toString(): String {
        return mTitle
    }

    constructor(title: String, uri: HttpUrl) : this(title, "", uri)

    constructor(title: String) : this(title, "", "http://fake/".toHttpUrlOrNull()!!)
}