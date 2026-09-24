package se.whitchurch.nordict

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders an Anki card Back as self-contained static HTML: the
 * `renderCardWord` output for a synthetic card word (see
 * [Cards.buildCardWord]) wrapped with the inlined `renderer.css`, so the Back
 * needs no JavaScript at review time and always carries the headword title,
 * the grammar/domain/geo colors, and the dark-mode palette — for definition
 * cards, merged cards, combined multi-dictionary cards, and idiom-only cards
 * alike.
 *
 * The render runs `renderer.js` verbatim inside a hidden WebView (the same
 * template the word view loads, minus `word.js` auto-linking) and captures
 * `#content`'s `innerHTML`, so cards can never drift from the word view.
 * The result is asynchronous: [render] always calls [onDone] on the main
 * thread, with null when the capture fails or times out.
 */
object CardBackRenderer {
    private const val CAPTURE_TIMEOUT_MS = 10_000L

    /**
     * Test seam: replaces the hidden-WebView capture (Robolectric never runs
     * page JavaScript, so the real path cannot produce HTML there). Tests set
     * this to answer with canned HTML and optionally record the card word.
     */
    @Volatile
    var debugRenderer: ((Context, Word, (String?) -> Unit) -> Unit)? = null

    /** The self-contained Back HTML for [cardWord]'s captured content. */
    fun wrapBack(css: String, contentHtml: String): String =
        "<style>$css</style>" +
            "<div id=\"content\" class=\"card-back\">$contentHtml</div>"

    fun render(context: Context, cardWord: Word, onDone: (String?) -> Unit) {
        debugRenderer?.let { seam ->
            seam(context, cardWord, onDone)
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { render(context, cardWord, onDone) }
            return
        }
        val appContext = context.applicationContext ?: context
        val css = try {
            appContext.assets.open("renderer.css").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            onDone(null)
            return
        }
        val template = try {
            appContext.assets.open("word_template.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            onDone(null)
            return
        }
        // Injected as a JS object literal: escape a literal </script> so card
        // text can never terminate the block early (same escaping as cli.js).
        val json = Gson().toJson(cardWord).replace("</script", "<\\/script")
        val html = template.replace(
            "</body>",
            "<script>loadCard($json);</script></body>"
        )
        val done = AtomicBoolean(false)
        fun finish(back: String?) {
            if (done.compareAndSet(false, true)) onDone(back)
        }
        val timeout = Handler(Looper.getMainLooper())
        val webView = try {
            WebView(context)
        } catch (e: Exception) {
            onDone(null)
            return
        }
        val timeoutTask = Runnable {
            webView.destroy()
            finish(null)
        }
        timeout.postDelayed(timeoutTask, CAPTURE_TIMEOUT_MS)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                    "(function(){var el=document.getElementById('content');" +
                        "return el?el.innerHTML:'';})()"
                ) { result ->
                    timeout.removeCallbacks(timeoutTask)
                    webView.destroy()
                    val inner = try {
                        if (result == null || result == "null") null
                        else Gson().fromJson(result, String::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    finish(inner?.takeIf { it.isNotEmpty() }?.let { wrapBack(css, it) })
                }
            }
        }
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
    }
}
