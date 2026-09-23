package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Combined multi-dictionary engine tests: DLE + EST (the same-language, all
 * JSON-rendered pair) share one MockWebServer with the live hosts' path
 * shapes, served from the `testdata/` fixtures via a path dispatcher.
 */
class MultiDictTest {

    private lateinit var server: MockWebServer
    private lateinit var dle: DleDictionary
    private lateinit var est: EstDictionary

    private fun fixture(name: String): String = Goldens.fixtureText("../testdata/$name")

    @Before
    fun setUp() {
        Goldens.requireTestdata()
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        val estBase = server.url("/diccionario-estudiante").toString().removeSuffix("/")
        val client = OkHttpClient()
        dle = DleDictionary(client, base)
        est = EstDictionary(client, estBase)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    // DLE search keys are query-specific: only "frentero" has its
                    // own list, everything else serves the shared frente fixture.
                    path.startsWith("/srv/keys") && request.requestUrl?.queryParameter("q") == "frentero" ->
                        MockResponse().setBody("""["frentero|frentero"]""")
                    path.startsWith("/srv/keys") -> MockResponse().setBody(fixture("dle-search.json"))
                    path.startsWith("/frente") -> MockResponse().setBody(fixture("dle.html"))
                    path.startsWith("/diccionario-estudiante/srv/keys") ->
                        MockResponse().setBody(fixture("est-search.json"))
                    path.startsWith("/diccionario-estudiante/frente") -> MockResponse().setBody(fixture("est.html"))
                    path.startsWith("/diccionario-estudiante/muerte") -> MockResponse().setBody(fixture("est/muerte.html"))
                    path.startsWith("/diccionario-estudiante/cagar") -> MockResponse().setBody(fixture("est/cagar.html"))
                    else -> {
                        println("unexpected path: $path")
                        MockResponse().setResponseCode(404)
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        // setUp() skips (Assume) when testdata is absent, leaving server
        // uninitialized — guard so the skip isn't turned into a failure.
        if (::server.isInitialized) server.shutdown()
    }

    private fun lookups() = listOf(dle, est)

    /** A same-tag, different-language dictionary (a cross-language pair cannot
     * combine). */
    private class FakeForeign(client: OkHttpClient) : Dictionary(client) {
        override val tag = "SO"
        override val flagCode = "sedk"
        override val lang = "sv"
        override fun search(query: String) = emptyList<SearchResult>()
        override fun fullSearch(query: String) = emptyList<SearchResult>()
        override fun get(uri: okhttp3.HttpUrl): Word? = null
    }

    @Test
    fun canCombineAcceptsOnlySameLanguageMultiDictionarySelections() {
        assertThat(MultiDict.canCombine(lookups())).isTrue()

        val foreign = FakeForeign(okhttp3.OkHttpClient())
        assertThat(MultiDict.canCombine(listOf(dle, foreign))).isFalse() // mixed languages
        assertThat(MultiDict.canCombine(listOf(dle))).isFalse() // needs two dictionaries
        assertThat(MultiDict.canCombine(emptyList())).isFalse()
    }

    @Test
    fun combinedSearchMergesByHeadwordAndTagsSources() {
        val merged = MultiDict.search(lookups(), "frente")

        // "frente" exists in both dictionaries: one result, both tags, both sources.
        assertThat(merged).isNotEmpty()
        val frente = merged.first { it.mTitle == "frente" }
        assertThat(frente.dicts).containsExactly("DLE", "EST").inOrder()
        assertThat(frente.sources).hasSize(2)
        assertThat(frente.sources[0].tag).isEqualTo("DLE")
        assertThat(frente.sources[0].uri.toString()).contains("/frente")
        assertThat(frente.sources[1].tag).isEqualTo("EST")

        // DLE-only and EST-only results appear once: entries starting
        // with the query sort before the rest, alphabetically within each
        // group, so "frente" is not buried under "al frente".
        assertThat(merged.map { it.mTitle })
            .containsExactly("frente", "frente a", "frente a frente",
                "frente por frente", "frentero", "al frente",
                "con la frente muy alta", "dar un paso al frente", "de frente",
                "dos dedos de frente", "en frente").inOrder()
        assertThat(merged.first { it.mTitle == "frentero" }.dicts).containsExactly("DLE")
        assertThat(merged.first { it.mTitle == "al frente" }.dicts).containsExactly("EST")
    }

    @Test
    fun mergeSearchCombinesCaseInsensitivelyAndOrdersAlphabetically() {
        val merged = MultiDict.mergeSearch(listOf(
            "COLSPAN" to listOf(
                SearchResult("Trinidad", "island", server.url("/trinidad")),
                SearchResult("Trinidad y Tobago", "", server.url("/trinidad-y-tobago"))
            ),
            "EST" to listOf(
                SearchResult("trinidad", "", server.url("/diccionario-estudiante/trinidad"))
            ),
            "DLE" to listOf(
                SearchResult("trinidad", "", server.url("/dle/trinidad"))
            )
        ))

        assertThat(merged).hasSize(2)
        // One case-folded entry for the headword across all three dicts,
        // keeping the first-seen casing from the selection order.
        val trinidad = merged[0]
        assertThat(trinidad.mTitle).isEqualTo("Trinidad")
        assertThat(trinidad.dicts).containsExactly("COLSPAN", "EST", "DLE").inOrder()
        assertThat(trinidad.sources).hasSize(3)
        assertThat(trinidad.uri.toString()).contains("/trinidad")
        // ...ordered before "Trinidad y Tobago" on a case-insensitive sort.
        assertThat(merged.map { it.mTitle })
            .containsExactly("Trinidad", "Trinidad y Tobago").inOrder()
    }

    @Test
    fun mergeSearchSortsQueryPrefixMatchesBeforeOthers() {
        val results = listOf(
            "COLSPAN" to listOf(
                SearchResult("al frente", "", server.url("/al-frente")),
                SearchResult("frente a", "", server.url("/frente-a"))
            ),
            "EST" to listOf(
                SearchResult("con la frente muy alta", "", server.url("/diccionario-estudiante/con")),
                SearchResult("frente", "", server.url("/diccionario-estudiante/frente"))
            )
        )

        // Prefix matches ("frente", "frente a") sort before the rest,
        // alphabetically within each group.
        assertThat(MultiDict.mergeSearch(results, "frente").map { it.mTitle })
            .containsExactly(
                "frente", "frente a", "al frente", "con la frente muy alta"
            ).inOrder()

        // Matching is case-folded and trims the query.
        assertThat(MultiDict.mergeSearch(results, "  Frente ").map { it.mTitle })
            .containsExactly(
                "frente", "frente a", "al frente", "con la frente muy alta"
            ).inOrder()

        // Without a query the list stays purely alphabetical.
        assertThat(MultiDict.mergeSearch(results).map { it.mTitle })
            .containsExactly(
                "al frente", "con la frente muy alta", "frente", "frente a"
            ).inOrder()
    }

    @Test
    fun combinedFetchAggregatesPagesIntoOneWord() {
        val sources = listOf(
            CombSource("DLE", server.url("/frente")),
            CombSource("EST", server.url("/diccionario-estudiante/frente"))
        )

        val combined = MultiDict.fetch(lookups(), sources, headword = "frente")!!

        assertThat(combined.mTitle).isEqualTo("frente")
        assertThat(combined.dictionary).isEqualTo("DLE")
        // One entry per source page, selection order, namespaced refs.
        assertThat(combined.mHomonymEntries).hasSize(2)
        assertThat(combined.mHomonymEntries.map { it.ref })
            .containsExactly("DLE::1", "EST::1").inOrder()
        assertThat(combined.mHomonymEntries.map { it.mTitle })
            .containsExactly("frente", "frente").inOrder()
        // The headword carries through for re-searches.
        assertThat(combined.searchHeadword).isEqualTo("frente")
        // The first dictionary's content is the headline-level slice.
        assertThat(combined.definitions).isNotEmpty()
    }

    @Test
    fun combinedWordFallsBackToEntryAudioWhenBaseHasNone() {
        // The DLE face "frente" page carries no pronunciation clips, but the
        // combined word may resolve from another dictionary that does (e.g.
        // COLSPAN's ES-ES sound). The word bar's play button must not sit
        // disabled: when the base word's audio is empty, merge the entries'.
        val base = dle.get(server.url("/frente"))!!
        assertThat(base.audio).isEmpty()
        val silentEntry = Word.toHomonymEntry(base)
        val audioEntry = Word.toHomonymEntry(base).apply {
            audio.add("https://example.com/pron.mp3")
        }

        val combined = Word.combined(base, "DLE", listOf(silentEntry, audioEntry), "frente", null)
        assertThat(combined.audio).containsExactly("https://example.com/pron.mp3")

        // Duplicated clips across entries collapse to one.
        val doubled = Word.combined(
            base, "DLE",
            listOf(audioEntry, Word.toHomonymEntry(base).apply {
                audio.addAll(audioEntry.audio)
            }),
            "frente", null
        )
        assertThat(doubled.audio).containsExactly("https://example.com/pron.mp3")
    }

    @Test
    fun combinedWordUnionsBaseAndEntryAudio() {
        // The play button queues the top-level list, so when two dictionaries
        // both carry clips (e.g. INFOPEDIA + LINGPT) both must play: base
        // first, then entries', deduped.
        val base = dle.get(server.url("/frente"))!!
        val baseWithAudio = Word.toHomonymEntry(base).apply {
            audio.add("https://example.com/base.mp3")
        }
        val audioEntry = Word.toHomonymEntry(base).apply {
            audio.add("https://example.com/pron.mp3")
        }
        val baseWord = base.apply { audio.add("https://example.com/base.mp3") }

        val combined = Word.combined(
            baseWord, "DLE", listOf(baseWithAudio, audioEntry), "frente", null
        )
        assertThat(combined.audio).containsExactly(
            "https://example.com/base.mp3", "https://example.com/pron.mp3"
        ).inOrder()
    }

    @Test
    fun combinedFetchReflectsRefSelection() {
        // The EST "muerte" page has three entries; a combined fetch over it
        // (with a namespaced ref) aggregates page order + labels and selects.
        val sources = listOf(
            CombSource("EST", server.url("/diccionario-estudiante/muerte"))
        )
        val combined = MultiDict.fetch(lookups(), sources, ref = "EST::2")!!

        assertThat(combined.mHomonymEntries.map { it.mTitle })
            .containsExactly("muerte", "muerte natural", "muerte violenta").inOrder()
        assertThat(combined.mHomonymEntries.map { it.ref })
            .containsExactly("EST::1", "EST::2", "EST::3").inOrder()
        assertThat(combined.mHomonymEntries.map { it.dictionary })
            .containsExactly("EST", "EST", "EST").inOrder()
        assertThat(combined.xrefs).containsExactly("EST::2")
    }

    @Test
    fun combinedSearchTrimsSurroundingWhitespace() {
        val padded = MultiDict.search(lookups(), "  frente  ")

        assertThat(padded).isNotEmpty()
        assertThat(padded.first { it.mTitle == "frente" }.dicts)
            .containsExactly("DLE", "EST").inOrder()
        assertThat(MultiDict.search(lookups(), "   ")).isEmpty()
    }

    @Test
    fun resolveExactKeepsMatchingDictionariesInSelectionOrder() {
        val both = MultiDict.resolveExact(lookups(), "frente")
        assertThat(both.map { it.tag }).containsExactly("DLE", "EST").inOrder()

        val onlyDle = MultiDict.resolveExact(lookups(), "frentero")
        assertThat(onlyDle.map { it.tag }).containsExactly("DLE")
    }

    @Test
    fun resolveExactFallsBackToSingular() {
        // A word.js plural link ("casas") resolves to the singular page when
        // only the singular has an exact match.
        val singular = SearchResult("casa", "", server.url("/frente"))
        val lookup = object : WordLookup {
            override val tag = "DLE"
            override fun search(query: String): List<SearchResult> =
                if (query.equals("casa", ignoreCase = true)) listOf(singular)
                else emptyList()
            override fun get(uri: okhttp3.HttpUrl): Word? = null
        }
        val sources = MultiDict.resolveExact(listOf(lookup), "casas")
        assertThat(sources.map { it.tag }).containsExactly("DLE")

        val (matched, withQuery) = MultiDict.resolveExactWithQuery(listOf(lookup), "casas")
        assertThat(matched).isEqualTo("casa")
        assertThat(withQuery.map { it.tag }).containsExactly("DLE")

        // No singular anywhere: still empty.
        assertThat(MultiDict.resolveExact(listOf(lookup), "xyzzy")).isEmpty()
    }

    @Test
    fun externalUrisOpensEverySelectedDictionary() {
        val sources = listOf(
            CombSource("DLE", server.url("/frente")),
            CombSource("EST", server.url("/diccionario-estudiante/frente"))
        )
        val combined = MultiDict.fetch(lookups(), sources, headword = "frente")!!

        // The combined word's own uri is only the first source's page; the
        // "open in browser" action must launch every selected dictionary.
        assertThat(combined.uri.toString()).contains("/frente")
        assertThat(MultiDict.externalUris(combined, sources).map { it.toString() })
            .containsExactly(
                server.url("/frente").toString(),
                server.url("/diccionario-estudiante/frente").toString()
            ).inOrder()
    }

    @Test
    fun externalUrisFallsBackToTheSingleWordUri() {
        val word = dle.get(server.url("/frente"))!!

        assertThat(MultiDict.externalUris(word, emptyList()))
            .containsExactly(word.uri)
    }

    @Test
    fun refsAndLabels() {
        assertThat(MultiDict.refOf("DLE", "3")).isEqualTo("DLE::3")
        assertThat(MultiDict.isCombinedRef("DLE::3")).isTrue()
        assertThat(MultiDict.isCombinedRef("3")).isFalse()
        assertThat(MultiDict.labelFor("", "DLE")).isEqualTo("DLE")
        assertThat(MultiDict.labelFor("Easy Learning", "COLSPAN")).isEqualTo("Easy Learning")
    }

    @Test
    fun sourcesJsonRoundTrips() {
        val sources = listOf(
            CombSource("DLE", server.url("/frente"), "Parte superior de la cara"),
            CombSource("EST", server.url("/diccionario-estudiante/frente"))
        )
        val json = MultiDict.sourcesToJson(sources)
        assertThat(json).contains("DLE")

        val back = MultiDict.sourcesFromJson(json)
        assertThat(back).isEqualTo(sources)
        assertThat(MultiDict.sourcesFromJson("not json")).isEmpty()
        assertThat(MultiDict.sourcesFromJson("")).isEmpty()
    }
}