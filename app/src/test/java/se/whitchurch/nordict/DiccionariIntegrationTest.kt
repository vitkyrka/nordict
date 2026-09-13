package se.whitchurch.nordict

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
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
        server.enqueue(MockResponse().setBody(File("../testdata/gdlc-search.json").readText()))

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
        server.enqueue(MockResponse().setBody(File("../testdata/gdlc/cap.html").readText()))

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
        server.enqueue(MockResponse().setBody(File("../testdata/gdlc/cap1.html").readText()))

        val uri = Uri.parse(server.url("/GDLC/cap1").toString())
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
        server.enqueue(MockResponse().setBody(File("../testdata/gdlc/cap.html").readText()))

        val uri = Uri.parse(server.url("/GDLC/cap2?__ref=3").toString())
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.xrefs).contains("3")
        assertThat(word?.mHomonymEntries).hasSize(10)
    }

    // ---- CA-ES (bilingual) ----

    @Test
    fun testCaEsSearch() {
        val dict = caes()
        server.enqueue(MockResponse().setBody(File("../testdata/ca-es-search.json").readText()))

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
        server.enqueue(MockResponse().setBody(File("../testdata/ca-es/taula.html").readText()))

        val uri = Uri.parse(server.url("/catala-castella/taula").toString())
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
        server.enqueue(MockResponse().setBody(File("../testdata/ca-es/cap.html").readText()))

        val uri = Uri.parse(server.url("/catala-castella/cap-verd").toString())
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
        server.enqueue(MockResponse().setBody(File("../testdata/ca-es/cap.html").readText()))

        val uri = Uri.parse(server.url("/catala-castella/canaveral-cap").toString())
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("Canaveral, cap")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.grammar).isEqualTo("nom propi")
    }

    // ---- CA-EN (bilingual) ----

    @Test
    fun testCaEnSearch() {
        val dict = caen()
        server.enqueue(MockResponse().setBody(File("../testdata/ca-en-search.json").readText()))

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
        server.enqueue(MockResponse().setBody(File("../testdata/ca-en/taula.html").readText()))

        val uri = Uri.parse(server.url("/catala-angles/taula").toString())
        val word = dict.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("taula")
        assertThat(word?.definitions).hasSize(14)
        assertThat(word?.definitions?.get(8)?.gender).isEqualTo(Genders.FEMININE)
    }

    @Test
    fun testRegistration() {
        Ordboken.reset()
        val ordboken = Ordboken.getInstance(ApplicationProvider.getApplicationContext(), client)
        for ((tag, cls) in listOf(
            "GDLC" to GdlcDictionary::class.java,
            "CA-ES" to CatalaCastellaDictionary::class.java,
            "CA-EN" to CatalaAnglesDictionary::class.java
        )) {
            assertThat(ordboken.dictMap).containsKey(tag)
            val dict = ordboken.dictMap[tag]
            assertThat(dict).isNotNull()
            assertThat(dict).isInstanceOf(cls)
            assertThat(dict?.tag).isEqualTo(tag)
            assertThat(dict?.lang).isEqualTo("ca")
        }
    }
}