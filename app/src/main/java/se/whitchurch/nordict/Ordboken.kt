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

class Ordboken private constructor(context: Context) {
    private val mConnMgr: ConnectivityManager
    val mPrefs: SharedPreferences
    var images = ArrayList<String>()
    var currentWord: Word? = null
    var lastWhere: Where? = null
        private set
    var lastWhat: String? = null
        private set
    val client: OkHttpClient
    var currentCss: String = ""
    lateinit var currentDictionary: Dictionary
    var currentFlag: Int = R.drawable.flag_se
    private var currentIndex = 0
    private var dictionaries: Array<Dictionary>
    lateinit var dictMap: Map<String, Dictionary>
    private var flags: Array<Int>
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
        val cache = Cache(context.cacheDir, (50 * 1024 * 1024).toLong())
        client = OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor {
                var request = it.request()

                if (isOnline) {
                    request = request.newBuilder()
                        .header("Cache-Control", "public, max-age=86400, max-stale=86400").build()
                } else {
                    request = request.newBuilder()
                        .cacheControl(CacheControl.FORCE_CACHE).build()
                }

                it.proceed(request)
            }
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        mPrefs = context.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
        lastWhere = Where.valueOf(mPrefs.getString("lastWhere", Where.MAIN.toString())!!)
        lastWhat = mPrefs.getString("lastWhat", "ordbok")
        mConnMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager


        val so = SoDictionary(client)
        so.init()

        val ddo = DdoDictionary(client)
        ddo.init()

        val sdo = SdoDictionary(client)
        sdo.init()

        val wfr = FrWiktionary(client)
        wfr.init()

        val rob = LeRobertDictionary(client)
        rob.init()

        val colfren = CollinsFrenchEnglishDictionary(client)
        colfren.init()

        var islex = IslexDictionary(client)
        islex.init()

        dictionaries = arrayOf(so, ddo, sdo, wfr, rob, colfren, islex)
        flags = arrayOf(so.flag, ddo.flag, sdo.flag, wfr.flag, rob.flag, colfren.flag, islex.flag)

        currentIndex = mPrefs.getInt("currentIndex", 0)
        currentDictionary = dictionaries[currentIndex]
        currentFlag = flags[currentIndex]

        dictMap = mapOf(
            so.tag to so,
            ddo.tag to ddo,
            sdo.tag to sdo,
            wfr.tag to wfr,
            rob.tag to rob,
            colfren.tag to colfren,
            islex.tag to islex,
        )
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

        val group = activity.findViewById<RadioGroup>(R.id.dictRadio)
        group.removeAllViews()

        var prevFlag = 0
        for ((index, it) in flags.withIndex()) {
            group.addView(RadioButton(activity).apply {
                if (it == prevFlag) {
                    this.text = "\u00A0" + dictionaries[index].tag[0]
                }
                this.setCompoundDrawablesWithIntrinsicBounds(it, 0, 0, 0)
            })

            prevFlag = it
        }

        group.check(group.getChildAt(currentIndex).id)

        val radioScroll = activity.findViewById<HorizontalScrollView>(R.id.radioScroll)
        radioScroll.post(Runnable {
            val view = group.getChildAt(currentIndex)
            radioScroll.scrollTo((view.left + view.right - radioScroll.width) / 2, 0)
        })

        activity.findViewById<ImageView>(R.id.dictFlag)?.setImageResource(flags[currentIndex])

        group.jumpDrawablesToCurrentState()
        group.setOnCheckedChangeListener { group, checkedId ->
            val button = group.findViewById<RadioButton>(checkedId)
            val index = group.indexOfChild(button)

            currentIndex = index
            currentDictionary = dictionaries[index]
            currentFlag = flags[index]
            activity.findViewById<ImageView>(R.id.dictFlag)?.setImageResource(flags[index])
        }
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

        fun getInstance(context: Context): Ordboken {
            var instance = sInstance
            if (instance == null) {
                instance = Ordboken(context)
                sInstance = instance
            }

            return instance
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
