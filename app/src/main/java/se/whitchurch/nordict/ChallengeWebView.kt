package se.whitchurch.nordict

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger

/**
 * Hidden-WebView page transport for hosts that challenge non-browser clients
 * (Cloudflare on collinsdictionary.com). A real Chromium engine executes the
 * managed challenge, mints `cf_clearance`, and the rendered DOM is handed
 * back up the same string path the OkHttp body takes, so parsers are
 * untouched.
 *
 * The WebView is created with the application context and never attached to
 * a window in the normal case. [loadAndExtract] must be called off the main
 * thread (every dictionary call site already is: `Dispatchers.IO`,
 * [MultiDict]'s pool, the agent driver's connection thread); it posts work
 * to the main thread and blocks the caller on latches.
 *
 * When the challenge stays interactive (a "verify you are human" checkbox a
 * hidden view cannot tap), the current [tapHost] — [MainActivity] while
 * resumed — is asked to show this same WebView in a dialog; the poll loop
 * keeps running and picks up the success once the user taps through.
 */
object ChallengeWebView {
    private val log: Logger = Logger.getLogger("ChallengeWebView")

    /** Total budget per fetch (page load + challenge + poll). */
    const val TIMEOUT_MS = 90_000L
    private const val PAGE_WAIT_MS = 25_000L
    private const val POLL_MS = 750L
    /** A persistent challenge escalates to the tap dialog after this long. */
    private const val ESCALATE_AFTER_MS = 12_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private var webView: WebView? = null

    /** Set by [MainActivity] while resumed; null everywhere else (tests, background). */
    @Volatile
    var tapHost: TapHost? = null

    @Volatile
    private var appContext: android.content.Context? = null

    /** Hosts the challenge WebView visibly so the user can tap a checkbox. */
    interface TapHost {
        fun showChallengeWebView(view: WebView)
        fun hideChallengeWebView()
    }

    data class WebFetch(val result: PageResult, val cookies: String?)

    private data class Probe(
        val entry: Boolean = false,
        val json: Boolean = false,
        val challenge: Boolean = false
    )

    /** Call once from [MainActivity.onCreate]; the holder keeps the application context. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }

    /**
     * Loads [url] in the hidden WebView, waits out any Cloudflare challenge,
     * and returns the rendered page: `outerHTML` for a word page, the raw
     * text for a JSON endpoint (e.g. Collins `/autocomplete/`). Never call on
     * the main thread (returns a failure rather than deadlocking).
     */
    fun loadAndExtract(url: String, timeoutMs: Long = TIMEOUT_MS): WebFetch {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log.severe("loadAndExtract called on the main thread for $url")
            return WebFetch(PageResult(-1, ""), null)
        }
        if (appContext == null) {
            log.severe("loadAndExtract before init for $url")
            return WebFetch(PageResult(-1, ""), null)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val gen = generation.incrementAndGet()

        // 1. Load on the main thread; wait for the first page finish.
        val pageLatch = CountDownLatch(1)
        var pageError = false
        mainHandler.post {
            val view = ensureWebView()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    if (gen == generation.get()) pageLatch.countDown()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame && gen == generation.get()) {
                        pageError = true
                        pageLatch.countDown()
                    }
                }
            }
            view.loadUrl(url)
        }
        pageLatch.await(PAGE_WAIT_MS, TimeUnit.MILLISECONDS)
        if (pageError) return WebFetch(PageResult(-1, ""), null)

        // 2. Poll the DOM: entry content, JSON text, or a lingering challenge.
        var escalated = false
        while (System.currentTimeMillis() < deadline) {
            when (val probe = evalProbe()) {
                null -> Unit // eval hiccup; keep polling
                else -> {
                    if (probe.entry) {
                        return finish(url, evalString(ENTRY_JS), isJson = false)
                    }
                    if (probe.json) {
                        return finish(url, evalString(JSON_JS), isJson = true)
                    }
                    if (probe.challenge && !escalated &&
                        System.currentTimeMillis() > deadline - timeoutMs + ESCALATE_AFTER_MS
                    ) {
                        escalated = true
                        requestTap()
                    }
                }
            }
            Thread.sleep(POLL_MS)
        }
        if (escalated) dismissTap()
        log.severe("challenge fetch timed out for $url")
        return WebFetch(PageResult(-1, ""), readCookies(url))
    }

    private fun finish(url: String, raw: String?, isJson: Boolean): WebFetch {
        dismissTap()
        if (raw == null) return WebFetch(PageResult(-1, ""), readCookies(url))
        // A solved challenge navigates to the real page, but double-check the
        // extraction isn't the interstitial itself.
        if (!isJson && isChallenge(200, raw)) {
            log.severe("extracted page is still a challenge for $url")
            return WebFetch(PageResult(403, raw), readCookies(url))
        }
        CookieManager.getInstance().flush()
        return WebFetch(PageResult(200, raw), readCookies(url))
    }

    private fun requestTap() {
        val host = tapHost ?: return
        val view = onMainSync<WebView>(5_000L) { done -> done(webView) } ?: return
        try {
            host.showChallengeWebView(view)
        } catch (e: Exception) {
            log.severe("tap dialog failed: ${e.message}")
        }
    }

    private fun dismissTap() {
        try {
            tapHost?.hideChallengeWebView()
        } catch (e: Exception) {
            log.severe("tap dismiss failed: ${e.message}")
        }
    }

    /** Detaches the WebView from any dialog parent (called on the main thread by the host). */
    fun detachFromParent() {
        (webView?.parent as? ViewGroup)?.removeView(webView)
    }

    private fun ensureWebView(): WebView {
        var view = webView
        if (view == null) {
            // Application context: the holder outlives any activity (rotation
            // must not leak it) and the view is normally never attached.
            val context = appContext ?: throw IllegalStateException("ChallengeWebView.init not called")
            view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            webView = view
        }
        return view
    }

    private fun evalProbe(): Probe? {
        val raw = evalString(PROBE_JS) ?: return null
        return try {
            Gson().fromJson(raw, Probe::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /** Runs [JS] via `evaluateJavascript` on the main thread, decoding the JSON-encoded return. */
    private fun evalString(JS: String): String? {
        val raw = onMainSync<String>(10_000L) { done ->
            webView?.evaluateJavascript(JS, android.webkit.ValueCallback { done(it) })
                ?: done(null)
        } ?: return null
        if (raw == "null") return null
        return try {
            Gson().fromJson(raw, String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun readCookies(url: String): String? {
        return onMainSync(5_000L) { done ->
            done(
                try {
                    CookieManager.getInstance().getCookie(url)
                } catch (e: Exception) {
                    null
                }
            )
        }
    }

    private fun <T> onMainSync(timeoutMs: Long, block: ((T?) -> Unit) -> Unit): T? {
        val latch = CountDownLatch(1)
        var out: T? = null
        mainHandler.post { block { value -> out = value; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    private const val PROBE_JS =
        "(function(){" +
            "var e=document.querySelector('div.cB.cB-def')!=null;" +
            "var t=document.documentElement.innerText.trim();" +
            "var j=t.charAt(0)=='[';" +
            "var c=document.title.indexOf('Just a moment')>=0" +
            "||!!document.querySelector('iframe[src*=\"challenges.cloudflare\"]');" +
            "return JSON.stringify({entry:e,json:j,challenge:c});" +
            "})()"

    private const val ENTRY_JS =
        "(function(){return document.documentElement.outerHTML})()"

    private const val JSON_JS =
        "(function(){return document.documentElement.innerText})()"
}
