package se.whitchurch.nordict

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.logging.Logger

abstract class Dictionary(val client: OkHttpClient) : WordLookup {
    override abstract val tag: String

    /**
     * Language/flag key, mapped to a drawable at the app boundary
     * (`flagResId` in the app); `"se"`, `"dk"`, `"sedk"`, `"es"`, `"ca"`,
     * `"pt"`, `"fr"`. Kept JVM-neutral so the class runs on the desktop.
     */
    abstract val flagCode: String
    abstract val lang: String

    override abstract fun search(query: String): List<SearchResult>
    abstract fun fullSearch(query: String): List<SearchResult>
    override abstract fun get(uri: HttpUrl): Word?

    protected val log: Logger by lazy { Logger.getLogger(tag) }

    fun fetch(pageUrl: String): String {
        val request = Request.Builder().url(pageUrl).build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            log.severe("Unexpected response: " + response.code)
            return ""
        }

        log.fine("url $pageUrl")

        return response.body?.string() ?: ""
    }
}