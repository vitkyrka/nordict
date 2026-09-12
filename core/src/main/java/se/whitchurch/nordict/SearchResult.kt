package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SearchResult(val mTitle: String, val mSummary: String, val uri: HttpUrl) {

    override fun toString(): String {
        return mTitle
    }

    constructor(title: String, uri: HttpUrl) : this(title, "", uri)

    constructor(title: String) : this(title, "", "http://fake/".toHttpUrlOrNull()!!)
}