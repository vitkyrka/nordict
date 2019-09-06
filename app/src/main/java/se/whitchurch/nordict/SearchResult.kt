package se.whitchurch.nordict

import android.net.Uri

class SearchResult(val mTitle: String, val mSummary: String, internal val uri: Uri) {

    override fun toString(): String {
        return mTitle
    }

    constructor(title: String, uri: Uri) : this(title, "", uri)

    constructor(title: String) : this(title, "", Uri.parse("http://fake/"))
}