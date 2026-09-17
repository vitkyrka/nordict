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

class ColfrenIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: CollinsFrenchEnglishDictionary
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        baseUrl = server.url("/").toString().removeSuffix("/")
        dictionary = CollinsFrenchEnglishDictionary(client, baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = File("../testdata/colfren-search.json").readText()
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("table")

        assertThat(results).hasSize(6)
        assertThat(results[0].mTitle).isEqualTo("table")
        assertThat(results[0].uri.toString()).contains("/dictionary/french-english/table")

        // Multi-word suggestions must be hyphenated into Collins's canonical
        // slug, or the entry page 301s to a spellcheck page and fails to load.
        val multiWord = results.first { it.mTitle == "table basse" }
        assertThat(multiWord.uri.toString())
            .isEqualTo("$baseUrl/dictionary/french-english/table-basse")

        val request = server.takeRequest()
        assertThat(request.path).contains("/autocomplete/")
        assertThat(request.path).contains("dictCode=french-english")
        assertThat(request.path).contains("q=table")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/colfren/table.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/dictionary/french-english/table")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        // Default (no __ref) -> the easy-learning headword.
        assertThat(word?.mTitle).isEqualTo("la table")
        assertThat(word?.dictionary).isEqualTo("Collins Easy Learning")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.pos).isEqualTo("feminine noun")
        assertThat(word?.definitions?.get(0)?.glosses).hasSize(1)
        assertThat(word?.audio).hasSize(1)
        assertThat(word?.audio?.get(0)).contains("/fr_")
    }

    @Test
    fun testGetRefResolvesMainHeadword() {
        val html = File("../testdata/colfren/table.html").readText()

        // __ref=2 -> the main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/french-english/table").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val main = dictionary.get(refUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("table")
        assertThat(main?.dictionary).isEqualTo("Collins French-English")
        assertThat(main?.audio).hasSize(1)
        assertThat(main?.audio?.get(0)).contains("FR-")
    }

    @Test
    fun testGetRefResolvesEasyLearning() {
        val html = File("../testdata/colfren/table.html").readText()

        // Default (no __ref) -> easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri: HttpUrl = server.url("/dictionary/french-english/table")
        val main = dictionary.get(mainUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("la table")
        assertThat(main?.dictionary).isEqualTo("Collins Easy Learning")
        // The combined page carries every headword (easy-learning + main).
        assertThat(main?.mHomonymEntries).hasSize(2)
        assertThat(main?.mHomonymEntries?.map { it.mTitle })
            .containsExactly("la table", "table").inOrder()

        // __ref=2 -> the main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/dictionary/french-english/table").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val easy = dictionary.get(refUri)
        assertThat(easy).isNotNull()
        assertThat(easy?.mTitle).isEqualTo("table")
        assertThat(easy?.dictionary).isEqualTo("Collins French-English")
        assertThat(easy?.audio).hasSize(1)
        assertThat(easy?.audio?.get(0)).contains("FR-")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/dictionary/french-english/table").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }
}