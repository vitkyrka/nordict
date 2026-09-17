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
        val json = File("../testdata/colspan-search.json").readText()
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
        val json = File("../testdata/colspan-search.json").readText()
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
        val html = File("../testdata/colspan/morir.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/dictionary/spanish-english/morir")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("morir")
        assertThat(word?.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.pos).isEqualTo("intransitive verb")
        assertThat(word?.definitions?.get(0)?.idioms).isEmpty()
        assertThat(word?.definitions?.get(0)?.phrases).isEmpty()
        assertThat(word?.definitions?.get(0)?.glosses).hasSize(2)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.idioms).hasSize(1)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.phrases).hasSize(5)
        assertThat(word?.audio).hasSize(1)
    }

    @Test
    fun testGetRefResolvesEasyLearning() {
        val html = File("../testdata/colspan/frente.html").readText()

        // Default (no __ref) -> main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri: HttpUrl = server.url("/dictionary/spanish-english/frente")
        val main = dictionary.get(mainUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("frente")
        assertThat(main?.dictionary).isEqualTo("Collins Spanish-English")
        // The combined page carries every headword (main + easy-learning).
        assertThat(main?.mHomonymEntries).hasSize(4)
        assertThat(main?.mHomonymEntries?.map { it.mTitle })
            .containsExactly("frente", "frente", "la frente", "el frente").inOrder()

        // __ref=1 -> the first easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/spanish-english/frente").newBuilder()
            .addQueryParameter("__ref", "1").build()
        val easy = dictionary.get(refUri)
        assertThat(easy).isNotNull()
        assertThat(easy?.mTitle).isEqualTo("la frente")
        assertThat(easy?.dictionary).isEqualTo("Collins Easy Learning")

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
        val html = File("../testdata/colspan/ley+de+la+gravedad.html").readText()

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