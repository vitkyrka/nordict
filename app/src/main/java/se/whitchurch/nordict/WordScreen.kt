package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.content.ContentValues
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.test.espresso.idling.CountingIdlingResource
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
import androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
import androidx.webkit.WebViewFeature
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry
import java.io.StringReader
import java.net.URLDecoder
import java.util.Date


sealed interface WordUiStatus {
    object Loading : WordUiStatus
    object Hidden : WordUiStatus
    data class Error(val textRes: Int) : WordUiStatus
}

/**
 * Loads and renders one word destination. The ViewModel is scoped to the word
 * NavBackStackEntry, so each pushed word keeps its own state exactly like the
 * old stacked WordActivities. Owns the WebView lifecycle, the audio player and
 * the SQLite history writes; navigation side effects flow out through the
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

    var mWord: Word? by mutableStateOf(null)
    var autoPlay: Boolean by mutableStateOf(false)
    var pinToViewport: Boolean by mutableStateOf(false)
    var webViewVisible: Boolean by mutableStateOf(false)
    var pageFinished: Boolean by mutableStateOf(false)
    var uiStatus: WordUiStatus by mutableStateOf(WordUiStatus.Loading)
    var webView: WebView? = null

    // The word action bar's exit-always scroll behavior, wired by WordScreen
    // on every composition. It owns the nested-scroll connection that the bar
    // listens to; the pinned (JSON) WebView feeds it through webViewScrolled,
    // while legacy words scroll the outer Compose column past it directly.
    var bottomBarScrollBehavior: BottomAppBarScrollBehavior? = null

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

    private val player: ExoPlayer by lazy {
        ExoPlayer.Builder(getApplication()).build()
    }

    init {
        autoPlay = ordboken.mPrefs.getBoolean("autoPlay", false)
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
    fun onLeave() {
        val ed = ordboken.prefsEditor

        // If the WebView was never made visible, getScale() returns the default
        // scale instead of the initialScale.
        if (mResetZoomNextPause) {
            mResetZoomNextPause = false
        } else if (webViewVisible) {
            val scale = ((webView?.scale ?: 1f) * 100).toInt()
            ed.putInt("scale", scale)
        }

        ed.commit()
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
        webView.setInitialScale(ordboken.mPrefs.getInt("scale", 0))

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
            }
        }

        return webView
    }

    /**
     * Feeds the word action bar's exit-always scroll behavior from the pinned
     * (JSON) WebView's internal scroll [WebView.OnScrollChangeListener]. The
     * Compose nested-scroll chain cannot see a platform WebView's scrolling,
     * so this bridges it to the same connection the legacy words drive through
     * their outer Compose scrollable: scrolling the page down collapses the
     * bar, scrolling back up brings it back. A positive delta (content moved
     * down) folds into a negative consumed offset, matching the direction the
     * M3 behavior expects to collapse.
     */
    fun webViewScrolled(oldScrollY: Int, scrollY: Int) {
        val behavior = bottomBarScrollBehavior ?: return
        if (!pinToViewport) return
        val delta = scrollY - oldScrollY
        if (delta == 0) return
        behavior.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -delta.toFloat()),
            available = Offset.Zero,
            source = NestedScrollSource.Drag,
        )
    }

    fun loadWebView(word: Word) {
        val webView = this.webView ?: return
        if (word.renderAsJson) {
            pinToViewport = true

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
            return
        }

        pinToViewport = false

        val text = word.getPage()
        val footer = ("<script src='file:///android_asset/jquery.min.js'></script>"
                + "<link rel='stylesheet' type='text/css' href='file:///android_asset/word.css'>"
                + "<script src='file:///android_asset/word.js'></script>")

        val builder = StringBuilder(text.replace("/speaker.png", "https://svenska.se/speaker.png"))
        builder.append(footer)

        webView.loadDataWithBaseURL(
            word.baseUrl, builder.toString(),
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
            var combined: List<CombSource>? = null
            var exact: SearchResult? = null
            withContext(Dispatchers.IO) {
                if (ordboken.activeDicts.isNotEmpty()) {
                    val sources = MultiDict.resolveExact(ordboken.activeDicts, query)
                    combined = sources.takeIf { it.isNotEmpty() }
                    if (combined == null) exact = SearchResult(query)
                } else {
                    val results = ordboken.currentDictionary.search(query)
                    exact = ExactMatch.resolve(query, results) ?: SearchResult(query)
                }
            }
            when {
                combined != null ->
                    onOpenSources?.invoke(
                        SearchResult(
                            mTitle = query,
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val dbHelper = OrdbokenDbHelper(getApplication())
                val db = dbHelper.writableDatabase
                val values = ContentValues()
                values.put(HistoryEntry.COLUMN_NAME_TITLE, word.mTitle)
                values.put(HistoryEntry.COLUMN_NAME_DICT, word.dict)
                values.put(HistoryEntry.COLUMN_NAME_SUMMARY, word.summary)
                values.put(HistoryEntry.COLUMN_NAME_URL, word.uri.toString())
                values.put(HistoryEntry.COLUMN_NAME_DATE, Date().time)
                db.insert(HistoryEntry.TABLE_NAME, "null", values)
                db.close()
            }
        }
    }

    // ---------- Audio / share ----------

    fun playAudio(urls: java.util.ArrayList<String>) {
        if (urls.isEmpty()) return
        urls.forEach { player.addMediaItem(MediaItem.fromUri(it)) }
        player.prepare()
        player.play()
    }

    fun playAudio(url: String) {
        playAudio(java.util.ArrayList(listOf(url)))
    }

    fun resetZoom() {
        mResetZoomNextPause = true
        ordboken.mPrefs.edit().putInt("scale", 0).apply()
    }

    fun share() {
        val word = mWord ?: return
        val webView = this.webView ?: return
        webView.evaluateJavascript("getCSS()", android.webkit.ValueCallback { json ->
            val reader = JsonReader(StringReader(json))
            reader.isLenient = true
            ordboken.currentCss = reader.nextString()

            val intent = Intent(getApplication(), CardActivity::class.java).apply {
                putExtra("deckName", "Nordict - ${word.dict}")
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
    onReplaceWord: (Uri, String) -> Unit
) {
    vm.onOpenUri = onOpenUri
    vm.onOpenSources = onOpenSources
    vm.onOpenExternal = onOpenExternal
    vm.onFillSearch = onFillSearch
    vm.onReplaceSources = onReplaceSources
    vm.onReplaceWord = onReplaceWord

    val word = vm.mWord
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The docked word action bar uses the M3 exit-always scroll behavior: it
    // collapses when the word content is scrolled down and reappears on a
    // scroll back up. The connection lives on the screen's root (as the M3
    // wiring does); the pinned JSON WebView feeds it through vm.webViewScrolled
    // since its internal scroll is invisible to the Compose nested-scroll
    // chain, while legacy words scroll the outer Compose column past it.
    val bottomBarScrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()
    vm.bottomBarScrollBehavior = bottomBarScrollBehavior

    // Load the word page as soon as the fetch lands. The AndroidView factory
    // can run before the coroutine finishes, so a fast fetch (or a retry)
    // re-triggers this load after the WebView exists. Also keyed on the
    // WebView instance so a fresh WebView created after the destination was
    // pushed over by another word re-renders the already-fetched word.
    LaunchedEffect(word, vm.webView) { vm.maybeLoadWord() }

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
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (ordboken.onDictChanged === myHook) ordboken.onDictChanged = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxH = maxHeight
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState, enabled = !vm.pinToViewport)
            ) {
                // Legacy homograph strip (JSON dictionaries render their own).
                if (word != null && !word.renderAsJson && word.mHomographs.isNotEmpty()) {
                    Column {
                        word.mHomographs.forEach { homograph ->
                            val isCurrent = homograph.uri == word.uri
                            Text(
                                text = (if (isCurrent) "▶ " else "  ") + homograph.mSummary,
                                fontSize = 15.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onOpenUri(homograph.uri.toAndroidUri(), homograph.mSummary)
                                    }
                                    .padding(start = 20.dp, top = 10.dp, end = 0.dp, bottom = 10.dp)
                            )
                        }
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        val webView = vm.createWebView(ctx)
                        // Bridge the pinned (JSON) WebView's internal
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
                    modifier = if (vm.pinToViewport) {
                        Modifier
                            .fillMaxWidth()
                            .height(maxH)
                    } else {
                        Modifier.fillMaxWidth()
                    }
                )
            }

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
                            LoadingIndicator(modifier = Modifier.padding(bottom = 16.dp))
                            Text(
                                context.getString(R.string.loading),
                                textAlign = TextAlign.Center
                            )
                        } else if (s is WordUiStatus.Error) {
                            Text(
                                text = context.getString(s.textRes),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Button(onClick = { vm.fetchWord() }) {
                                Text(context.getString(R.string.tryagain))
                            }
                        }
                    }
                }
            }
        }
        // Docked word action bar: an M3 bottom app bar overlaid on the
        // WebView, which runs behind it all the way to the screen bottom so
        // the bar can collapse away and reveal the page's last lines.
        // exitAlwaysScrollBehavior hides the bar when the content is scrolled
        // down and brings it back on a scroll up; the pinned (JSON) WebView's
        // scroll feeds it through vm.webViewScrolled, the legacy words through
        // the outer Compose scroll. The Anki-card FAB is folded in as a
        // regular action.
        if (word != null) {
            BottomAppBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                scrollBehavior = bottomBarScrollBehavior,
                content = {
                    IconButton(onClick = {
                        vm.mWord?.audio?.let { audio -> vm.playAudio(audio) }
                    }) {
                        Icon(
                            painterResource(R.drawable.play),
                            contentDescription = context.getString(R.string.menu_play_audio)
                        )
                    }
                    Box {
                        var menuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = context.getString(R.string.menu_more)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.open_in_browser)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                                },
                                onClick = {
                                    vm.mWord?.let { w ->
                                        onOpenExternal(
                                            MultiDict.externalUris(w, vm.sources)
                                                .map { it.toAndroidUri() }
                                        )
                                    }
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.menu_autoplay)) },
                                leadingIcon = {
                                    Icon(
                                        painterResource(
                                            if (vm.autoPlay) R.drawable.autoplay_on
                                            else R.drawable.autoplay_off
                                        ),
                                        contentDescription = null,
                                        tint = if (vm.autoPlay) MaterialTheme.colorScheme.primary
                                        else Color.Unspecified
                                    )
                                },
                                onClick = {
                                    vm.autoPlay = !vm.autoPlay
                                    ordboken.mPrefs.edit()
                                        .putBoolean("autoPlay", vm.autoPlay).apply()
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.menu_reset_zoom)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.ZoomIn, contentDescription = null)
                                },
                                onClick = {
                                    vm.resetZoom()
                                    menuExpanded = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    FilledTonalIconButton(
                        onClick = { vm.share() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(
                            painterResource(R.drawable.add_card),
                            contentDescription = context.getString(R.string.menu_add_card)
                        )
                    }
                }
            )
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
    onReplaceWord: (Uri, String) -> Unit = onOpenUri
) {
    val vm: WordViewModel = viewModel(entry)
    Log.i("word", "rendering word route for ${vm.mWord}")
    WordScreen(
        vm, ordboken, onOpenUri, onOpenSources, onOpenExternal, onFillSearch,
        onReplaceSources, onReplaceWord
    )
}