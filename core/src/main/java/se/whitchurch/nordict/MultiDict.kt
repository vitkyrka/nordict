package se.whitchurch.nordict

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * One dictionary's page for a headword inside a combined lookup: the source tag
 * and the exact URI to fetch (the autocomplete suggestion URI, a word.js link's
 * exact match, or the CLI's wordUrl target). Carried by merged [SearchResult]s
 * so an opening can re-fetch every selected dictionary in parallel.
 */
data class CombSource(val tag: String, val uri: HttpUrl, val summary: String = "")

/**
 * The minimal shape the combined engine needs from a lookup source. Both the
 * app's [Dictionary] and the CLI's `Dict` satisfy it (via a small adapter), so
 * the merge/aggregate logic lives here once instead of in each driver. `get`
 * returns a page's word: a JSON dictionary fills its `mHomonymEntries` with the
 * whole page's entries, which the combiner flattens.
 */
interface WordLookup {
    val tag: String
    fun search(query: String): List<SearchResult>
    fun get(uri: HttpUrl): Word?
}

/**
 * Combines several same-language JSON dictionaries into one lookup: searches
 * run in parallel and merge by headword (tagging every result with its
 * dictionaries), and opening a headword fetches every selected dictionary in
 * parallel and aggregates their page entries into a single renderable word —
 * the same stacked-page rendering the app already uses for multi-headword
 * pages, now spanning dictionaries.
 *
 * Namespaced refs (`"DLE::1"`) keep each dictionary's `__ref` ids distinct in
 * the combined entry set and let an agent name the exact source+entry of what
 * it is looking at.
 */
object MultiDict {
    private const val REF_SEP = "::"
    private const val WORKERS = 4

    val log: Logger = Logger.getLogger("MultiDict")

    // Shared pool for parallel searches/fetches. Wrapped in an object so the
    // app and the CLI never duplicate the threads.
    private val pool: ExecutorService = Executors.newFixedThreadPool(WORKERS)

    /** Turns a dictionary's local `__ref` into the combined page's ref space. */
    fun refOf(tag: String, ref: String): String = "$tag$REF_SEP$ref"

    /** True when [ref] is a combined (namespaced) ref. */
    fun isCombinedRef(ref: String): Boolean = REF_SEP in ref

    /**
     * The label a combined page shows for a dictionary's entries: the parser's
     * own `dictionary` label when it set one (e.g. Collins), else the tag.
     */
    fun labelFor(dictionary: String, tag: String): String = dictionary.ifEmpty { tag }

    /**
     * True when [dicts] can combine: at least two same-language dictionaries
     * (every JSON-rendered dictionary of the app combines with its language's
     * siblings). Everything else keeps single-dictionary behavior.
     */
    fun canCombine(dicts: List<Dictionary>): Boolean =
        dicts.size > 1 && dicts.map { it.lang }.distinct().size == 1

    private class MutableReadySearch(
        val title: String,
        var summary: String,
        val uri: HttpUrl,
        val dicts: MutableList<String>,
        val sources: MutableList<CombSource>
    )

    /**
     * Searches [lookups] in parallel and merges the results by headword,
     * ignoring case, ordering the combined list alphabetically (case-folded)
     * across all dictionaries. A headword found in several dictionaries yields
     * one [SearchResult] whose `dicts`/`sources` carry every dictionary's tag
     * and page (deduplicated per dictionary, first summary kept).
     */
    fun search(lookups: List<WordLookup>, query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return mergeSearch(lookups.map { lookup ->
            pool.submit<Pair<String, List<SearchResult>>> {
                try {
                    lookup.tag to lookup.search(trimmed)
                } catch (e: Exception) {
                    log.severe("combined search failed for ${lookup.tag}: ${e.message}")
                    lookup.tag to emptyList()
                }
            }
        }.map { future ->
            try {
                future.get()
            } catch (e: Exception) {
                log.severe("combined search interrupted: ${e.message}")
                "" to emptyList()
            }
        }, trimmed)
    }

    /**
     * Merges already-fetched per-dictionary [results] (tag, list) into one
     * dictionary-tagged list. Headwords merge case-insensitively ("Trinidad"
     * and "trinidad" are one entry, keeping the first-seen casing); the merged
     * entries are then ordered alphabetically by title, ignoring case, so the
     * combined list reads as one case-folded dictionary rather than per-source
     * selection order. Entries whose title starts with [query] (case-folded)
     * sort before all others, so an exact/prefix match like "frente" is not
     * buried under "al frente".
     */
    fun mergeSearch(results: List<Pair<String, List<SearchResult>>>, query: String = ""): List<SearchResult> {
        val merged = LinkedHashMap<String, MutableReadySearch>()
        for ((tag, list) in results) {
            for (r in list) {
                val key = r.mTitle.lowercase()
                val existing = merged[key]
                if (existing == null) {
                    merged[key] = MutableReadySearch(
                        r.mTitle, r.mSummary, r.uri,
                        mutableListOf(tag), mutableListOf(CombSource(tag, r.uri, r.mSummary))
                    )
                } else if (tag !in existing.dicts) {
                    existing.dicts.add(tag)
                    existing.sources.add(CombSource(tag, r.uri, r.mSummary))
                    if (existing.summary.isBlank() && r.mSummary.isNotBlank()) existing.summary = r.mSummary
                }
            }
        }
        val prefix = query.trim().lowercase()
        return merged.values
            .sortedWith(
                compareBy(
                    { !(prefix.isNotEmpty() && it.title.lowercase().startsWith(prefix)) },
                    { it.title.lowercase() }
                )
            )
            .map {
                SearchResult(it.title, it.summary, it.uri, it.dicts.toList(), it.sources.toList())
            }
    }

    /**
     * The ordered set of dictionaries that have a unique exact match for
     * [headword] (the word.js `/search/` link opener). Dictionaries without the
     * word are skipped; the result keeps the selection's order.
     */
    fun resolveExact(lookups: List<WordLookup>, headword: String): List<CombSource> {
        val trimmed = headword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val futures = lookups.map { lookup ->
            pool.submit<Pair<String, SearchResult?>> {
                try {
                    lookup.tag to ExactMatch.resolve(trimmed, lookup.search(trimmed))
                } catch (e: Exception) {
                    log.severe("combined exact search failed for ${lookup.tag}: ${e.message}")
                    lookup.tag to null
                }
            }
        }
        val sources = ArrayList<CombSource>()
        for (future in futures) {
            val (tag, exact) = try {
                future.get()
            } catch (e: Exception) {
                "" to null
            }
            if (exact != null) sources.add(CombSource(tag, exact.uri, exact.mSummary))
        }
        return sources
    }

    /**
     * Fetches every [source] in parallel and aggregates the dictionaries' page
     * entries into one renderable combined [Word]. Dictionaries that fail to
     * parse are skipped; null when none did. [ref] is the namespaced entry ref
     * to select (null selects the first entry).
     */
    fun fetch(lookups: List<WordLookup>, sources: List<CombSource>, headword: String = "", ref: String? = null): Word? {
        if (sources.isEmpty()) return null
        val byTag = lookups.associateBy { it.tag }

        val futures = sources.map { source ->
            pool.submit<Pair<List<Word.HomonymEntry>, Word>?> {
                try {
                    val word = byTag[source.tag]?.get(source.uri) ?: return@submit null
                    val entries = entriesFor(source.tag, word)
                    if (entries.isEmpty()) {
                        null
                    } else {
                        entries to word
                    }
                } catch (e: Exception) {
                    log.severe("combined fetch failed for ${source.tag} ${source.uri}: ${e.message}")
                    null
                }
            }
        }

        val allEntries = ArrayList<Word.HomonymEntry>()
        var primary: Word? = null
        var primaryTag: String? = null
        futures.forEachIndexed { i, future ->
            val source = sources[i]
            val result = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (result != null) {
                if (primary == null) {
                    primary = result.second
                    primaryTag = source.tag
                }
                allEntries.addAll(result.first)
            }
        }

        val base = primary ?: return null
        val head = headword.ifBlank { base.searchHeadword }
        return Word.combined(base, labelFor(base.dictionary, primaryTag!!), allEntries, head, ref)
    }

    /**
     * Flattens [word]'s page into labeled, namespaced homonym entries for the
     * combined set. Falls back to the word itself when its page had no
     * `mHomonymEntries` (a single-entry page).
     */
    fun entriesFor(tag: String, word: Word): List<Word.HomonymEntry> {
        val pageEntries = if (word.mHomonymEntries.isNotEmpty()) {
            word.mHomonymEntries
        } else {
            Word.homonymEntries(listOf(word))
        }
        return pageEntries.map { e ->
            Word.HomonymEntry(
                mTitle = e.mTitle,
                ref = refOf(tag, e.ref),
                dictionary = labelFor(e.dictionary, tag),
                conjugation = e.conjugation,
                participle = e.participle,
                etymology = e.etymology,
                pronunciation = e.pronunciation,
                definitions = e.definitions,
                idioms = e.idioms,
                audio = e.audio
            )
        }
    }

    /**
     * The source page URIs a loaded word's "open in browser" action should
     * launch: every selected dictionary's page for a combined word (in
     * selection order), or the single page for a plain word. Combined words
     * keep their probes in the route's [sources] — their own `uri` is just the
     * first dictionary's — so the menu action must go through here rather than
     * [Word.uri].
     */
    fun externalUris(word: Word, sources: List<CombSource>): List<HttpUrl> =
        if (sources.isNotEmpty()) sources.map { it.uri } else listOf(word.uri)

    // ---- Route wire codec (the word destination's `sources` argument) ----

    /** Serializes [sources] for the word route (`[tag, uri, summary]` objects). */
    fun sourcesToJson(sources: List<CombSource>): String {
        val arr = JsonArray()
        for (s in sources) {
            val obj = JsonObject()
            obj.addProperty("tag", s.tag)
            obj.addProperty("uri", s.uri.toString())
            obj.addProperty("summary", s.summary)
            arr.add(obj)
        }
        return AgentProtocol.gson.toJson(arr)
    }

    /** Decodes a word route's `sources` argument back into [CombSource]s. */
    fun sourcesFromJson(json: String): List<CombSource> {
        if (json.isBlank()) return emptyList()
        val arr = try {
            AgentProtocol.gson.fromJson(json, JsonArray::class.java) ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<CombSource>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val uriStr = obj.get("uri")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val uri = uriStr.toHttpUrlOrNull() ?: continue
            val tag = obj.get("tag")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val summary = obj.get("summary")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            out.add(CombSource(tag, uri, summary))
        }
        return out
    }
}