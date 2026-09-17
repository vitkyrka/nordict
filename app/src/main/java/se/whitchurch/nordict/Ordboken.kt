package se.whitchurch.nordict

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    // A combined multi-dictionary word is addressed by its sources probe list
    // (plus the namespaced selected ref); the word's own uri is only the first
    // source's page, so persisting the uri alone would restore/play it as a
    // single-dictionary word. Empty = a normal single word.
    var lastSources: String? = null
        private set
    var lastRef: String? = null
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

    // The last language the user was on (the target of the one-tap swap
    // button). Persisted; seeded to the first language != the current one on a
    // fresh install so the swap affordance is immediately usable.
    var lastLang by mutableStateOf<String?>(null)

    // The active multi-dictionary selection (empty = a single dictionary, the
    // normal state, driven by [currentDictionary]). Set by the agent driver
    // (or a future multi-select UI); any single-dict switch — a nav chip, a
    // language switch, or an agent `setDict tag`/`setLang` — collapses it.
    var activeDicts by mutableStateOf<List<Dictionary>>(emptyList())

    /** The key the suggestion search and its cache use: the selection's tag
     * order when combining, else the current dictionary's tag. */
    val selectionSignature: String
        get() = if (activeDicts.isEmpty()) currentDictionary.tag
        else activeDicts.joinToString(",") { it.tag }

    private var dictionaries: Array<Dictionary>
    lateinit var dictMap: Map<String, Dictionary>
    private var flags: Array<Int>
    private var languages: Array<String> = emptyArray()
    private var languageFlags: Map<String, Int> = emptyMap()
    private val mCache: LruCache<HttpUrl, Word> = LruCache(25)
    private val mSearchResultCache: LruCache<String, List<SearchResult>> = LruCache(25)

    val isOnline: Boolean
        get() = isNetworkOnline(mConnMgr)

    val availableLanguages: List<String>
        get() = languages.toList()

    val languageFlagMap: Map<String, Int>
        get() = languageFlags

    fun langFlag(lang: String): Int = languageFlags[lang] ?: R.drawable.flag_se

    fun dictFlag(index: Int): Int = flags[index]

    /** True when [lang] has at least one dictionary (so its chips combine). */
    fun hasCombiningForLang(lang: String): Boolean =
        combiningDictsForLang(lang).isNotEmpty()

    /** The dictionaries that combine for [lang]: its whole set, in
     * registration order (every dictionary combines with its language's
     * siblings). */
    fun combiningDictsForLang(lang: String): List<Dictionary> =
        dictionaries.filter { it.lang == lang }

    // Caller does the commit
    val prefsEditor: SharedPreferences.Editor
        @SuppressLint("CommitPrefEdits")
        get() {
            val ed = mPrefs.edit()

            ed.putString("lastWhere", lastWhere!!.toString())
            ed.putString("lastWhat", lastWhat)
            ed.putString("lastSources", lastSources ?: "")
            ed.putString("lastRef", lastRef ?: "")
            ed.putInt("currentIndex", currentIndex)
            ed.putString("lastLang", lastLang)

            return ed
        }

    enum class Where {
        MAIN, WORD
    }

    init {
        mPrefs = context.getSharedPreferences("ordboken", Context.MODE_PRIVATE)
        lastWhere = Where.valueOf(mPrefs.getString("lastWhere", Where.MAIN.toString())!!)
        lastWhat = mPrefs.getString("lastWhat", "ordbok")
        lastSources = mPrefs.getString("lastSources", "")?.takeIf { it.isNotBlank() }
        lastRef = mPrefs.getString("lastRef", "")?.takeIf { it.isNotBlank() }
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

        // A fresh install has no last language yet: seed it to the first
        // registered language other than the current one (usually dk against
        // the default se start) so the swap button is usable right away.
        lastLang = mPrefs.getString("lastLang", null)
        if (lastLang == null) {
            val seed = languages.firstOrNull { it != currentDictionary.lang }
            if (seed != null) {
                lastLang = seed
                mPrefs.edit().putString("lastLang", seed).apply()
            }
        }

        // Restore this language's enabled+ordered dictionaries if a valid
        // multi-dictionary selection was persisted for it.
        restoreActiveDicts(currentDictionary.lang)
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
        val key = "$selectionSignature:$query"
        var results: List<SearchResult>?

        results = mSearchResultCache.get(key)
        if (results == null) {
            results = if (activeDicts.isNotEmpty()) MultiDict.search(activeDicts, query)
            else currentDictionary.search(query)
            mSearchResultCache.put(key, results)
        }

        return results
    }

    /**
     * Fetches and merges a combined dictionary page from the probe
     * dictionaries in [sources] (the word's own `sources` route param),
     * honouring a namespaced `__ref`-style ref (`"DLE::2"`) when given.
     */
    fun getCombinedWord(sources: List<CombSource>, ref: String? = null): Word? {
        if (sources.isEmpty()) return null
        val lookups = sources.mapNotNull { dictMap[it.tag] }
        if (lookups.isEmpty()) return null
        return MultiDict.fetch(lookups, sources, ref = ref)
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
        restoreActiveDicts(currentDictionary.lang)
    }

    /**
     * Selects the dictionary at [index], persisting the selection for its
     * language and invoking [onDictChanged]. Shared by the dictionary-row
     * listener and the agent driver so both switch through one path.
     */
    fun setCurrentDictionary(index: Int) {
        if (index !in dictionaries.indices) return
        updateActiveDicts(emptyList())
        currentIndex = index
        currentDictionary = dictionaries[index]
        currentFlag = flags[index]
        saveDictIndex(currentDictionary.lang, index)
        saveDicts(currentDictionary.lang, listOf(currentDictionary.tag))
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
     * remember selection for that language (a stored multi-dictionary
     * combination, else the single-dict index), else its first dictionary.
     */
    fun setLanguage(lang: String): Boolean {
        val indices = dictionaries.indices.filter { dictionaries[it].lang == lang }
        if (indices.isEmpty()) return false
        val previous = currentDictionary.lang
        val stored = storedDictIndex(lang)
        val fallbackIndex = if (stored in indices) stored else indices.first()

        val storedDicts = loadDicts(lang).mapNotNull { dictByTag(it) }
        if (storedDicts.size > 1 && MultiDict.canCombine(storedDicts)) {
            updateActiveDicts(storedDicts)
            val first = storedDicts.first()
            currentIndex = dictionaries.indexOfFirst { it === first }
            currentDictionary = first
            currentFlag = flags[currentIndex]
            saveDictIndex(lang, currentIndex)
        } else {
            updateActiveDicts(emptyList())
            currentIndex = fallbackIndex
            currentDictionary = dictionaries[fallbackIndex]
            currentFlag = flags[fallbackIndex]
        }
        // The language really changed: remember the one we left so the swap
        // button can bounce back to it (a no-op switch keeps lastLang).
        if (previous != lang) {
            lastLang = previous
            mPrefs.edit().putString("lastLang", previous).apply()
        }
        onDictChanged?.invoke()
        return true
    }

    /**
     * The one-tap language change the swap button drives: jumps to the
     * previously active language ([lastLang]) and flips [lastLang] to the one
     * just left, so repeated taps toggle between exactly two languages.
     * Returns false when there is no other language to swap to.
     */
    fun swapLang(): Boolean {
        val target = lastLang ?: return false
        if (target == currentDictionary.lang) return false
        return setLanguage(target)
    }

    /**
     * Selects a multi-dictionary combination by tag. A single tag collapses
     * to the normal single-dictionary state; several tags must share one
     * language ([MultiDict.canCombine]).
     */
    fun setCurrentDictionaries(tags: List<String>): Boolean {
        if (tags.isEmpty()) return false
        val picked = tags.mapNotNull { dictByTag(it) }
        if (picked.size != tags.size) return false
        if (picked.size == 1) return setCurrentDictionary(picked.first().tag)
        if (!MultiDict.canCombine(picked)) return false
        updateActiveDicts(picked)
        currentIndex = dictionaries.indexOfFirst { it === picked.first() }
        currentDictionary = picked.first()
        currentFlag = flags[currentIndex]
        saveDictIndex(currentDictionary.lang, currentIndex)
        saveDicts(currentDictionary.lang, tags)
        onDictChanged?.invoke()
        return true
    }

    /**
     * Toggles [tag] in/out of the active selection (the UI chip handler).
     * Toggling on appends (keeps the current order); toggling off removes.
     * The selection never becomes empty ([DictSelection.toggle] restores
     * the language's first dictionary). Persists and notifies.
     */
    fun toggleDictionary(tag: String): Boolean {
        val dict = dictByTag(tag) ?: return false
        val candidates = combiningDictsForLang(dict.lang).map { it.tag }
        if (tag !in candidates) return false
        val currentTags =
            if (activeDicts.isNotEmpty()) activeDicts.map { it.tag }
            else listOf(currentDictionary.tag)
        val next = DictSelection.toggle(candidates, currentTags, tag)
        return if (next.size <= 1) setCurrentDictionary(next.first())
        else setCurrentDictionaries(next)
    }

    /**
     * Reorders the active multi-dictionary selection (the drag-drop commit).
     * [tags] must be a permutation of the current selection; the order is
     * persisted and [onDictChanged] fires once.
     */
    fun setDictionaryOrder(tags: List<String>): Boolean {
        if (activeDicts.isEmpty()) return false
        val currentTags = activeDicts.map { it.tag }
        if (tags.size != currentTags.size || tags.toSet() != currentTags.toSet()) {
            return false
        }
        val picked = tags.mapNotNull { dictByTag(it) }
        if (picked.size != tags.size || !MultiDict.canCombine(picked)) return false
        updateActiveDicts(picked)
        currentIndex = dictionaries.indexOfFirst { it === picked.first() }
        currentDictionary = picked.first()
        currentFlag = flags[currentIndex]
        saveDictIndex(currentDictionary.lang, currentIndex)
        saveDicts(currentDictionary.lang, tags)
        onDictChanged?.invoke()
        return true
    }

    private fun updateActiveDicts(dicts: List<Dictionary>) {
        if (activeDicts != dicts) activeDicts = dicts
    }

    private fun restoreActiveDicts(lang: String) {
        val stored = loadDicts(lang).mapNotNull { dictByTag(it) }
        // A single persisted tag is just the normal single-dictionary state
        // (saved by [setCurrentDictionary]); only a real combination restores
        // as multi-dict.
        if (stored.size > 1 && MultiDict.canCombine(stored)) {
            updateActiveDicts(stored)
            val first = stored.first()
            currentIndex = dictionaries.indexOfFirst { it === first }
            currentDictionary = first
            currentFlag = flags[currentIndex]
        } else {
            updateActiveDicts(emptyList())
            // Restore the language's remembered single dictionary (the global
            // currentIndex may still point at another language on cold start).
            val idx = storedDictIndex(lang)
            if (idx in dictionaries.indices && dictionaries[idx].lang == lang) {
                currentIndex = idx
                currentDictionary = dictionaries[idx]
                currentFlag = flags[idx]
            }
        }
    }

    private fun dictByTag(tag: String): Dictionary? =
        dictionaries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    private fun storedDictIndex(lang: String): Int = mPrefs.getInt("dictIndex_$lang", -1)

    private fun saveDictIndex(lang: String, index: Int) {
        mPrefs.edit().putInt("dictIndex_$lang", index).apply()
    }

    /** Reads [lang]'s persisted enabled+ordered combining tags. */
    private fun loadDicts(lang: String): List<String> {
        val raw = mPrefs.getString("dicts_$lang", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }.filter { tag ->
            val dict = dictByTag(tag)
            dict != null && dict.lang == lang
        }
    }

    private fun saveDicts(lang: String, tags: List<String>) {
        mPrefs.edit().putString("dicts_$lang", tags.joinToString(",")).apply()
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
                    val isOnline = isNetworkOnline(connMgr)
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

        private fun isNetworkOnline(connMgr: ConnectivityManager): Boolean {
            val network = connMgr.activeNetwork ?: return false
            val caps = connMgr.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
