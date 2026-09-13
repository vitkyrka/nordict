package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.media.AudioManager
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.util.JsonReader
import android.util.Log
import android.view.View
import android.view.Window
import android.webkit.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.idling.CountingIdlingResource
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
import androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
import androidx.webkit.WebViewFeature
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.gson.Gson
import se.whitchurch.nordict.OrdbokenContract.FavoritesEntry
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry
import java.io.StringReader
import java.net.URLDecoder
import java.util.*


sealed interface WordUiStatus {
    object Loading : WordUiStatus
    object Hidden : WordUiStatus
    data class Error(val textRes: Int) : WordUiStatus
}

class WordActivity : androidx.appcompat.app.AppCompatActivity() {
    internal val loadResource: CountingIdlingResource = CountingIdlingResource("search")
    private var mWebView: WebView? = null
    private var mOrdboken: Ordboken? = null
    private var mUrl: Uri? = null
    private var mGotStarred: Boolean = false
    private var mPageFinished: Boolean = false
    private var mResetZoomNextPause = false
    private var mFilterName: String? = null
    private var mWordList: WordList = WordList(position = -1)

    private var mWord: Word? by mutableStateOf(null)
    private var mStarred: Boolean by mutableStateOf(false)
    private var autoPlay: Boolean by mutableStateOf(false)
    private var pinToViewport: Boolean by mutableStateOf(false)
    private var webViewVisible: Boolean by mutableStateOf(false)
    internal var queryText: String by mutableStateOf("")
    private var uiStatus: WordUiStatus by mutableStateOf(WordUiStatus.Loading)
    private val searchFocus = FocusRequester()

