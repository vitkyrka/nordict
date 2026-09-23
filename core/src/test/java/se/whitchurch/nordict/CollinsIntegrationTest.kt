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

class CollinsIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: CollinsSpanishEnglishDictionary
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        baseUrl = server.url("/").toString().removeSuffix("/")
        dictionary = CollinsSpanishEnglishDictionary(client, baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = Goldens.fixtureText("../testdata/colspan-search.json")
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("cagar")

        assertThat(results).hasSize(4)
        assertThat(results[0].mTitle).isEqualTo("cagar")
        assertThat(results[0].uri.toString()).contains("/dictionary/spanish-english/cagar")

        // Multi-word suggestions must be hyphenated into Collins's canonical
        // slug, or the entry page 301s to a spellcheck page and fails to load.
        val multiWord = results.first { it.mTitle == "efectivo en caja" }
        assertThat(multiWord.uri.toString())
            .isEqualTo("$baseUrl/dictionary/spanish-english/efectivo-en-caja")

        val request = server.takeRequest()
        assertThat(request.path).contains("/autocomplete/")
        assertThat(request.path).contains("dictCode=spanish-english")
        assertThat(request.path).contains("q=cagar")
    }

    @Test
    fun testSearchTrimsSurroundingWhitespace() {
        val json = Goldens.fixtureText("../testdata/colspan-search.json")
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("  cagar  ")

        assertThat(results).isNotEmpty()
        assertThat(results[0].mTitle).isEqualTo("cagar")

        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("q")).isEqualTo("cagar")
    }

    @Test
    fun testSearchBlankReturnsEmptyWithoutRequest() {
        val results = dictionary.search("   ")

        assertThat(results).isEmpty()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun testGet() {
        val html = Goldens.fixtureText("../testdata/colspan/morir.html")
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/dictionary/spanish-english/morir")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        // Default (no __ref) -> the first easy-learning headword.
        assertThat(word?.mTitle).isEqualTo("morir")
        assertThat(word?.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.pos).isEqualTo("verb")
        assertThat(word?.audio).isEmpty()
    }

    @Test
    fun testGetRefResolvesMainHeadword() {
        val html = Goldens.fixtureText("../testdata/colspan/morir.html")

        // __ref=2 -> the first main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/spanish-english/morir").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val main = dictionary.get(refUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("morir")
        assertThat(main?.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(main?.definitions).hasSize(1)
        assertThat(main?.definitions?.get(0)?.pos).isEqualTo("intransitive verb")
        assertThat(main?.definitions?.get(0)?.idioms).isEmpty()
        assertThat(main?.definitions?.get(0)?.phrases).isEmpty()
        assertThat(main?.definitions?.get(0)?.glosses).hasSize(2)
        assertThat(main?.definitions?.get(0)?.glosses?.get(0)?.idioms).hasSize(1)
        assertThat(main?.definitions?.get(0)?.glosses?.get(0)?.phrases).hasSize(5)
        assertThat(main?.audio).hasSize(1)
    }

    @Test
    fun testGetRefResolvesEasyLearning() {
        val html = Goldens.fixtureText("../testdata/colspan/frente.html")

        // Default (no __ref) -> the first easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri: HttpUrl = server.url("/dictionary/spanish-english/frente")
        val main = dictionary.get(mainUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("la frente")
        assertThat(main?.dictionary).isEqualTo("Collins Easy Learning")
        // The combined page carries every headword (easy-learning + main).
        assertThat(main?.mHomonymEntries).hasSize(4)
        assertThat(main?.mHomonymEntries?.map { it.mTitle })
            .containsExactly("la frente", "el frente", "frente", "frente").inOrder()

        // __ref=3 -> the first main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/spanish-english/frente").newBuilder()
            .addQueryParameter("__ref", "3").build()
        val spanish = dictionary.get(refUri)
        assertThat(spanish).isNotNull()
        assertThat(spanish?.mTitle).isEqualTo("frente")
        assertThat(spanish?.dictionary).isEqualTo("Collins Spanish-English")

        // __ref=2 -> the second easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri2: HttpUrl = server.url("/dictionary/spanish-english/frente").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val easy2 = dictionary.get(refUri2)
        assertThat(easy2).isNotNull()
        assertThat(easy2?.mTitle).isEqualTo("el frente")
        assertThat(easy2?.dictionary).isEqualTo("Collins Easy Learning")
    }

    @Test
    fun testGetCrossReferenceEntry() {
        val html = Goldens.fixtureText("../testdata/colspan/ley+de+la+gravedad.html")

        // Default view -> the cross-reference stub headword.
        server.enqueue(MockResponse().setBody(html))
        val stubUri: HttpUrl = server.url("/dictionary/spanish-english/ley-de-la-gravedad")
        val stub = dictionary.get(stubUri)
        assertThat(stub).isNotNull()
        assertThat(stub?.mTitle).isEqualTo("ley de la gravedad")
        assertThat(stub?.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(stub?.definitions?.get(0)?.glosses).hasSize(1)
        assertThat(stub?.definitions?.get(0)?.glosses?.get(0)?.definition).contains("law of gravity")

        // __ref=2 -> the embedded full "ley" entry.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/spanish-english/ley-de-la-gravedad").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val ley = dictionary.get(refUri)
        assertThat(ley).isNotNull()
        assertThat(ley?.mTitle).isEqualTo("ley")
        assertThat(ley?.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(ley?.definitions?.get(0)?.pos).isEqualTo("feminine noun")
        assertThat(ley?.audio).hasSize(1)
    }
}