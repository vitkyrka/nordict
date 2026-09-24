package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class DiccionariIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun gdlc(): GdlcDictionary = GdlcDictionary(client, server.url("/").toString().removeSuffix("/"))
    private fun caes(): CatalaCastellaDictionary = CatalaCastellaDictionary(client, server.url("/").toString().removeSuffix("/"))
    private fun caen(): CatalaAnglesDictionary = CatalaAnglesDictionary(client, server.url("/").toString().removeSuffix("/"))

    // ---- GDLC (monolingual) ----

    @Test
    fun testGdlcSearch() {
        val dict = gdlc()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/gdlc-search.json")))

        val results = dict.search("cap")

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("cap.")
        assertThat(results[0].uri.toString()).contains("/GDLC/cap")
        assertThat(results[6].mTitle).isEqualTo("cap-pal")
        assertThat(results[6].uri.toString()).contains("/GDLC/cap-pal")

        val request = server.takeRequest()
        assertThat(request.path).contains("/search_api_autocomplete/diccionari_gdlc")
        assertThat(request.path).contains("q=cap")
    }

    @Test
    fun testGdlcFullSearch() {
        val dict = gdlc()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/gdlc/cap.html")))

        val results = dict.fullSearch("cap")

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("cap.")
        assertThat(results[1].mTitle).isEqualTo("cap")
        assertThat(results[1].mSummary).contains("Part superior del cos de l’home")
        assertThat(results[1].uri.toString()).contains("/GDLC/cap1")

        val request = server.takeRequest()
        assertThat(request.path).contains("/cerca/gran-diccionari-de-la-llengua-catalana")
        assertThat(request.path).contains("search_api_fulltext_cust=cap")
    }

    @Test
    fun testGdlcGet() {
        val dict = gdlc()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/gdlc/cap1.html")))

        val uri: HttpUrl = server.url("/GDLC/cap1")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("cap")
        assertThat(word?.definitions).hasSize(27)
        assertThat(word?.idioms).hasSize(137)
        assertThat(word?.definitions?.get(0)?.domain).isEqualTo("anatomia")
        assertThat(word?.definitions?.get(3)?.glosses?.get(0)?.examples)
            .containsExactly("Tenir cap. Tenir molt de cap.")
    }

    @Test
    fun testGdlcGetHomographRef() {
        // A numbered homograph ("cap2") served from the full search-view page
        // resolves via the __ref query parameter.
        val dict = gdlc()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/gdlc/cap.html")))

        val uri: HttpUrl = server.url("/GDLC/cap2?__ref=3")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.xrefs).contains("3")
        assertThat(word?.mHomonymEntries).hasSize(10)
    }

    // ---- CA-ES (bilingual) ----

    @Test
    fun testCaEsSearch() {
        val dict = caes()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-es-search.json")))

        val results = dict.search("taula")

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("taula")
        assertThat(results[0].uri.toString()).contains("/catala-castella/taula")
        // Prefix completions from the autocomplete resolve to entry URLs.
        assertThat(results[4].mTitle).isEqualTo("taulat")
        assertThat(results[4].uri.toString()).contains("/catala-castella/taulat")

        val request = server.takeRequest()
        assertThat(request.path).contains("/search_api_autocomplete/diccionari_ca_es_")
    }

    @Test
    fun testCaEsGet() {
        val dict = caes()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-es/taula.html")))

        val uri: HttpUrl = server.url("/catala-castella/taula")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("taula")
        assertThat(word?.definitions).hasSize(12)
        // Bilingual example pairing works through the full word path.
        assertThat(word?.definitions?.get(1)?.glosses?.get(0)?.examples)
            .contains("La bona taula és sovint danyosa per a la salut, la buena mesa es con frecuencia dañina para la salud.")
    }

    @Test
    fun testCaEsGetLocutionFromCombinedPage() {
        // Locution/proper-noun entries are served from the search-view page
        // that embeds all matching entries; the slug picks the right headword.
        val dict = caes()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-es/cap.html")))

        val uri: HttpUrl = server.url("/catala-castella/cap-verd")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("Cap Verd")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.domain).isEqualTo("geografia")
        assertThat(word?.mHomonymEntries).hasSize(9)
    }

    @Test
    fun testCaEsGetPuncturedLocutionFromCombinedPage() {
        // "Canaveral, cap" has a comma in its title but a plain slug:
        // normalization must match "canaveral-cap".
        val dict = caes()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-es/cap.html")))

        val uri: HttpUrl = server.url("/catala-castella/canaveral-cap")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("Canaveral, cap")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.grammar).isEqualTo("nom propi")
    }

    // ---- CA-ES cerca fallback (bare-completion 404s, e.g. repenjar) ----

    @Test
    fun testCaEsGetFallsBackToCercaWhenEntryMissing() {
        // Like DIDAC: a bare completion ("repenjar") 404s because the real
        // headword is "repenjar-se"; get() falls back to the cerca view.
        val dict = caes()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-es/cap.html")))

        val word = dict.get(server.url("/catala-castella/repenjar"))

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("cap")
        server.takeRequest() // the 404 entry fetch
        val fallback = server.takeRequest()
        assertThat(fallback.path).contains("/cerca/diccionari-catala-castella")
        assertThat(fallback.path).contains("search_api_fulltext_cust=repenjar")
    }

    @Test
    fun testCaEsGetReturnsNullWhenFallbackEmpty() {
        val dict = caes()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("<html><body></body></html>"))

        assertThat(dict.get(server.url("/catala-castella/zzznonexistent"))).isNull()
    }

    // ---- CA-EN (bilingual) ----

    @Test
    fun testCaEnSearch() {
        val dict = caen()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-en-search.json")))

        val results = dict.search("taula")

        assertThat(results).hasSize(6)
        assertThat(results[0].mTitle).isEqualTo("taula")
        assertThat(results[0].uri.toString()).contains("/catala-angles/taula")
        // Prefix completions from the autocomplete resolve to entry URLs.
        assertThat(results[5].mTitle).isEqualTo("taulat")
        assertThat(results[5].uri.toString()).contains("/catala-angles/taulat")

        val request = server.takeRequest()
        assertThat(request.path).contains("/search_api_autocomplete/diccionari_ca_en")
    }

    @Test
    fun testCaEnGet() {
        val dict = caen()
        server.enqueue(MockResponse().setBody(Goldens.fixtureText("../testdata/ca-en/taula.html")))

        val uri: HttpUrl = server.url("/catala-angles/taula")
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("taula")
        assertThat(word?.definitions).hasSize(14)
        assertThat(word?.definitions?.get(8)?.gender).isEqualTo(Genders.FEMININE)
    }
}