    @SuppressLint("AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mOrdboken = Ordboken.getInstance(this)

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
        volumeControlStream = AudioManager.STREAM_MUSIC

        autoPlay = getPreferences(Context.MODE_PRIVATE)?.getBoolean("autoPlay", false) ?: false

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WordScreen()
                }
            }
        }

        val intent = intent
        val title = intent.getStringExtra("title")
        if (title != null) {
            setTitle(title)
        }

        val url = if (intent.data != null) {
            intent.data!!
        } else {
            Uri.parse("https://svenska.se/so/?id=18788&ref=lnr176698")
        }

        mUrl = url
        fetchWord()
    }

    private fun createWebView(context: Context): WebView {
        val webView = WebView(context)
        mWebView = webView
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
            when (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> {
                    WebSettingsCompat.setForceDark(webView.settings, FORCE_DARK_ON)
                }
                Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_UNDEFINED -> {
                    WebSettingsCompat.setForceDark(webView.settings, FORCE_DARK_OFF)
                }
                else -> {
                }
            }
        }

        webView.setInitialScale(mOrdboken!!.mPrefs.getInt("scale", 0))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.contains("/search/")) {
                    val word = url.substring(url.indexOf("ch/") + 3)
                    loadResource.increment()
                    SearchLinkTask().execute(URLDecoder.decode(word, "UTF-8"))
                    return true
                } else if (url.contains("https://ordnet.dk/ddo/ordbog?entry_id=")) {
                    val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                        data = Uri.parse(url)
                    }
                    startActivity(intent)
                } else if (url.contains("https://ordnet.dk/ddo/ordbog?subentry_id=")) {
                    val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                        data = Uri.parse(url)
                    }
                    startActivity(intent)
                } else if (url.contains("https://ordnet.dk/korpusdk/qconc")) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(browserIntent)
                } else if (url.contains("/so/?id=")) {
                    val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                        data = Uri.parse(url.replace("file:///", "https://svenska.se/"))
                    }
                    startActivity(intent)
                } else if (url.contains("mp3")) {
                    setProgressBarIndeterminateVisibility(true)
                    playAudio(url)
                } else {
                    val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                        data = Uri.parse(url)
                    }
                    startActivity(intent)
                }

                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!mPageFinished) {
                    webView.visibility = View.VISIBLE
                    webViewVisible = true
                    uiStatus = WordUiStatus.Hidden

                    if (autoPlay) mWord?.audio?.let { audio ->
                        playAudio(audio)
                    }

                    loadResource.decrement()
                }

                mPageFinished = true
                updateStar()
            }
        }

        class OrdbokenJsObject {
            @JavascriptInterface
            fun toggleStar() {
                StarToggleTask().execute()
            }
        }
        webView.addJavascriptInterface(OrdbokenJsObject(), "ordboken")

        return webView
    }

    @Composable
    fun WordScreen() {
        val ordboken = mOrdboken!!
        val word = mWord

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar: back + search + word actions
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(searchFocus),
                        singleLine = true,
                        placeholder = { Text(getString(R.string.search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch(queryText) })
                    )
                    IconButton(onClick = { submitSearch(queryText) }) {
                        Icon(Icons.Default.Search, contentDescription = getString(R.string.menu_search))
                    }
                    IconButton(onClick = { StarToggleTask().execute() }) {
                        Icon(
                            if (mStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = getString(
                                if (mStarred) R.string.remove_bookmark else R.string.add_bookmark
                            ),
                            tint = if (mStarred) Color(0xFFFBC02D) else Color.Unspecified
                        )
                    }
                    IconButton(onClick = {
                        mWord?.uri?.let { url ->
                            val browserIntent = Intent(Intent.ACTION_VIEW, url.toAndroidUri())
                            startActivity(browserIntent)
                        }
                    }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = getString(R.string.open_in_browser))
                    }
                    IconButton(onClick = {
                        // The WebView zoom is only persisted through the saved
                        // "scale" preference that setInitialScale() applies on
                        // the next page load (there is no reliable API to read
                        // or set the zoom of the currently displayed page).
                        // Reset it to the default so the next word opens at
                        // the default zoom.
                        mResetZoomNextPause = true
                        mOrdboken?.mPrefs?.edit()?.putInt("scale", 0)?.apply()
                        getPreferences(Context.MODE_PRIVATE)?.let { pref ->
                            with(pref.edit()) {
                                putInt("scale", 0)
                                commit()
                            }
                        }
                    }) {
                        Icon(Icons.Filled.ZoomIn, contentDescription = getString(R.string.menu_reset_zoom))
                    }
                }
            }

            DictionaryNav(ordboken = ordboken, modifier = Modifier.padding(horizontal = 8.dp))

            HorizontalDivider()

            // Content area
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val maxH = maxHeight
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState, enabled = !pinToViewport)
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
                                            Ordboken.startWordActivity(
                                                this@WordActivity,
                                                "",
                                                homograph.uri.toAndroidUri()
                                            )
                                        }
                                        .padding(start = 20.dp, top = 10.dp, end = 0.dp, bottom = 10.dp)
                                )
                            }
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            val webView = createWebView(ctx)
                            // A fast word fetch (or a Robolectric test driving the
                            // looper) can finish before the first composition
                            // creates the WebView; load the pending word then.
                            if (!mPageFinished && mWord != null) {
                                loadWebView(mWord!!)
                            }
                            webView
                        },
                        modifier = if (pinToViewport) {
                            Modifier
                                .fillMaxWidth()
                                .height(maxH)
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    )
                }

                if (uiStatus !is WordUiStatus.Hidden) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val s = uiStatus
                            if (s is WordUiStatus.Loading) {
                                LoadingIndicator(modifier = Modifier.padding(bottom = 16.dp))
                                Text(getString(R.string.loading), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            } else if (s is WordUiStatus.Error) {
                                Text(
                                    text = getString(s.textRes),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                                Button(onClick = { fetchWord() }) {
                                    Text(getString(R.string.tryagain))
                                }
                            }
                        }
                    }
                }
            }

            // Bottom action bar
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        autoPlay = !autoPlay
                        getPreferences(Context.MODE_PRIVATE)?.let { pref ->
                            with(pref.edit()) {
                                putBoolean("autoPlay", autoPlay)
                                commit()
                            }
                        }
                    }) {
                        Icon(
                            painterResource(if (autoPlay) R.drawable.autoplay_on else R.drawable.autoplay_off),
                            contentDescription = null,
                            tint = if (autoPlay) Color(0xFF4A90D9) else Color.Unspecified
                        )
                    }
                    IconButton(onClick = { share() }) {
                        Icon(
                            painterResource(R.drawable.add_card),
                            contentDescription = getString(R.string.menu_share)
                        )
                    }
                    IconButton(onClick = {
                        mWord?.audio?.let { audio -> playAudio(audio) }
                    }) {
                        Icon(
                            painterResource(R.drawable.play),
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }

    fun fetchWord(forceUrl: Boolean = false) {
        uiStatus = WordUiStatus.Loading
        loadResource.increment()

        WordTask().execute(Triple(mUrl!!, mWordList, forceUrl))
    }

    private fun loadWebView(word: Word) {
        if (mWebView == null) return
        if (word.renderAsJson) {
            // JSON pages are taller than the screen; pin the WebView to the
            // viewport so it scrolls internally and #hom-N anchors land on
            // their headings.
            pinToViewport = true

            val gson = Gson()
            val json = gson.toJson(word)
            val template = assets.open("word_template.html").bufferedReader().use { it.readText() }
            val html = template.replace("</body>", """
                <script>
                    loadWord($json);
                </script>
                </body>
            """.trimIndent())

            mWebView!!.loadDataWithBaseURL(
                "file:///android_asset/", html,
                "text/html", "UTF-8", null
            )
            return
        }

        // Legacy dictionaries render original HTML that the outer scroll view
        // scrolls, so let the WebView size to its content again.
        pinToViewport = false

        val text = word.getPage()
        val footer = ("<script src='file:///android_asset/jquery.min.js'></script>"
                + "<link rel='stylesheet' type='text/css' href='file:///android_asset/word.css'>"
                + "<script src='file:///android_asset/word.js'></script>")

        val builder = StringBuilder(text.replace("/speaker.png", "https://svenska.se/speaker.png"))

        builder.append(footer)

        mWebView!!.loadDataWithBaseURL(
            word.baseUrl, builder.toString(),
            "text/html", "UTF-8", null
        )
    }

    private fun updateStar() {
        mStarred = mStarred
    }

    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        val intent = Intent(Intent.ACTION_SEARCH)
            .setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(SearchManager.QUERY, query)
        startActivity(intent)
    }

    private inner class SearchLinkTask : AsyncTask<String, Void, SearchResult>() {
        override fun doInBackground(vararg params: String): SearchResult {
            val q = params[0]
            val results = mOrdboken?.currentDictionary?.search(q) ?: return SearchResult(q)
            return ExactMatch.resolve(q, results) ?: SearchResult(q)
        }

        override fun onPostExecute(result: SearchResult) {
            if (result.uri.host == "fake") {
                showSuggestions(result.mTitle)
            } else {
                val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                    data = result.uri.toAndroidUri()
                }
                startActivity(intent)
            }

            loadResource.decrement()
        }
    }

    // Triggered when the user taps a dictionary button for a different
    // dictionary of the same language: search that dictionary for the current
    // entry and, when there is a unique exact match, jump straight to it.
    // Otherwise fill the search bar and show the new dictionary's suggestions.
    private fun maybeSwitchDict() {
        val ordboken = mOrdboken ?: return
        val word = mWord ?: return

        val newDict = ordboken.currentDictionary
        val wordDict = ordboken.dictMap[word.dict] ?: return

        // A language switch rebuilds the dictionary row and selects that
        // language's default dictionary; don't cross-search languages.
        if (newDict.lang != wordDict.lang) return

        loadResource.increment()
        SwitchDictTask().execute(word.searchHeadword)
    }

    private inner class SwitchDictTask : AsyncTask<String, Void, SearchResult>() {
        override fun doInBackground(vararg params: String): SearchResult {
            val q = params[0]
            val results = mOrdboken?.currentDictionary?.search(q) ?: return SearchResult(q)
            return ExactMatch.resolve(q, results) ?: SearchResult(q)
        }

        override fun onPostExecute(result: SearchResult) {
            if (result.uri.host == "fake") {
                showSuggestions(result.mTitle)
            } else {
                Ordboken.startWordActivity(this@WordActivity, result.mTitle, result.uri.toAndroidUri())
            }

            loadResource.decrement()
        }
    }

    private fun showSuggestions(query: String) {
        queryText = query
        searchFocus.requestFocus()
    }

    private inner class WordTask :
        AsyncTask<Triple<Uri, WordList, Boolean>, Void, Pair<Word?, WordList>>() {
        override fun doInBackground(vararg params: Triple<Uri, WordList, Boolean>): Pair<Word?, WordList> {
            val wordList = params[0].second
            val word: Word? = mOrdboken!!.getWord(params[0].first)

            return Pair(word, wordList)
        }

        override fun onPostExecute(result: Pair<Word?, WordList>) {
            val word = result.first
            val wordList = result.second

            mWord = word
            mOrdboken!!.currentWord = mWord
            mWordList = wordList

            if (word == null) {
                uiStatus = if (!mOrdboken!!.isOnline) {
                    WordUiStatus.Error(R.string.error_offline)
                } else {
                    WordUiStatus.Error(R.string.error_word)
                }
                loadResource.decrement()
                return
            }

            title = word.toString()
            Log.i("word", word.toString())

            loadWebView(word)
            title = word.mTitle
            mPageFinished = false
            webViewVisible = false

            StarUpdateTask().execute()
            HistorySaveTask().execute()
        }
    }

    private fun playAudio(urls: java.util.ArrayList<String>) {
        if (urls.size == 0) {
            return
        }

        val player: ExoPlayer = ExoPlayer.Builder(this).build()
        urls.forEach { player.addMediaItem(MediaItem.fromUri(it)) }
        player.prepare()
        player.play()
    }

    private fun playAudio(url: String) {
        playAudio(java.util.ArrayList(listOf(url)))
    }

    private inner class HistorySaveTask : AsyncTask<Void, Void, Void>() {
        override fun doInBackground(vararg params: Void): Void? {
            val dbHelper = OrdbokenDbHelper(this@WordActivity)
            val db = dbHelper.writableDatabase
            val values = ContentValues()

            values.put(HistoryEntry.COLUMN_NAME_TITLE, mWord!!.mTitle)
            values.put(HistoryEntry.COLUMN_NAME_DICT, mWord!!.dict)
            values.put(HistoryEntry.COLUMN_NAME_SUMMARY, mWord!!.summary)
            values.put(HistoryEntry.COLUMN_NAME_URL, mWord!!.uri.toString())
            values.put(HistoryEntry.COLUMN_NAME_DATE, Date().time)

            db.insert(HistoryEntry.TABLE_NAME, "null", values)
            db.close()

            return null
        }
    }

    private abstract inner class StarTask : AsyncTask<Void, Void, Boolean>() {
        protected val db: SQLiteDatabase
            get() {
                val dbHelper = OrdbokenDbHelper(this@WordActivity)
                return dbHelper.writableDatabase
            }

        protected fun isStarred(db: SQLiteDatabase): Boolean {
            val cursor = db.query(
                FavoritesEntry.TABLE_NAME, null,
                FavoritesEntry.COLUMN_NAME_URL + "=?",
                arrayOf(mWord!!.uri.toString()), null, null, null, "1"
            )
            val count = cursor.count

            cursor.close()
            return count > 0
        }

        override fun onPostExecute(starred: Boolean?) {
            mStarred = starred!!
            mGotStarred = true
            updateStar()
        }
    }

    private inner class StarUpdateTask : StarTask() {
        override fun doInBackground(vararg params: Void): Boolean? {
            val db = db
            val starred = isStarred(db)

            db.close()

            return starred
        }
    }

    private inner class StarToggleTask : StarTask() {
        override fun doInBackground(vararg params: Void): Boolean? {
            val db = db
            val starred = isStarred(db)

            if (starred) {
                db.delete(
                    FavoritesEntry.TABLE_NAME,
                    FavoritesEntry.COLUMN_NAME_URL + "=?",
                    arrayOf(mWord!!.uri.toString())
                )
            } else {
                val values = ContentValues()

                values.put(FavoritesEntry.COLUMN_NAME_TITLE, mWord!!.mTitle)
                values.put(HistoryEntry.COLUMN_NAME_SUMMARY, mWord!!.summary)
                values.put(FavoritesEntry.COLUMN_NAME_URL, mWord!!.uri.toString())

                db.insert(FavoritesEntry.TABLE_NAME, "null", values)
            }

            db.close()

            return !starred
        }
    }

    private fun share() {
        val word = mWord ?: return

        mWebView?.evaluateJavascript("getCSS()", ValueCallback {
            val reader = JsonReader(StringReader(it))

            reader.isLenient = true

            val css = reader.nextString()

            mOrdboken?.currentCss = css

            val intent = Intent(this, CardActivity::class.java).apply {
                if (mFilterName != null) {
                    putExtra("deckName", "Nordict - ${word.dict} - $mFilterName")
                } else {
                    putExtra("deckName", "Nordict - ${word.dict}")
                }
            }
            startActivity(intent)
        })
    }

    override fun onResume() {
        super.onResume()
        mOrdboken!!.currentWord = mWord
        mOrdboken!!.onResume(this)
        mOrdboken!!.onDictChanged = { maybeSwitchDict() }

        if (mWord != null) {
            StarUpdateTask().execute()
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)

        if (mWord != null) {
            mOrdboken!!.setLastView(Ordboken.Where.WORD, mWord!!.uri.toString())
        }

        val ed = mOrdboken!!.prefsEditor

        // If the WebView was not made visible, getScale() does not
        // return the initalScale, but the default one.
        if (mResetZoomNextPause) {
            // The zoom was just reset: keep the cleared scale instead of
            // re-saving the still-zoomed page's scale, or the next open
            // would apply the large zoom again.
            mResetZoomNextPause = false
        } else if (webViewVisible) {
            // getScale() is supposed to be deprecated, but its replacement
            // onScaleChanged() doesn't get called when zooming using pinch.
            val scale = (mWebView!!.scale * 100).toInt()

            ed.putInt("scale", scale)
        }

        ed.commit()
    }
}