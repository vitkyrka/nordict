package se.whitchurch.nordict

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.logging.Logger

/**
 * The raw result of one page fetch: the HTTP status and the body text.
 * `code` is -1 when the request never completed (e.g. a timeout).
 */
data class PageResult(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Pluggable page transport for [Dictionary]. The default implementation is
 * plain OkHttp; the app can substitute a WebView-backed fetcher for hosts
 * that challenge non-browser clients (Cloudflare on collinsdictionary.com).
 * JVM-neutral so the desktop CLI and the plain-JUnit tests keep working.
 */
fun interface PageFetcher {
    fun fetch(url: String, headers: Map<String, String>): PageResult
}

/**
 * True when a fetch result looks like a bot challenge rather than content:
 * an outright 403, or the Cloudflare "Just a moment..." interstitial. A
 * challenged fetch carries no parseable dictionary content, so callers treat
 * it as a signal to try the next transport, not as a page.
 */
fun isChallenge(code: Int, body: String): Boolean {
    if (code == 403) return true
    return body.contains("Just a moment") || body.contains("challenges.cloudflare.com")
}

/** Plain-OkHttp [PageFetcher]: what every dictionary used before. */
class OkHttpPageFetcher(
    val client: OkHttpClient,
    /** Extra headers per URL (e.g. a synced `cf_clearance` cookie). */
    val extraHeaders: ((url: String) -> Map<String, String>)? = null
) : PageFetcher {
    override fun fetch(url: String, headers: Map<String, String>): PageResult {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (name, value) -> builder.addHeader(name, value) }
            extraHeaders?.invoke(url)?.forEach { (name, value) -> builder.addHeader(name, value) }
            val response = client.newCall(builder.build()).execute()
            PageResult(response.code, response.body?.string() ?: "")
        } catch (e: Exception) {
            Logger.getLogger("PageFetcher").severe("fetch failed for $url: ${e.message}")
            PageResult(-1, "")
        }
    }
}

/**
 * Tries [primary], falling back to [secondary] only when the primary hits
 * the challenge signature ([isChallenge]). A normal page (or a normal
 * failure like a 404/timeout) never touches the secondary transport, so the
 * fast path and the offline MockWebServer tests are unaffected.
 */
class FallbackPageFetcher(
    val primary: PageFetcher,
    val secondary: PageFetcher,
    val onFallback: ((url: String, result: PageResult) -> Unit)? = null
) : PageFetcher {
    override fun fetch(url: String, headers: Map<String, String>): PageResult {
        val first = primary.fetch(url, headers)
        if (!isChallenge(first.code, first.body)) return first
        val second = secondary.fetch(url, headers)
        onFallback?.invoke(url, second)
        return second
    }
}
