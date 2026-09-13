package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import android.util.LruCache
import android.util.Pair
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
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
    val currentLang: String
        get() = currentDictionary.lang

    // Compose-observable so the nav rows and the search suggestions recompose
    // when the dictionary (or its per-language selection) changes.
    var currentIndex by mutableStateOf(0)

    private var dictionaries: Array<Dictionary>
    lateinit var dictMap: Map<String, Dictionary>
    private var flags: Array<Int>
    private var languages: Array<String> = emptyArray()
    private var languageFlags: Map<String, Int> = emptyMap()
    private val mCache: LruCache<HttpUrl, Word> = LruCache(25)
    private val mSearchResultCache: LruCache<Pair<String, Int>, List<SearchResult>> = LruCache(25)

    val isOnline: Boolean
        get() {
            val networkInfo = mConnMgr.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }

    val availableLanguages: List<String>
        get() = languages.toList()

    val languageFlagMap: Map<String, Int>
        get() = languageFlags

    fun langFlag(lang: String): Int = languageFlags[lang] ?: R.drawable.flag_se

    fun dictTag(index: Int): String = dictionaries[index].tag

    fun dictIndicesForLang(lang: String): List<Int> =
        dictionaries.indices.filter { dictionaries[it].lang == lang }

    fun dictFlag(index: Int): Int = flags[index]

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
        flags = dictionaries.map { flagResId(it.flagCode) }.toTypedArray()
        dictMap = dictionaries.associateBy { it.tag }

        val languageList = ArrayList<String>()
        val languageFlagMap = HashMap<String, Int>()
        for (dict in dictionaries) {
            if (dict.lang !in languageList) {
                languageList.add(dict.lang)
                languageFlagMap[dict.lang] = flagResId(dict.flagCode)
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
        val didac = DidacDictionary(this.client)
        val gdlc = GdlcDictionary(this.client)
        val caes = CatalaCastellaDictionary(this.client)
        val caen = CatalaAnglesDictionary(this.client)
        val lingpt = LingueeDictionary(this.client)
        val infopedia = InfopediaDictionary(this.client)
        val wfr = FrWiktionary(this.client)
        val rob = LeRobertDictionary(this.client)
        val colfren = CollinsFrenchEnglishDictionary(this.client)
        return arrayOf(so, ddo, sdo, dle, est, colspan, didac, gdlc, caes, caen, lingpt, infopedia, wfr, rob, colfren)
    }

    fun getWord(uri: Uri): Word? {
        val httpUrl = uri.toHttpUrl()
        val word = mCache.get(httpUrl)
        if (word != null) return word

        for (dict in dictionaries) {
            dict.get(httpUrl)?.let {
                mCache.put(httpUrl, it)
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
        // Restore any dictionary selection persisted while this screen was
        // paused; the Compose nav rows read the live Ordboken state, so no
        // view mutation is needed here.
        currentIndex = mPrefs.getInt("currentIndex", 0)
        if (currentIndex !in dictionaries.indices) {
            currentIndex = 0
        }
        currentDictionary = dictionaries[currentIndex]
        currentFlag = flags[currentIndex]
    }

    /**
     * Selects the dictionary at [index], persisting the selection for its
     * language and invoking [onDictChanged]. Shared by the dictionary-row
     * listener and the agent driver so both switch through one path.
     */
    fun setCurrentDictionary(index: Int) {
        if (index !in dictionaries.indices) return
        currentIndex = index
        currentDictionary = dictionaries[index]
        currentFlag = flags[index]
        saveDictIndex(currentDictionary.lang, index)
        onDictChanged?.invoke()
    }

    /** Selects the dictionary registered under [tag]; true if known. */
    fun setCurrentDictionary(tag: String): Boolean {
        val index = dictionaries.indexOfFirst { it.tag.equals(tag, ignoreCase = true) }
        if (index < 0) return false
        setCurrentDictionary(index)
        return true
    }

    /**
     * Selects the dictionary for [lang] the way the language row does: the
     * remember selection for that language, else its first dictionary.
     */
    fun setLanguage(lang: String): Boolean {
        val indices = dictionaries.indices.filter { dictionaries[it].lang == lang }
        if (indices.isEmpty()) return false
        val stored = storedDictIndex(lang)
        setCurrentDictionary(if (stored in indices) stored else indices.first())
        return true
    }

    private fun storedDictIndex(lang: String): Int = mPrefs.getInt("dictIndex_$lang", -1)

    private fun saveDictIndex(lang: String, index: Int) {
        mPrefs.edit().putInt("dictIndex_$lang", index).apply()
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
    }
}
