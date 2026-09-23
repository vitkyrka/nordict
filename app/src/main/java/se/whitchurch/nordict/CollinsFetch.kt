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
 * The `cf_clearance` token the hidden WebView mints while solving Infopedia's
 * Cloudflare challenge, synced back to OkHttp so later Infopedia fetches can
 * take the fast path again. Memory-only, like [CollinsClearance].
 */
object InfopediaClearance {
    private const val HOST = "www.infopedia.pt"
    private val log: Logger = Logger.getLogger("InfopediaClearance")

    @Volatile
    var cfClearance: String? = null
        private set

    /** Extra headers for Infopedia URLs while a clearance token is held. */
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
 * and syncs any minted clearance back to [CollinsClearance]/[InfopediaClearance].
 * Must be called off the main thread (all dictionary call sites are); fails
 * fast otherwise.
 */
class WebViewPageFetcher : PageFetcher {
    private val log: Logger = Logger.getLogger("WebViewPageFetcher")

    override fun fetch(url: String, headers: Map<String, String>): PageResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log.severe("WebView fetch called on the main thread for $url")
            return PageResult(-1, "")
        }
        val fetched = ChallengeWebView.loadAndExtract(url, headers)
        CollinsClearance.noteCookies(url, fetched.cookies)
        InfopediaClearance.noteCookies(url, fetched.cookies)
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
 * The Infopedia transport: plain OkHttp (carrying the synced clearance when
 * held) with hidden-WebView fallback the moment a fetch looks challenged.
 * Mirrors [collinsPageFetcher]; www.infopedia.pt now challenges plain HTTP
 * clients (403 + "Just a moment...") the same way collinsdictionary.com does.
 */
fun infopediaPageFetcher(client: OkHttpClient): PageFetcher = FallbackPageFetcher(
    OkHttpPageFetcher(client, InfopediaClearance::cookieHeaderFor),
    WebViewPageFetcher(),
    onFallback = { _, result ->
        InfopediaTransport.noteFallback(result.isSuccessful && !isChallenge(result.code, result.body))
    }
)

/**
 * Cookies the app's WebViews currently hold for [url], if any. CookieManager
 * is process-wide, so everything the hidden challenge-solving WebView picked
 * up (the `cf_clearance` it minted, Cloudflare's `__cf_bm`, site sessions)
 * comes back automatically — including cookies minted after the
 * [CollinsClearance]/[InfopediaClearance] sync ran. Null when the jar is
 * empty or WebView isn't usable (unit tests); never throws.
 */
fun webViewCookiesFor(url: String): String? = try {
    android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
    null
}

/** Merges jar cookies with a synced `cf_clearance`, without duplicating it. */
fun mergeCookies(jar: String?, syncedCookie: String?): String? {
    val synced = syncedCookie?.removePrefix("Cookie=")?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        jar != null && synced != null && "cf_clearance=" !in jar -> "$jar; $synced"
        jar != null -> jar
        synced != null -> synced
        else -> null
    }
}

/**
 * Request headers for in-app audio playback (the word view's ExoPlayer, the
 * card screen's MediaPlayer). The challenged hosts' pronunciation clips —
 * Infopedia TTS, Collins hwd_sounds — live behind the same Cloudflare
 * challenge as the word pages, but the players use their own HTTP stacks that
 * never solved it: without the WebView-held cookies the clip request is
 * refused (403) and ExoPlayer fails with a silent source error, so nothing
 * plays even though the word page (and its audio URL) loaded fine.
 *
 * Infopedia additionally hotlink-guards its TTS endpoint: a bare fetch of
 * the clip URL answers 404 (the site's "desaparecida" page) unless it carries
 * the word page as `Referer`, so callers pass the playing word's page URL.
 * Collins' static sound files 403 the same kind of bare fetch, so they get
 * the word page as `Referer` too.
 */
fun audioRequestHeaders(url: String, referer: String? = null): Map<String, String> {
    val headers = HashMap<String, String>()
    val synced = CollinsClearance.cookieHeaderFor(url)["Cookie"]
        ?: InfopediaClearance.cookieHeaderFor(url)["Cookie"]
    mergeCookies(webViewCookiesFor(url), synced)?.let { headers["Cookie"] = it }
    if (referer != null && ("infopedia.pt" in url || "collinsdictionary.com" in url)) {
        headers["Referer"] = referer
    }
    if ("infopedia.pt" in url || "collinsdictionary.com" in url) {
        // The sites' own players fetch clips as browser subresources; send
        // the same metadata so bot-fighting doesn't tell us apart.
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Sec-Fetch-Dest"] = "audio"
        headers["Sec-Fetch-Mode"] = "no-cors"
        headers["Sec-Fetch-Site"] = "same-origin"
    }
    return headers
}

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

/**
 * Last-seen WebView-fallback state for Infopedia, mirroring
 * [CollinsTransport] so agent/UI messages can tell "no such word" apart from
 * "Infopedia challenged us".
 */
object InfopediaTransport {
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
        return if (lastFallbackOk) "Infopedia challenged the request; solved in the embedded browser"
        else "Infopedia challenged the request and the embedded browser could not solve it"
    }

    /** Test-only reset. */
    fun reset() {
        lastFallbackAtMs = 0
        lastFallbackOk = false
    }
}
