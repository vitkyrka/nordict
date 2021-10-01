package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.util.JsonReader
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.test.espresso.idling.CountingIdlingResource
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
import androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomappbar.BottomAppBar
import se.whitchurch.nordict.OrdbokenContract.FavoritesEntry
import se.whitchurch.nordict.OrdbokenContract.HistoryEntry
import java.io.StringReader
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.*


class WordActivity : AppCompatActivity() {
    internal val loadResource: CountingIdlingResource = CountingIdlingResource("search")
    private var mWebView: WebView? = null
    private var mOrdboken: Ordboken? = null
    private var mWord: Word? = null
    private var mUrl: Uri? = null
    private var mProgressBar: ProgressBar? = null
    private var mStatusText: TextView? = null
    private var mStatusLayout: LinearLayout? = null
    private var mRetryButton: Button? = null
    private var mSearchView: SearchView? = null
    private var mStarred: Boolean = false
    private var mGotStarred: Boolean = false
    private var mPageFinished: Boolean = false
    private var autoPlay: Boolean = false
    private var mFilterName: String? = null
    private var mWordList: WordList = WordList(position = -1)

    @SuppressLint("AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mOrdboken = Ordboken.getInstance(this)

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS)
        setContentView(R.layout.activity_word)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val actionBar = supportActionBar
        actionBar!!.displayOptions = (ActionBar.DISPLAY_SHOW_CUSTOM or ActionBar.DISPLAY_SHOW_HOME
                or ActionBar.DISPLAY_HOME_AS_UP)
        actionBar.setCustomView(R.layout.actionbar)
        actionBar.setDisplayHomeAsUpEnabled(true)

        val bottomBar = findViewById<BottomAppBar>(R.id.bottom_app_bar)
        bottomBar.replaceMenu(R.menu.bottom_word)

        autoPlay = getPreferences(Context.MODE_PRIVATE)?.getBoolean("autoPlay", false) ?: false
        bottomBar.menu.findItem(R.id.menu_autoplay).apply {
            isChecked = autoPlay
            setIcon(if (autoPlay) R.drawable.autoplay_on else R.drawable.autoplay_off)
        }

        bottomBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_share -> {
                    share()
                    true
                }
                R.id.menu_play -> {
                    mWord?.audio?.let { audio -> playAudio(audio) }
                    true
                }
                R.id.menu_autoplay -> {
                    it.isChecked = !it.isChecked
                    autoPlay = it.isChecked
                    it.setIcon(if (autoPlay) R.drawable.autoplay_on else R.drawable.autoplay_off)

                    getPreferences(Context.MODE_PRIVATE)?.let { pref ->
                        with(pref.edit()) {
                            putBoolean("autoPlay", autoPlay)
                            commit()
                        }
                    }
                    true
                }
                else -> false
            }
        }


        val webView = findViewById<View>(R.id.webView) as WebView ?: return
        mWebView = webView
        mWebView!!.webChromeClient = WebChromeClient()
        val settings = mWebView!!.settings.apply {
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

        mWebView!!.setInitialScale(mOrdboken!!.mPrefs.getInt("scale", 0))
        mWebView!!.webViewClient = object : WebViewClient() {
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
                    webView.setBackgroundColor(Color.GREEN)
                    webView.visibility = View.VISIBLE
                    mStatusLayout!!.visibility = View.INVISIBLE

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
        mWebView!!.addJavascriptInterface(OrdbokenJsObject(), "ordboken")

        mStarred = false
        mProgressBar = findViewById<View>(R.id.word_progress) as ProgressBar
        mStatusText = findViewById<View>(R.id.word_status) as TextView
        mStatusLayout = findViewById<View>(R.id.word_status_layout) as LinearLayout
        mRetryButton = findViewById<View>(R.id.word_retry) as Button

        val intent = intent

        val title = intent.getStringExtra("title")
        if (title != null) {
            setTitle(title)
        }

        val url = if (intent != null && intent.data != null) {
            intent.data
        } else {
            Uri.parse("https://svenska.se/so/?id=18788&ref=lnr176698")
        }

        mUrl = url
        fetchWord()
    }

    fun fetchWord(forceUrl: Boolean = false) {
        mProgressBar!!.visibility = View.VISIBLE
        mStatusText!!.setText(R.string.loading)
        mRetryButton!!.visibility = View.GONE
        loadResource.increment()

        WordTask().execute(Triple(mUrl!!, mWordList, forceUrl))
    }

    private fun loadHomographs(word: Word) {
        val linearLayout = findViewById<LinearLayout>(R.id.linear_layout)

        linearLayout.removeAllViews()

        if (word.mHomographs.size == 0) {
            return
        }

        var pos = 0
        for (homograph in word.mHomographs) {
            val text = TextView(this)

            text.setPadding(20, 10, 0, 10)
            text.textSize = 19f

            if (homograph.uri == word.uri) {
                text.text = "☑ " + homograph.mTitle + " " + homograph.mSummary
                text.setTypeface(null, Typeface.BOLD)
            } else {
                text.text = "☐ " + homograph.mTitle + " " + homograph.mSummary
            }

            text.setOnClickListener {
                Ordboken.startWordActivity(this, "", homograph.uri)
            }

            linearLayout.addView(text, pos)
            pos++
        }
    }

    private fun loadWebView(word: Word) {
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
        val on = if (mStarred) 1 else 0

        if (!mGotStarred || !mPageFinished) {
            return
        }

        // mWebView.loadUrl("javascript:setStar(" + on.toString() + ")");
    }

    private inner class SearchLinkTask : AsyncTask<String, Void, SearchResult>() {
        override fun doInBackground(vararg params: String): SearchResult {
            val q = params[0]
            val fake = SearchResult(q)
            val results = mOrdboken?.currentDictionary?.search(q) ?: return fake

            if (results.isEmpty()) {
                return fake
            }

            val first = results[0]
            if (first.mTitle != q) {
                return fake
            }

            if (results.size == 1 || results[1].mTitle != first.mTitle) {
                return first
            }

            return fake
        }

        override fun onPostExecute(result: SearchResult) {
            if (result.uri.host == "fake") {
                try {
                    mSearchView!!.setQuery(result.mTitle, false)
                    mSearchView!!.isIconified = false
                    mSearchView!!.requestFocusFromTouch()
                } catch (e: UnsupportedEncodingException) {
                    // Should not happen.
                }
            } else {
                val intent = Intent(this@WordActivity, WordActivity::class.java).apply {
                    data = result.uri
                }
                startActivity(intent)
            }

            loadResource.decrement()
        }
    }

    private inner class WordTask :
        AsyncTask<Triple<Uri, WordList, Boolean>, Void, Pair<Word?, WordList>>() {
        override fun doInBackground(vararg params: Triple<Uri, WordList, Boolean>): Pair<Word?, WordList> {
            val wordList = params[0].second
            var word: Word? = null

            word = mOrdboken!!.getWord(params[0].first)

            return Pair(word, wordList)
        }

        override fun onPostExecute(result: Pair<Word?, WordList>) {
            val word = result.first
            val wordList = result.second

            mWord = word
            mOrdboken!!.currentWord = mWord
            mWordList = wordList

            if (word == null) {
                mProgressBar!!.visibility = View.GONE
                if (!mOrdboken!!.isOnline) {
                    mStatusText!!.setText(R.string.error_offline)
                } else {
                    mStatusText!!.setText(R.string.error_word)
                }

                mRetryButton!!.visibility = View.VISIBLE
                loadResource.decrement()
                return
            }

            title = word.toString()
            Log.i("word", word.toString())

            loadHomographs(word)
            loadWebView(word)
            title = word.mTitle

            StarUpdateTask().execute()
            HistorySaveTask().execute()
        }
    }

    private fun playAudio(urls: ArrayList<String>) {
        if (urls.size == 0) {
            return
        }

        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)

        try {
            mediaPlayer.setDataSource(urls[0])
        } catch (e: Exception) {
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            return
        }

        var current = 0
        mediaPlayer.setOnCompletionListener {
            current += 1
            if (urls.size <= current) {
                return@setOnCompletionListener
            }

            it.reset()
            it.setDataSource(urls[current])
            it.prepareAsync()
        }

        mediaPlayer.setOnPreparedListener { mp ->
            mp.start()
        }

        mediaPlayer.setOnErrorListener { mp, what, extra ->
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            false
        }

        mediaPlayer.prepareAsync()
    }

    private fun playAudio(url: String) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)

        try {
            mediaPlayer.setDataSource(url)
        } catch (e: Exception) {
            setProgressBarIndeterminateVisibility(false)
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            return
        }

        mediaPlayer.setOnPreparedListener { mp ->
            setProgressBarIndeterminateVisibility(false)
            mp.start()
        }

        mediaPlayer.setOnErrorListener { mp, what, extra ->
            setProgressBarIndeterminateVisibility(false)
            Toast.makeText(applicationContext, R.string.error_audio, Toast.LENGTH_SHORT)
                .show()
            false
        }

        mediaPlayer.prepareAsync()
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
            invalidateOptionsMenu()
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

        if (mWord != null) {
            StarUpdateTask().execute()
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)

        // mOrdboken!!.currentWord = null
        if (mWord != null) {
            mOrdboken!!.setLastView(Ordboken.Where.WORD, mWord!!.uri.toString())
        }

        val ed = mOrdboken!!.prefsEditor

        // If the WebView was not made visible, getScale() does not
        // return the initalScale, but the default one.
        if (mWebView!!.visibility == View.VISIBLE) {
            // getScale() is supposed to be deprecated, but its replacement
            // onScaleChanged() doesn't get called when zooming using pinch.
            val scale = (mWebView!!.scale * 100).toInt()

            ed.putInt("scale", scale)
        }

        ed.commit()
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val star = menu.findItem(R.id.menu_star)

        if (mStarred) {
            star.setIcon(R.drawable.ic_action_important)
            star.title = getString(R.string.remove_bookmark)
        } else {
            star.setIcon(R.drawable.ic_action_not_important)
            star.title = getString(R.string.add_bookmark)
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menuInflater.inflate(R.menu.word, menu)

        mSearchView = mOrdboken!!.initSearchView(this, menu, null, false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (mOrdboken!!.onOptionsItemSelected(this, item)) {
            return true
        }

        if (item.itemId == R.id.menu_star) {
            StarToggleTask().execute()
        }

        return super.onOptionsItemSelected(item)
    }
}
