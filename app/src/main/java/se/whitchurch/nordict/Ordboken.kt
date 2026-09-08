package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.app.SearchManager
import android.app.TaskStackBuilder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import android.util.LruCache
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.NavUtils
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class Ordboken private constructor(
    context: Context,
    val client: OkHttpClient,
    testDictionaries: Array<Dictionary>? = null
) {
    private val mConnMgr: ConnectivityManager
    val mPrefs: SharedPreferences
    var images = ArrayList<String>()
    var currentWord: Word? = null
    var lastWhere: Where? = null
        private set
    var lastWhat: String? = null
        private set
    var currentCss: String = ""
    lateinit     var currentDictionary: Dictionary
    var currentFlag: Int = R.drawable.flag_se
    var onDictChanged: (() -> Unit)? = null
    private var currentIndex = 0
    private var dictionaries: Array<Dictionary>
    lateinit var dictMap: Map<String, Dictionary>
    private var flags: Array<Int>
    private var languages: Array<String> = emptyArray()
    private var languageFlags: Map<String, Int> = emptyMap()
    private val mCache: LruCache<Uri, Word> = LruCache(25)
    private val mSearchResultCache: LruCache<Pair<String, Int>, List<SearchResult>> = LruCache(25)

    val isOnline: Boolean
        get() {
            val networkInfo = mConnMgr.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }

    // Caller does the commit
    val prefsEditor: SharedPreferences.Editor
        @SuppressLint("CommitPrefEdits")
        get() {
            val ed = mPrefs.edit()

            ed.putString("lastWhere", lastWhere!!.toString())
            ed.putString("lastWhat", lastWhat)
            ed.putInt("currentIndex", currentIndex)

            return ed
        }

    enum class Where {
        MAIN, WORD
    }

    init {
        mPrefs = context.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
        lastWhere = Where.valueOf(mPrefs.getString("lastWhere", Where.MAIN.toString())!!)
        lastWhat = mPrefs.getString("lastWhat", "ordbok")
        mConnMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        dictionaries = testDictionaries ?: defaultDictionaries()
        dictionaries.forEach { it.init() }
        flags = dictionaries.map { it.flag }.toTypedArray()
        dictMap = dictionaries.associateBy { it.tag }

        val languageList = ArrayList<String>()
        val languageFlagMap = HashMap<String, Int>()
        for (dict in dictionaries) {
            if (dict.lang !in languageList) {
                languageList.add(dict.lang)
                languageFlagMap[dict.lang] = dict.flag
            }
        }
        languages = languageList.toTypedArray()
        languageFlags = languageFlagMap

        currentIndex = mPrefs.getInt("currentIndex", 0)
        currentDictionary = dictionaries[currentIndex]
        currentFlag = flags[currentIndex]
    }

    private fun defaultDictionaries(): Array<Dictionary> {
        val so = SoDictionary(this.client)
        val ddo = DdoDictionary(this.client)
        val sdo = SdoDictionary(this.client)
        val dle = DleDictionary(this.client)
        val est = EstDictionary(this.client)
        val colspan = CollinsSpanishEnglishDictionary(this.client)
        val lingpt = LingueeDictionary(this.client)
        val infopedia = InfopediaDictionary(this.client)
        val wfr = FrWiktionary(this.client)
        val rob = LeRobertDictionary(this.client)
        val colfren = CollinsFrenchEnglishDictionary(this.client)
        return arrayOf(so, ddo, sdo, dle, est, colspan, lingpt, infopedia, wfr, rob, colfren)
    }

    fun getWord(uri: Uri): Word? {
        val word = mCache.get(uri)
        if (word != null) return word

        for (dict in dictionaries) {
            dict.get(uri)?.let {
                mCache.put(uri, it)
                return it
            }
        }

        return null
    }

    fun search(query: String, count: Int): List<SearchResult> {
        val key = Pair.create(query, currentIndex)
        var results: List<SearchResult>?

        results = mSearchResultCache.get(key)
        if (results == null) {
            results = currentDictionary.search(query)
            mSearchResultCache.put(key, results)
        }

        return results
    }

    fun onResume(activity: AppCompatActivity) {
        currentIndex = mPrefs.getInt("currentIndex", 0)

        val langGroup = activity.findViewById<RadioGroup>(R.id.langRadio)
        val dictGroup = activity.findViewById<RadioGroup>(R.id.dictRadio)
        val dictScroll = activity.findViewById<HorizontalScrollView>(R.id.radioScroll)

        val currentLang = dictionaries[currentIndex].lang
        var langIndex = 0
        langGroup.removeAllViews()
        for ((index, lang) in languages.withIndex()) {
            if (lang == currentLang) {
                langIndex = index
            }
            val image = languageFlags[lang]!!
            langGroup.addView(RadioButton(activity).apply {
                this.setBackgroundResource(R.drawable.toggle_button_background)
                this.setButtonDrawable(null)
                this.setCompoundDrawablesWithIntrinsicBounds(image, 0, 0, 0)
                this.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                this.tag = lang
                this.contentDescription = lang
            })
        }
        langGroup.check(langGroup.getChildAt(langIndex).id)

        buildDictionaryRow(activity, dictGroup, currentLang)

        langGroup.jumpDrawablesToCurrentState()
        dictGroup.jumpDrawablesToCurrentState()

        activity.findViewById<ImageView>(R.id.dictFlag)?.setImageResource(flags[currentIndex])

        dictScroll.post(Runnable {
            val checked = dictGroup.findViewById<RadioButton>(dictGroup.checkedRadioButtonId)
            if (checked != null) {
                dictScroll.scrollTo((checked.left + checked.right - dictScroll.width) / 2, 0)
            }
        })

        langGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) {
                return@setOnCheckedChangeListener
            }
            val lang = langGroup.findViewById<RadioButton>(checkedId).tag as String
            buildDictionaryRow(activity, dictGroup, lang)
            dictScroll.post(Runnable {
                val checked = dictGroup.findViewById<RadioButton>(dictGroup.checkedRadioButtonId)
                if (checked != null) {
                    dictScroll.smoothScrollTo((checked.left + checked.right - dictScroll.width) / 2, 0)
                }
            })
        }

        dictGroup.setOnCheckedChangeListener { _, checkedId ->
            val button = dictGroup.findViewById<RadioButton>(checkedId)
            if (button == null) {
                return@setOnCheckedChangeListener
            }
            val index = button.tag as Int
            currentIndex = index
            currentDictionary = dictionaries[index]
            currentFlag = flags[index]
            saveDictIndex(currentDictionary.lang, index)
            activity.findViewById<ImageView>(R.id.dictFlag)?.setImageResource(flags[index])
            onDictChanged?.invoke()
        }
    }

    private fun buildDictionaryRow(activity: AppCompatActivity, group: RadioGroup, lang: String) {
        group.clearCheck()
        group.removeAllViews()

        val indices = dictionaries.indices.filter { dictionaries[it].lang == lang }
        var desiredIndex = if (lang == currentDictionary.lang) currentIndex else storedDictIndex(lang)
        if (desiredIndex !in indices) {
            desiredIndex = indices.firstOrNull() ?: return
        }

        for (index in indices) {
            group.addView(RadioButton(activity).apply {
                this.setBackgroundResource(R.drawable.toggle_button_background)
                this.setButtonDrawable(null)
                this.text = dictionaries[index].tag
                this.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6))
                this.tag = index
            })
            val button = group.getChildAt(group.childCount - 1) as RadioButton
            if (index == desiredIndex) {
                group.check(button.id)
            }
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun storedDictIndex(lang: String): Int = mPrefs.getInt("dictIndex_$lang", -1)

    private fun saveDictIndex(lang: String, index: Int) {
        mPrefs.edit().putInt("dictIndex_$lang", index).apply()
    }

    fun initSearchView(
        activity: AppCompatActivity,
        menu: Menu,
        query: String?,
        focus: Boolean
    ): SearchView {
        val searchManager = activity
            .getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = activity.findViewById<View>(R.id.mySearchView) as SearchView

        searchView.setSearchableInfo(
            searchManager.getSearchableInfo(
                ComponentName(
                    activity,
                    MainActivity::class.java
                )
            )
        )

        // Hack to get the magnifying glass icon inside the EditText
        searchView.setIconifiedByDefault(true)
        searchView.isIconified = false

        // Hack to get rid of the collapse button
        searchView.onActionViewExpanded()

        if (!focus) {
            searchView.clearFocus()
        }

        // searchView.setSubmitButtonEnabled(true);
        searchView.isQueryRefinementEnabled = true

        if (query != null) {
            searchView.setQuery(query, false)
        }

        return searchView
    }

    fun onOptionsItemSelected(activity: AppCompatActivity, item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            val upIntent = NavUtils.getParentActivityIntent(activity)
            if (NavUtils.shouldUpRecreateTask(activity, upIntent!!)) {
                TaskStackBuilder.create(activity)
                    .addNextIntentWithParentStack(upIntent)
                    .startActivities()
            } else {
                NavUtils.navigateUpFromSameTask(activity)
            }
            return true
        }

        return false
    }

    fun setLastView(where: Where, what: String) {
        lastWhere = where
        lastWhat = what
    }

    companion object {
        private var sInstance: Ordboken? = null

        fun getInstance(context: Context, client: OkHttpClient? = null): Ordboken {
            var instance = sInstance
            if (instance == null) {
                val effectiveClient = client ?: createDefaultClient(context)
                instance = Ordboken(context, effectiveClient)
                sInstance = instance
            }

            return instance
        }

        // Test-only: build a fresh Ordboken around a fixed set of dictionaries
        // (e.g. MockWebServer-backed fixtures) instead of the real sources.
        fun getInstance(
            context: Context,
            client: OkHttpClient,
            dictionaries: Array<Dictionary>
        ): Ordboken {
            val instance = Ordboken(context, client, dictionaries)
            sInstance = instance
            return instance
        }

        private fun createDefaultClient(context: Context): OkHttpClient {
            val cache = Cache(context.cacheDir, (50 * 1024 * 1024).toLong())
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            return OkHttpClient.Builder()
                .cache(cache)
                .addInterceptor {
                    val networkInfo = connMgr.activeNetworkInfo
                    val isOnline = networkInfo != null && networkInfo.isConnected
                    var request = it.request()

                    if (isOnline) {
                        request = request.newBuilder()
                            .header("Cache-Control", "public, max-age=86400, max-stale=86400")
                            .build()
                    } else {
                        request = request.newBuilder()
                            .cacheControl(CacheControl.FORCE_CACHE).build()
                    }

                    it.proceed(request)
                }
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        fun reset() {
            sInstance = null
        }

        fun startWordActivity(activity: AppCompatActivity, word: String, uri: Uri) {
            val intent = Intent(activity, WordActivity::class.java).apply {
                data = uri
                flags = Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra("title", word)
            }

            activity.startActivity(intent)
        }

        fun startWordActivity(activity: AppCompatActivity, word: String, url: String) {
            startWordActivity(activity, word, Uri.parse(url))
        }
    }
}
