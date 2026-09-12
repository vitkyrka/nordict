package se.whitchurch.nordict

import android.net.Uri
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Bridge between the shared `:core` module's JVM-neutral `okhttp3.HttpUrl` and
 * Android's `android.net.Uri`. The DLE parser (and `Word`/`SearchResult`) store
 * `HttpUrl`; convert at the app boundary whenever an Android `Uri` is required
 * (intents, content-provider rows, ordboken navigation).
 */
fun Uri.toHttpUrl(): HttpUrl =
    toString().toHttpUrlOrNull() ?: throw IllegalArgumentException("not an HTTP(S) URL: $this")

fun HttpUrl.toAndroidUri(): Uri = Uri.parse(toString())