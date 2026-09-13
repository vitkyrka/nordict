package se.whitchurch.nordict

import okhttp3.HttpUrl

/**
 * JVM-neutral [HttpUrl] helpers shared by the dictionaries.
 *
 * The dictionaries used `android.net.Uri.buildUpon()``; their homograph
 * selection relies on stripping the `__ref` (or `ref`) query parameter before
 * fetching a page. That block was copy-pasted across nine dictionaries, so it
 * now lives here once.
 */
fun HttpUrl.withoutQueryParam(name: String): HttpUrl {
    if (queryParameter(name) == null) return this
    return newBuilder()!!.removeAllQueryParameters(name).build()
}

/** RAE-style homograph refs use `__ref` (SO uses `ref`). */
fun HttpUrl.withoutRefParam(): HttpUrl = withoutQueryParam("__ref")

fun HttpUrl.withQueryParam(name: String, value: String): HttpUrl =
    newBuilder()!!.addQueryParameter(name, value).build()