package se.whitchurch.nordict

import android.os.Looper
import okhttp3.OkHttpClient
import java.util.logging.Logger

/**
 * The `cf_clearance` token the hidden WebView mints while solving Collins'
 * Cloudflare challenge, synced back to OkHttp so later Collins fetches can
 * take the fast path again. Memory-only: the cookie carries its own expiry
 * and a fresh one is minted on demand when it stops working.
 */
object CollinsClearance {
    private const val HOST = "www.collinsdictionary.com"
    private val log: Logger = Logger.getLogger("CollinsClearance")

    @Volatile
    var cfClearance: String? = null
        private set

    /** Extra headers for Collins URLs while a clearance token is held. */
    fun cookieHeaderFor(url: String): Map<String, String> {
        val clearance = cfClearance ?: return emptyMap()
        if (HOST !in url) return emptyMap()
        return mapOf("Cookie" to "cf_clearance=$clearance")
    }

    /** Records the clearance token from a WebView cookie header, if present. */
    fun noteCookies(url: String, cookies: String?) {
        if (cookies == null || HOST !in url) return
        cookies.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("cf_clearance=") }
            ?.removePrefix("cf_clearance=")
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                cfClearance = it
                log.fine("cf_clearance synced back to OkHttp")
            }
    }

    /** Test-only reset. */
    fun reset() {
        cfClearance = null
    }
}

/**
 * [PageFetcher] that loads the URL in the hidden challenge-solving WebView
 * and syncs any minted clearance back to [CollinsClearance]. Must be called
 * off the main thread (all dictionary call sites are); fails fast otherwise.
 */
class WebViewPageFetcher : PageFetcher {
    private val log: Logger = Logger.getLogger("WebViewPageFetcher")

    override fun fetch(url: String, headers: Map<String, String>): PageResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log.severe("WebView fetch called on the main thread for $url")
            return PageResult(-1, "")
        }
        val fetched = ChallengeWebView.loadAndExtract(url)
        CollinsClearance.noteCookies(url, fetched.cookies)
        return fetched.result
    }
}

/**
 * The Collins transport: plain OkHttp (carrying the synced clearance when
 * held) with hidden-WebView fallback the moment a fetch looks challenged.
 * Only the two Collins dictionaries get this; everything else keeps the
 * default OkHttp fetcher.
 */
fun collinsPageFetcher(client: OkHttpClient): PageFetcher = FallbackPageFetcher(
    OkHttpPageFetcher(client, CollinsClearance::cookieHeaderFor),
    WebViewPageFetcher(),
    onFallback = { _, result ->
        CollinsTransport.noteFallback(result.isSuccessful && !isChallenge(result.code, result.body))
    }
)

/**
 * Last-seen WebView-fallback state, so UI/agent messages can tell "the
 * dictionary is empty" apart from "the dictionary challenged us". Sticky for
 * two minutes: a lookup that just went through the fallback explains the
 * result that follows it.
 */
object CollinsTransport {
    @Volatile
    var lastFallbackAtMs: Long = 0
        private set

    @Volatile
    var lastFallbackOk: Boolean = false
        private set

    fun noteFallback(ok: Boolean) {
        lastFallbackOk = ok
        lastFallbackAtMs = System.currentTimeMillis()
    }

    fun recentFallbackNotice(): String? {
        if (System.currentTimeMillis() - lastFallbackAtMs > 120_000) return null
        return if (lastFallbackOk) "Collins challenged the request; solved in the embedded browser"
        else "Collins challenged the request and the embedded browser could not solve it"
    }

    /** Test-only reset. */
    fun reset() {
        lastFallbackAtMs = 0
        lastFallbackOk = false
    }
}
