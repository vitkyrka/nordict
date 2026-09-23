package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class LeRobertIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: LeRobertDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        dictionary = LeRobertDictionary(client, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = Goldens.fixtureText("../testdata/rob-search.json")
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("table")

        // Only definition entries are returned: the conjugation and synonyms
        // views are dropped, so the headword resolves to a unique exact match.
        assertThat(results).hasSize(4)
        assertThat(results[0].mTitle).isEqualTo("table")
        // Search results stay on the same host (the MockWebServer).
        assertThat(results[0].uri.toString()).contains("/definition/table")
        assertThat(results.any { it.uri.toString().contains("/synonymes/") }).isFalse()
        assertThat(results.any { it.uri.toString().contains("/conjugaison/") }).isFalse()

        val exact = ExactMatch.resolve("table", results)
        assertThat(exact).isNotNull()
        assertThat(exact!!.uri.toString()).contains("/definition/table")

        val request = server.takeRequest()
        assertThat(request.path).contains("/autocomplete.json?q=table")
    }

    @Test
    fun testGet() {
        val html = Goldens.fixtureText("../testdata/rob/table.html")
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/definition/table")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("table")
        assertThat(word?.definitions).isNotEmpty()
        assertThat(word?.definitions?.get(0)?.definition).contains("Meuble sur pied")
        assertThat(word?.idioms).isNotEmpty()
    }

    @Test
    fun testGetHomographRef() {
        val html = Goldens.fixtureText("../testdata/rob/table.html")

        // A __ref=2 URL still resolves (the fixture has a single entry, so the
        // parser falls back to the first word rather than failing).
        server.enqueue(MockResponse().setBody(html))
        val refUri = server.url("/definition/table").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val word = dictionary.get(refUri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("table")

        // A plain URL resolves the single word.
        server.enqueue(MockResponse().setBody(html))
        val mainUri = server.url("/definition/table")
        val word2 = dictionary.get(mainUri)

        assertThat(word2).isNotNull()
        assertThat(word2?.mTitle).isEqualTo("table")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/definition/table").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }
}