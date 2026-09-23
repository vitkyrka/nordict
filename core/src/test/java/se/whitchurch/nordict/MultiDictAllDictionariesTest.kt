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
 * Combining across the dictionaries that used to be single-dict-only: formerly
 * only DLE/EST/COLSPAN and the diccionari.cat family combined, but every
 * same-language dictionary does now. This exercises the Portuguese pair
 * (Linguee + Infopédia) end-to-end and checks the French and Swedish pairs pass
 * the same-language gate.
 */
class MultiDictAllDictionariesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var linguee: LingueeDictionary
    private lateinit var infopedia: InfopediaDictionary

    private fun fixture(name: String): String = Goldens.fixtureText("../testdata/$name")

    @Before
    fun setUp() {
        Goldens.requireTestdata()
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        val base = server.url("/").toString().removeSuffix("/")
        linguee = LingueeDictionary(client, base)
        infopedia = InfopediaDictionary(client, base)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    path.startsWith("/portugues-ingles/traducao/mesa") ->
                        MockResponse().setBody(
                            Goldens.fixtureText("../testdata/lingpt/mesa.html", Charsets.ISO_8859_1)
                        )
                    path.startsWith("/dicionarios/lingua-portuguesa/mesa") ->
                        MockResponse().setBody(fixture("infopedia/mesa.html"))
                    else -> MockResponse().setResponseCode(404)
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

    @Test
    fun formerlySingleDictLanguagesNowCombine() {
        assertThat(MultiDict.canCombine(listOf(linguee, infopedia))).isTrue()

        // fr: Le Robert + French Wiktionary (same lang fr).
        val rob = LeRobertDictionary(client, server.url("/").toString())
        val wfr = FrWiktionary(client, server.url("/").toString())
        assertThat(MultiDict.canCombine(listOf(rob, wfr))).isTrue()

        // se: SO + SDO share one language too.
        val so = SoDictionary(client, server.url("/").toString())
        val sdo = SdoDictionary(client, server.url("/"), server.url("/"))
        assertThat(MultiDict.canCombine(listOf(so, sdo))).isTrue()

        // A cross-language mix is still rejected.
        assertThat(MultiDict.canCombine(listOf(linguee, rob))).isFalse()
    }

    @Test
    fun combinedFetchAggregatesLingueeAndInfopedia() {
        val sources = listOf(
            CombSource("LINGPT", server.url("/portugues-ingles/traducao/mesa.html")),
            CombSource("INFOPEDIA", server.url("/dicionarios/lingua-portuguesa/mesa"))
        )

        val combined = MultiDict.fetch(listOf(linguee, infopedia), sources, headword = "mesa")!!

        assertThat(combined.mTitle).isEqualTo("mesa")
        assertThat(combined.dictionary).isEqualTo("LINGPT")
        // Linguee's page carries two exact matches (mesa, mês); Infopédia one.
        assertThat(combined.mHomonymEntries.map { it.ref })
            .containsExactly("LINGPT::1", "LINGPT::2", "INFOPEDIA::1").inOrder()
        assertThat(combined.mHomonymEntries.map { it.dictionary })
            .containsExactly("LINGPT", "LINGPT", "INFOPEDIA").inOrder()
    }
}
