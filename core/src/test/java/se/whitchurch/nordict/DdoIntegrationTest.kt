package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Fake-network integration tests for the DDO dictionary: both the API host
 * (`ws.dsl.dk`) and the site host (`ordnet.dk`) route through one
 * MockWebServer, exercised via the `apiBaseUrl`/`siteBaseUrl` constructor
 * params (the app keeps the production defaults).
 */
class DdoIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: DdoDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        val base = server.url("/")
        dictionary = DdoDictionary(client, base, base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val query = File("../testdata/ddo-query.html").readText()
        val search = File("../testdata/ddo-search.json").readText()
        server.enqueue(MockResponse().setBody(query))
        server.enqueue(MockResponse().setBody(search))

        val results = dictionary.search("arbejde")

        // 1 inflected-form match + 7 livesearch words, deduped by title.
        assertThat(results).hasSize(7)
        // The inflected match points at the main-site entry page.
        assertThat(results[0].uri.toString()).contains("/ddo/ordbog?entry_id=10828335")
        // Livesearch words point at the api query page.
        assertThat(results[1].uri.toString())
            .isEqualTo(server.url("/ddo/query?app=android&version=2.1.5&q=arbejde%20frem").toString())

        val queryRequest = server.takeRequest()
        assertThat(queryRequest.path).isEqualTo("/ddo/query?q=arbejde&app=android&version=2.1.5")

        val searchRequest = server.takeRequest()
        assertThat(searchRequest.path).isEqualTo("/ddo/livesearch?text=arbejde&size=50")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/ddo/arbejde.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/ddo/ordbog")
            .newBuilder()
            .addQueryParameter("entry_id", "11002240")
            .addQueryParameter("query", "arbejde")
            .build()

        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("arbejde")
        assertThat(word?.renderAsJson).isTrue()
        assertThat(word?.definitions).isNotEmpty()
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition).isNotEmpty()
        assertThat(word?.idioms).isNotEmpty()
    }

    @Test
    fun testGetApiWord() {
        val query = File("../testdata/ddo-query.html").readText()
        val html = File("../testdata/ddo/arbejde.html").readText()
        server.enqueue(MockResponse().setBody(query))
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/ddo/query")
            .newBuilder()
            .addQueryParameter("app", "android")
            .addQueryParameter("version", "2.1.5")
            .addQueryParameter("q", "arbejdet")
            .build()

        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("arbejde")
        // Homograph links from the api page's .short-result block.
        assertThat(word?.mHomographs).hasSize(2)

        server.takeRequest()
        val entryRequest = server.takeRequest()
        assertThat(entryRequest.path)
            .isEqualTo("/ddo/ordbog?entry_id=10828335&query=.")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/ddo/ordbog?entry_id=10828335").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }
}