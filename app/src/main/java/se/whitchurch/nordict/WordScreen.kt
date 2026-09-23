package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.util.JsonReader
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.test.espresso.idling.CountingIdlingResource
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
import androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
import androidx.webkit.WebViewFeature
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.net.URLDecoder
import kotlin.math.roundToInt


sealed interface WordUiStatus {
    object Loading : WordUiStatus
    object Hidden : WordUiStatus
    data class Error(val textRes: Int) : WordUiStatus
}

/**
 * Loads and renders one word destination. The ViewModel is scoped to the word
 * NavBackStackEntry, so each pushed word keeps its own state exactly like the
 * old stacked WordActivities. Owns the WebView lifecycle, the audio player and
 * the DataStore history writes; navigation side effects flow out through the
 * [onOpenUri]/[onOpenExternal]/[onFillSearch] callbacks wired by [WordScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
class WordViewModel(
    application: android.app.Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val uri: Uri = Uri.parse(savedStateHandle.get<String>("uri") ?: "")

    // A combined (multi-dictionary) word carries the probe dictionaries'
    // `sources` JSON and an optional namespaced ref (`"DLE::2"`) instead of a
    // plain single-dictionary uri. Empty sources = a normal single word.
    val sources: List<CombSource> =
        MultiDict.sourcesFromJson(savedStateHandle.get<String>("sources") ?: "")
    private val ref: String? = savedStateHandle.get<String>("ref")?.takeIf { it.isNotEmpty() }

    companion object {
        // Shared across word destinations (Espresso idling).
        val loadResource: CountingIdlingResource = CountingIdlingResource("word")

        // Stock Chrome-on-Android UA. Cloudflare bot-fights the player's
        // default ExoPlayerLibrary UA on the challenged hosts even when the
        // request carries a valid clearance, so their clips are fetched the
        // way the site's own player fetches them. Shared with tests.
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** True for the Cloudflare-challenged audio hosts. */
        fun isChallengedAudioHost(url: String): Boolean =
            "infopedia.pt" in url || "collinsdictionary.com" in url
    }

    // Wiring from the composing screen (reassigned on every recomposition).
    var onOpenUri: ((Uri, String) -> Unit)? = null
    var onOpenSources: ((SearchResult) -> Unit)? = null
    var onOpenExternal: ((List<Uri>) -> Unit)? = null
    var onFillSearch: ((String) -> Unit)? = null

    // Pop-then-push navigation for the selection-reload path: replacing the
    // current word destination keeps the back stack free of the pre-switch
    // word instead of stacking a redundant copy of it.
    var onReplaceSources: ((SearchResult) -> Unit)? = null
    var onReplaceWord: ((Uri, String) -> Unit)? = null

    // Gate for the selection-reload path. NordictApp sets this to false while
    // the search sheet is expanded: the language/dictionary picker lives on
    // the sheet, so switches made there scope the upcoming search and must
    // not reload the word underneath (switching away to B and back to A would
    // otherwise look like a same-language change on return and pop+reload the
    // word, collapsing the sheet).
    var selectionReloadAllowed: () -> Boolean = { true }

    var mWord: Word? by mutableStateOf(null)
    var autoPlay: Boolean by mutableStateOf(false)
    var webViewVisible: Boolean by mutableStateOf(false)
    var pageFinished: Boolean by mutableStateOf(false)
    var uiStatus: WordUiStatus by mutableStateOf(WordUiStatus.Loading)
    var webView: WebView? = null

    /** Deck name for card creation: the language code (e.g. "ES") for a
     *  combined multi-dictionary word whose deck spans every selected
     *  dictionary, else the single dictionary's tag (e.g. "DLE"). */
    val deckName: String
        get() {
            val word = mWord ?: return "Nordict"
            if (sources.isEmpty()) return "Nordict - ${word.dict}"
            val lang = sources.firstNotNullOfOrNull { ordboken.dictMap[it.tag]?.lang }
            return if (lang != null) "Nordict - ${lang.uppercase()}"
            else "Nordict - ${word.dict}"
        }

    // The word action bar's exit-always scroll behavior, wired by WordScreen
    // on every composition. It owns the nested-scroll connection that the bar
    // listens to; the pinned WebView feeds it through webViewScrolled, and the
    // floating bar reads its state's clamped heightOffset to slide off/on
    // screen.
    var bottomBarScrollBehavior: BottomAppBarScrollBehavior? = null

    /**
     * The scroll offset a user left this word at, kept across a covered
     * destination so popping back can restore it. Words scroll inside the
     * pinned WebView ([captureScroll] reads `WebView.getScrollY`). Captured
     * when the destination pauses and when its composition is torn down (both
     * fire when another word is pushed over it), re-applied once the page is
     * rendered again.
     */
    var savedScrollY: Int = 0

    /** True once [mWord] has been rendered into the current WebView (used by
     * tests to observe that a re-created WebView reloaded its page). */
    val webViewLoaded: Boolean
        get() = loadedUri == mWord?.uri?.toString()

    private val ordboken: Ordboken by lazy { Ordboken.getInstance(getApplication()) }
    private var mResetZoomNextPause = false

    // The uri that has been rendered into the *current* WebView (null until the
    // current WebView renders something). Reset whenever a fresh WebView is
    // created so an already-fetched word is re-rendered into it (e.g. popping
    // back to a word destination that another word had been pushed over).
    private var loadedUri: String? = null

    // True while fetchWord()'s idling-resource increment is still covering the
    // upcoming first render; it is consumed by maybeLoadWord() so reloads
    // (which have no fetch in flight) get their own balanced increment.
    private var fetchPending = false

    // True while a dictionary-selection change is waiting for this word to
    // finish fetching. A quick change can land on a destination whose word is
    // still loading (the change hook of the previous one pushed it), and
    // onSelectionChanged() bails on a null word; the change is retried from
    // fetchWord() once the word — and its real search headword — arrives.
    private var pendingDictSwitch = false

    // Generation number + in-flight search for the cross-dictionary switch: a
    // rapid second switch supersedes (cancels) the first so the older search
    // cannot land late and push a word for a dictionary the user already left.
    private var switchDictGeneration = 0
    private var switchDictJob: kotlinx.coroutines.Job? = null

    // The HTTP transport the player fetches pronunciation clips through.
    // Challenged-host clips (Infopedia TTS, Collins sounds) need the synced
    // cf_clearance cookie, refreshed from [audioRequestHeaders] on every play;
    // internal so tests can observe the headers a play sends.
    internal val httpDataSourceFactory: DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()

    // The headers the last [playAudio] handed to [httpDataSourceFactory];
    // internal so tests can observe them (the factory offers no read-back).
    internal var lastAudioHeaders: Map<String, String> = emptyMap()
        private set

    // The User-Agent the last [playAudio] set; same observability reason.
    internal var lastAudioUserAgent: String = MediaLibraryInfo.VERSION_SLASHY
        private set

    // internal so tests can observe the playlist (a re-play must reset it).
    internal val player: ExoPlayer by lazy {
        val app = getApplication<android.app.Application>()
        // DefaultDataSource routes file:// (fallback cache clips) and other
        // local schemes to their own sources; http(s) goes through the
        // header/UA-carrying transport above.
        val dataSourceFactory = DefaultDataSource.Factory(app, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(app).setDataSourceFactory(dataSourceFactory)
        ExoPlayer.Builder(app).setMediaSourceFactory(mediaSourceFactory).build().also {
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    onAudioError()
                }
            })
        }
    }

    // Generation of the current play: a re-play supersedes an in-flight
    // fallback fetch, and a fallback re-play of its own never refalls-back.
    private var audioGeneration = 0
    private var audioFallbackDoneForGen = -1

    // A direct clip fetch failed in the player. Challenged-host clips get one
    // silent recovery per play — bytes through the hidden challenge WebView
    // (the Chromium stack the site's own player uses), replayed from a cache
    // file — because only the toast remains otherwise.
    private fun onAudioError() {
        val gen = audioGeneration
        if (gen == audioFallbackDoneForGen) {
            toastAudioError()
            return
        }
        val urls = ArrayList<String>()
        for (i in 0 until player.mediaItemCount) {
            player.getMediaItemAt(i).localConfiguration?.uri?.toString()?.let { urls.add(it) }
        }
        android.util.Log.i("NordictAudio", "play failed for $urls")
        val httpUrls = urls.filter { it.startsWith("http") && isChallengedAudioHost(it) }
        if (httpUrls.isEmpty()) {
            toastAudioError()
            return
        }
        audioFallbackDoneForGen = gen
        val referer = mWord?.uri?.toString()
        viewModelScope.launch(Dispatchers.IO) {
            val files = httpUrls.mapIndexedNotNull { index, url ->
                val fetched = ChallengeWebView.fetchBytes(url, referer)
                android.util.Log.i("NordictAudio", "webview fetch $url -> ${fetched.status}")
                if (!fetched.ok) return@mapIndexedNotNull null
                val file = java.io.File(
                    getApplication<android.app.Application>().cacheDir,
                    "audio-fallback-$index.mp3"
                )
                try {
                    file.writeBytes(fetched.bytes!!)
                    file
                } catch (e: Exception) {
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (gen != audioGeneration || files.size != httpUrls.size) {
                    if (gen == audioGeneration) toastAudioError()
                    return@withContext
                }
                android.util.Log.i("NordictAudio", "replaying ${files.size} clip(s) from cache")
                player.clearMediaItems()
                files.forEach {
                    player.addMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it)))
                }
                player.prepare()
                player.play()
            }
        }
    }

    private fun toastAudioError() {
        android.widget.Toast.makeText(
            getApplication(),
            R.string.error_audio,
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    init {
        autoPlay = ordboken.autoPlay
        fetchWord()
    }

    override fun onCleared() {
        (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
        webView?.destroy()
        webView = null
        player.release()
    }

    fun fetchWord() {
        uiStatus = WordUiStatus.Loading
        if (!fetchPending) {
            loadResource.increment()
            fetchPending = true
        }

        viewModelScope.launch {
            val word = withContext(Dispatchers.IO) {
                if (sources.isNotEmpty()) ordboken.getCombinedWord(sources, ref)
                else ordboken.getWord(uri)
            }
            mWord = word
            ordboken.currentWord = word

            if (word == null) {
                uiStatus = if (!ordboken.isOnline) {
                    WordUiStatus.Error(R.string.error_offline)
                } else {
                    WordUiStatus.Error(R.string.error_word)
                }
                loadResource.decrement()
                fetchPending = false
                return@launch
            }

            pageFinished = false
            webViewVisible = false
            historySave()
            // A selection change that landed while this word was being fetched
            // (see onSelectionChanged) is retried now that the word — and its
            // search headword — is available.
            if (pendingDictSwitch) onSelectionChanged()
            // The idling resource stays busy until onPageFinished decrements it.
        }
    }

    /** Persists the WebView scale when leaving a word. */
    // WebView.getScale() is deprecated with no replacement carrying the page
    // zoom level, which is what is persisted here.
    @Suppress("DEPRECATION")
    fun onLeave() {
        // If the WebView was never made visible, getScale() returns the default
        // scale instead of the initialScale.
        if (mResetZoomNextPause) {
            mResetZoomNextPause = false
        } else if (webViewVisible) {
            val scale = ((webView?.scale ?: 1f) * 100).toInt()
            ordboken.setScale(scale)
        }
    }

    /** Records the word's current scroll offset so a back-navigation can put
     * the page back where the user left it. A covered destination is torn down
     * (and its WebView re-created fresh), so without this the scroll silently
     * resets to the top when the word is re-entered. */
    fun captureScroll() {
        savedScrollY = webView?.scrollY ?: 0
    }

    /** Re-applies [savedScrollY] to a freshly loaded pinned WebView (words
     * scroll inside the viewport WebView, so the Compose column cannot do it).
     * Called from `onPageFinished`, once the rendered content actually has a
     * scrollable extent. */
    fun restoreWebViewScroll() {
        if (savedScrollY > 0) {
            val view = webView ?: return
            view.post { view.scrollTo(0, savedScrollY) }
        }
    }

    /**
     * Renders [mWord] into the current WebView unless it is already showing it.
     * Called whenever the word or the WebView instance changes: a freshly
     * created WebView (the destination was pushed over by another word and is
     * being recomposed) must re-render the already-fetched word, or it would
     * come back blank. The first render is covered by fetchWord()'s increment;
     * later re-renders add their own balanced increment once.
     */
    fun maybeLoadWord() {
        val word = mWord ?: return
        if (webView == null) return
        if (loadedUri == word.uri.toString()) return

        loadedUri = word.uri.toString()
        if (!fetchPending) {
            uiStatus = WordUiStatus.Loading
            pageFinished = false
            loadResource.increment()
        }
        fetchPending = false
        loadWebView(word)
    }

    // ---------- WebView ----------

    // setAllowFileAccessFromFileURLs/setAllowUniversalAccessFromFileURLs are
    // deprecated with no replacement; the word page loads from
    // file:///android_asset/ with JavaScript enabled and needs them.
    @Suppress("DEPRECATION")
    @SuppressLint("AddJavascriptInterface")
    fun createWebView(context: Context): WebView {
        // A prior WebView from a previous composition of this destination may
        // still exist (detached) but must not be reused; drop it so a fresh
        // instance reloads the word instead of coming back blank.
        (webView?.parent as? android.view.ViewGroup)?.removeView(webView)
        webView?.destroy()
        webView = null
        loadedUri = null

        val webView = WebView(context)
        this.webView = webView
        webView.clipToOutline = true
        webView.visibility = View.INVISIBLE
        webView.webChromeClient = WebChromeClient()
        val settings = webView.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            allowContentAccess = true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> {
                    WebSettingsCompat.setForceDark(webView.settings, FORCE_DARK_ON)
                }
                else -> WebSettingsCompat.setForceDark(webView.settings, FORCE_DARK_OFF)
            }
        }
        webView.setInitialScale(ordboken.scale)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                when {
                    url.contains("/search/") -> {
                        loadResource.increment()
                        val word = url.substring(url.indexOf("ch/") + 3)
                        linkSearch(URLDecoder.decode(word, "UTF-8"))
                    }
                    url.contains("https://ordnet.dk/ddo/ordbog?entry_id=") ||
                        url.contains("https://ordnet.dk/ddo/ordbog?subentry_id=") -> {
                        onOpenUri?.invoke(Uri.parse(url), "")
                    }
                    url.contains("https://ordnet.dk/korpusdk/qconc") -> {
                        onOpenExternal?.invoke(listOf(Uri.parse(url)))
                    }
                    url.contains("/so/?id=") -> {
                        onOpenUri?.invoke(
                            Uri.parse(url.replace("file:///", "https://svenska.se/")), ""
                        )
                    }
                    url.contains("mp3") -> {
                        playAudio(java.util.ArrayList(listOf(url)))
                    }
                    else -> onOpenUri?.invoke(Uri.parse(url), "")
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!pageFinished) {
                    webView.visibility = View.VISIBLE
                    webViewVisible = true
                    uiStatus = WordUiStatus.Hidden

                    if (autoPlay) mWord?.audio?.let { audio -> playAudio(audio) }

                    loadResource.decrement()
                }

                pageFinished = true
                restoreWebViewScroll()
            }
        }

        return webView
    }

    /**
     * Feeds the word action bar's exit-always scroll behavior from the pinned
     * WebView's internal scroll [WebView.OnScrollChangeListener]. The Compose
     * nested-scroll chain cannot see a platform WebView's scrolling, so this
     * bridges it to the same connection the words drive through their outer
     * Compose scrollable: scrolling the page down collapses the bar, scrolling
     * back up brings it back. A positive delta (content moved down) folds into
     * a negative consumed offset, matching the direction the M3 behavior
     * expects to collapse.
     */
    fun webViewScrolled(oldScrollY: Int, scrollY: Int) {
        val behavior = bottomBarScrollBehavior ?: return
        val delta = scrollY - oldScrollY
        if (delta == 0) return
        behavior.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -delta.toFloat()),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
    }

    fun loadWebView(word: Word) {
        val webView = this.webView ?: return

        val gson = Gson()
        val json = gson.toJson(word)
        val template =
            getApplication<android.app.Application>().assets.open("word_template.html")
                .bufferedReader().use { it.readText() }
        val html = template.replace("</body>", """
            <script>
                loadWord($json);
            </script>
            </body>
        """.trimIndent())

        webView.loadDataWithBaseURL(
            "file:///android_asset/", html,
            "text/html", "UTF-8", null
        )
    }

    // ---------- Lookups ----------

    /** A `/search/` link tapped inside the WebView. Under an active multi-dict
     * selection the link resolves across every selected dictionary and opens
     * the combined page; otherwise it opens the current dictionary's exact
     * match as usual. */
    fun linkSearch(query: String) {
        viewModelScope.launch {
            val trimmed = query.trim()
            var combined: List<CombSource>? = null
            var combinedTitle = trimmed
            var exact: SearchResult? = null
            withContext(Dispatchers.IO) {
                if (ordboken.activeDicts.isNotEmpty()) {
                    val (matched, sources) =
                        MultiDict.resolveExactWithQuery(ordboken.activeDicts, trimmed)
                    combined = sources.takeIf { it.isNotEmpty() }
                    if (combined != null) combinedTitle = matched
                    else exact = SearchResult(trimmed)
                } else {
                    val dict = ordboken.currentDictionary
                    val results = dict.search(trimmed)
                    // A word.js link can be an inflected form (e.g. Spanish
                    // plural "casas"); fall back to the singular ("casa") when
                    // the raw query has no exact match.
                    exact = ExactMatch.resolveWithSearch(trimmed, results) { dict.search(it) }
                        ?: SearchResult(trimmed)
                }
            }
            when {
                combined != null ->
                    onOpenSources?.invoke(
                        SearchResult(
                            mTitle = combinedTitle,
                            uri = combined!!.first().uri,
                            dicts = combined!!.map { it.tag },
                            sources = combined!!
                        )
                    )
                exact!!.uri.host == "fake" -> onFillSearch?.invoke(exact!!.mTitle)
                else -> onOpenUri?.invoke(exact!!.uri.toAndroidUri(), exact!!.mTitle)
            }
            loadResource.decrement()
        }
    }

    /**
     * The dictionary-selection change handler, installed on
     * [Ordboken.onDictChanged]. Fires whenever any attribute of the selection
     * changes while a word is on screen — a dictionary toggled in/out
     * ([Ordboken.toggleDictionary]), the order changed
     * ([Ordboken.setDictionaryOrder]), a combination collapsed to a single
     * dictionary, or the language switched. Reloads the word for the new
     * selection: the combined page when several dictionaries are active
     * ([MultiDict.resolveExact] then [onReplaceSources]), the new single
     * dictionary's page otherwise ([onReplaceWord]). A language switch is
     * skipped — the incoming selection belongs to a different language than
     * the word, so there is nothing to cross-search.
     */
    fun onSelectionChanged() {
        // Selection changes made while the search sheet is expanded scope the
        // upcoming search; they must neither reload the covered word nor
        // collapse the sheet (see selectionReloadAllowed).
        if (!selectionReloadAllowed()) return
        val word = mWord
        if (word == null) {
            // A quick change can land while this destination's word is still
            // being fetched; remember it and retry once the word (and its
            // real search headword) is available.
            pendingDictSwitch = true
            return
        }

        val wordDict = ordboken.dictMap[word.dict] ?: return

        val active = ordboken.activeDicts
        val selectionLang = if (active.isNotEmpty()) active.first().lang
        else ordboken.currentDictionary.lang

        // A language switch rebuilds the dictionary row and selects that
        // language's default dictionary; don't cross-search languages.
        if (selectionLang != wordDict.lang) return

        pendingDictSwitch = false

        // Snapshot the triggering selection and give this change a generation
        // number: the lookups below must reflect the selection the user made,
        // not whichever happens to be active when they finish, and a rapid
        // second change supersedes the first so an older lookup cannot land
        // late and push the wrong selection's word.
        val trigger = ordboken.selectionSignature
        val generation = ++switchDictGeneration
        switchDictJob?.cancel()
        loadResource.increment()
        switchDictJob = viewModelScope.launch {
            try {
                val combined = if (active.size > 1) {
                    withContext(Dispatchers.IO) {
                        MultiDict.resolveExact(active, word.searchHeadword)
                    }
                } else emptyList()
                // A newer change took over, or the selection was switched
                // meanwhile (e.g. the agent's switchOp runs with the hook
                // nulled); the newer caller will navigate, so this stale
                // result must not.
                if (generation != switchDictGeneration) return@launch
                if (ordboken.selectionSignature != trigger) return@launch
                if (combined.isNotEmpty()) {
                    onReplaceSources?.invoke(
                        SearchResult(
                            mTitle = word.searchHeadword,
                            uri = combined.first().uri,
                            dicts = combined.map { it.tag },
                            sources = combined
                        )
                    )
                    return@launch
                }
                // Single selection, or a multi selection where no dictionary
                // has the word under the new combination: reload in the
                // selection's actual dictionary.
                val exact = withContext(Dispatchers.IO) {
                    val results = ordboken.currentDictionary.search(word.searchHeadword)
                    ExactMatch.resolve(word.searchHeadword, results)
                        ?: SearchResult(word.searchHeadword)
                }
                if (generation != switchDictGeneration) return@launch
                if (ordboken.selectionSignature != trigger) return@launch
                if (exact.uri.host == "fake") {
                    onFillSearch?.invoke(exact.mTitle)
                } else {
                    onReplaceWord?.invoke(exact.uri.toAndroidUri(), exact.mTitle)
                }
            } finally {
                loadResource.decrement()
            }
        }
    }

    // ---------- History ----------

    private fun historySave() {
        val word = mWord ?: return
        // A combined page's own uri/dict is only its first source's: persist
        // the full sources probe list so history reopens the merged page,
        // not the single dictionary. Single words keep an empty sources cell.
        val sourcesJson = MultiDict.sourcesToJson(sources)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                saveHistoryEntry(
                    getApplication(),
                    dict = word.dict,
                    title = word.mTitle,
                    summary = word.summary,
                    url = word.uri.toString(),
                    sources = sourcesJson
                )
            }
        }
    }

    // ---------- Audio / share ----------

    fun playAudio(urls: java.util.ArrayList<String>) {
        if (urls.isEmpty()) return
        audioGeneration += 1
        // The player's own HTTP stack never solved the Cloudflare challenge,
        // so challenged-host clips 403 without the synced clearance cookie;
        // Infopedia's TTS endpoint additionally needs the word page as
        // Referer or it answers 404.
        val referer = mWord?.uri?.toString()
        val headers = urls.flatMap { audioRequestHeaders(it, referer).entries }
            .associate { it.key to it.value }
        lastAudioHeaders = headers
        httpDataSourceFactory.setDefaultRequestProperties(headers)
        // Challenged hosts bot-fight the player's library UA even with a
        // valid clearance; unchallenged hosts keep the stock one.
        val ua =
            if (urls.any { isChallengedAudioHost(it) }) BROWSER_UA
            else MediaLibraryInfo.VERSION_SLASHY
        lastAudioUserAgent = ua
        httpDataSourceFactory.setUserAgent(ua)
        player.clearMediaItems()
        urls.forEach { player.addMediaItem(MediaItem.fromUri(it)) }
        player.prepare()
        player.play()
    }

    fun playAudio(url: String) {
        playAudio(java.util.ArrayList(listOf(url)))
    }

    fun resetZoom() {
        mResetZoomNextPause = true
        ordboken.setScale(0)
    }

    fun share() {
        if (mWord == null) return
        val webView = this.webView ?: return
        webView.evaluateJavascript("getCSS()", android.webkit.ValueCallback { json ->
            val reader = JsonReader(StringReader(json))
            reader.isLenient = true
            ordboken.currentCss = reader.nextString()

            val intent = Intent(getApplication(), CardActivity::class.java).apply {
                putExtra("deckName", deckName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<android.app.Application>().startActivity(intent)
        })
    }
}

/** The word destination: WebView content plus the word action bars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordScreen(
    vm: WordViewModel,
    ordboken: Ordboken,
    onOpenUri: (Uri, String) -> Unit,
    onOpenSources: (SearchResult) -> Unit,
    onOpenExternal: (List<Uri>) -> Unit,
    onFillSearch: (String) -> Unit,
    onReplaceSources: (SearchResult) -> Unit,
    onReplaceWord: (Uri, String) -> Unit,
    isSearchExpanded: () -> Boolean = { false }
) {
    vm.onOpenUri = onOpenUri
    vm.onOpenSources = onOpenSources
    vm.onOpenExternal = onOpenExternal
    vm.onFillSearch = onFillSearch
    vm.onReplaceSources = onReplaceSources
    vm.onReplaceWord = onReplaceWord
    vm.selectionReloadAllowed = { !isSearchExpanded() }

    val word = vm.mWord
    val lifecycleOwner = LocalLifecycleOwner.current

    // The floating word action bar uses the M3 exit-always scroll behavior: it
    // slides out when the word content is scrolled down and reappears on a
    // scroll back up. The connection lives on the screen's root (as the M3
    // wiring does); the pinned WebView feeds it through vm.webViewScrolled
    // since its internal scroll is invisible to the Compose nested-scroll
    // chain.
    val bottomBarScrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()
    vm.bottomBarScrollBehavior = bottomBarScrollBehavior

    // Load the word page as soon as the fetch lands. The AndroidView factory
    // can run before the coroutine finishes, so a fast fetch (or a retry)
    // re-triggers this load after the WebView exists. Also keyed on the
    // WebView instance so a fresh WebView created after the destination was
    // pushed over by another word re-renders the already-fetched word.
    LaunchedEffect(word, vm.webView) { vm.maybeLoadWord() }

    // A word's page loads asynchronously into the WebView, so when this
    // destination is re-entered after being covered the scrollable's max extent
    // starts at 0 and clamps a restored offset to it. Once the page finishes and
    // reports its laid-out height, put the WebView back where the user left it
    // (restoreWebViewScroll re-applies the saved offset on the pinned page).

    // Restore Ordboken state + the cross-dictionary hook on resume, matching
    // the old WordActivity.onResume/onPause duties. The hook is installed at
    // composition so a dictionary tap during a destination transition (old
    // dest paused, new dest not yet resumed) still reaches a live word view;
    // it is reinstated on resume in case anything nulled it meanwhile, and
    // only released on disposal while this destination still owns it.
    DisposableEffect(lifecycleOwner, ordboken) {
        val myHook: () -> Unit = { vm.onSelectionChanged() }
        ordboken.onDictChanged = myHook
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ordboken.currentWord = vm.mWord
                    ordboken.onDictChanged = myHook
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Persist the WebView zoom on pause (backgrounding OR
                    // leaving the word, e.g. back or closing the app), like
                    // the old WordActivity.onPause. Saving on composition
                    // disposal is too late: the ViewModel's onCleared has
                    // already destroyed the WebView by then, so getScale()
                    // would return the default and overwrite the user's zoom.
                    // The cross-link hook is intentionally NOT nulled here: a
                    // dictionary tap during the transition to the next word
                    // destination must still reach this (or the next) view.
                    vm.onLeave()
                    vm.captureScroll()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ordboken.onDictChanged === myHook) ordboken.onDictChanged = null
            // Defensive complement to the pause capture: a covered destination
            // is disposed right around the ON_PAUSE event, and the WebView (and
            // its scroll) survives untouched until the re-created one replaces
            // it, so the last offset is still readable here.
            vm.captureScroll()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxH = maxHeight

            AndroidView(
                    factory = { ctx ->
                        val webView = vm.createWebView(ctx)
                        // Bridge the pinned WebView's internal
                        // scroll into the word bar's exit-always scroll
                        // behavior: Compose nested scroll can't see a
                        // platform WebView, so the bar listens here.
                        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                            vm.webViewScrolled(oldScrollY, scrollY)
                        }
                        // A fast fetch (or a recreated destination) can have the
                        // word ready before/while the WebView is created; render
                        // it here too (idempotent via the loaded-Uri guard).
                        vm.maybeLoadWord()
                        webView
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(maxH)
                )

            if (vm.uiStatus !is WordUiStatus.Hidden) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val s = vm.uiStatus
                        if (s is WordUiStatus.Loading) {
                            LoadingIndicator()
                        } else if (s is WordUiStatus.Error) {
                            Text(
                                text = stringResource(s.textRes),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(onClick = { vm.fetchWord() }) {
                                Text(stringResource(R.string.tryagain))
                            }
                        }
                    }
                }
            }
        }
        // Floating word action bar: a rounded, elevated pill floating above
        // the WebView, which runs behind it all the way to the screen bottom
        // so the bar can slide away and reveal the page's last lines. Its
        // vertical position follows the M3 exit-always scroll behavior:
        // heightOffset is clamped to the bar's travel distance, so the bar is
        // fully out once the page has scrolled down by its own height and
        // slides back in as soon as the user scrolls up — regardless of how
        // far down the page is.
        if (word != null) {
            FloatingWordToolbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                scrollBehavior = bottomBarScrollBehavior,
                audioEnabled = word.audio.isNotEmpty(),
                autoPlay = vm.autoPlay,
                onPlayAudio = { vm.mWord?.audio?.let { audio -> vm.playAudio(audio) } },
                onOpenInBrowser = {
                    vm.mWord?.let { w ->
                        onOpenExternal(
                            MultiDict.externalUris(w, vm.sources)
                                .map { it.toAndroidUri() }
                        )
                    }
                },
                onToggleAutoPlay = {
                    vm.autoPlay = !vm.autoPlay
                    ordboken.setAutoPlay(vm.autoPlay)
                },
                onResetZoom = { vm.resetZoom() },
                onAddCard = { vm.share() }
            )
        }
    }
}

/** The word destination's floating action bar: a rounded, elevated pill
 *  showing the pronunciation [IconButton] (disabled when [audioEnabled] is
 *  false — the word carries no audio), the add-card action, and the overflow
 *  menu rightmost, sized to wrap its content.
 *
 *  Like the M3 bottom-app-bar it replaced, the bar hides on a downward content
 *  scroll and returns on an upward one. M3's own bottom bar measures itself and
 *  sets [BottomAppBarState.heightOffsetLimit] to minus its height, so its
 *  `heightOffset` stays clamped to the bar's travel distance; a custom bar has
 *  to do the same, otherwise the limit stays 0 and the bar can never move (the
 *  unbounded `contentOffset` is not a substitute: once it is past the travel
 *  distance the bar is stuck off-screen until the page is back near the top).
 *  Position the pill from the clamped `heightOffset`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FloatingWordToolbar(
    scrollBehavior: BottomAppBarScrollBehavior,
    audioEnabled: Boolean,
    autoPlay: Boolean,
    onPlayAudio: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onResetZoom: () -> Unit,
    onAddCard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val bottomPadding = 16.dp
    val bottomPaddingPx = with(density) { bottomPadding.roundToPx() }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    Surface(
        modifier = modifier
            .onSizeChanged {
                toolbarHeight = it.height
                // M3's BottomAppBarLayout does this in its measure pass; with
                // a custom bar nothing else sets it, so without it
                // heightOffset stays pinned at 0.
                scrollBehavior.state.heightOffsetLimit =
                    -(it.height + bottomPaddingPx).toFloat()
            }
            .offset {
                val hideDistance = (toolbarHeight + bottomPaddingPx).coerceAtLeast(1)
                val slideDown = (-scrollBehavior.state.heightOffset)
                    .coerceIn(0f, hideDistance.toFloat())
                    .roundToInt()
                IntOffset(0, slideDown)
            },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayAudio, enabled = audioEnabled) {
                Icon(
                    painterResource(R.drawable.play),
                    contentDescription = stringResource(R.string.menu_play_audio)
                )
            }

            IconButton(onClick = onAddCard) {
                Icon(
                    painterResource(R.drawable.add_card),
                    contentDescription = stringResource(R.string.menu_add_card)
                )
            }

            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.menu_more)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_in_browser)) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenInBrowser()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_autoplay)) },
                        leadingIcon = {
                            Icon(
                                painterResource(
                                    if (autoPlay) R.drawable.autoplay_on
                                    else R.drawable.autoplay_off
                                ),
                                contentDescription = null,
                                tint = if (autoPlay) MaterialTheme.colorScheme.primary
                                else Color.Unspecified
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onToggleAutoPlay()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_reset_zoom)) },
                        leadingIcon = {
                            Icon(Icons.Filled.ZoomIn, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onResetZoom()
                        }
                    )
                }
            }
        }
    }
}

/** Resolves a word route entry into its [WordViewModel] and [WordScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordRoute(
    entry: NavBackStackEntry,
    ordboken: Ordboken,
    onOpenUri: (Uri, String) -> Unit,
    onOpenSources: (SearchResult) -> Unit,
    onOpenExternal: (List<Uri>) -> Unit,
    onFillSearch: (String) -> Unit,
    onReplaceSources: (SearchResult) -> Unit = onOpenSources,
    onReplaceWord: (Uri, String) -> Unit = onOpenUri,
    isSearchExpanded: () -> Boolean = { false }
) {
    val vm: WordViewModel = viewModel(entry)
    Log.i("word", "rendering word route for ${vm.mWord}")
    WordScreen(
        vm, ordboken, onOpenUri, onOpenSources, onOpenExternal, onFillSearch,
        onReplaceSources, onReplaceWord, isSearchExpanded
    )
}
