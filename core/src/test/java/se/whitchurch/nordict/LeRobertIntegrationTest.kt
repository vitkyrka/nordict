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
        val json = File("../testdata/rob-search.json").readText()
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("table")

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("table")
        // Search results stay on the same host (the MockWebServer).
        assertThat(results[0].uri.toString()).contains("/definition/table")
        // Conjugaison results are rewritten to the definition view.
        assertThat(results[1].uri.toString()).contains("/definition/tabler")

        val request = server.takeRequest()
        assertThat(request.path).contains("/autocomplete.json?q=table")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/rob/table.html").readText()
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
        val html = File("../testdata/rob/table.html").readText()

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