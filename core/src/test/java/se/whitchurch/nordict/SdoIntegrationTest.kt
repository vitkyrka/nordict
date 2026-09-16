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
 * Fake-network integration tests for the SDO dictionary: both the API host
 * (`ws.dsl.dk`) and the site host (`ordnet.dk`) route through one
 * MockWebServer, exercised via the `apiBaseUrl`/`siteBaseUrl` constructor
 * params (the app keeps the production defaults).
 */
class SdoIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: SdoDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        val base = server.url("/")
        dictionary = SdoDictionary(client, base, base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val query = File("../testdata/sdo/hus.html").readText()
        val search = File("../testdata/sdo-search.json").readText()
        server.enqueue(MockResponse().setBody(query))
        server.enqueue(MockResponse().setBody(search))

        val results = dictionary.search("hus")

        // SDO query pages carry no `.ar` inflected matches, so the 50
        // livesearch words are the whole result, deduped by title.
        assertThat(results).hasSize(50)
        assertThat(results[0].mTitle).isEqualTo("hus")
        assertThat(results[0].uri.toString()).contains("/sdo/query")

        val queryRequest = server.takeRequest()
        assertThat(queryRequest.path).isEqualTo("/sdo/query?q=hus&app=android&version=2.1.5")

        val searchRequest = server.takeRequest()
        assertThat(searchRequest.path).isEqualTo("/sdo/livesearch?text=hus&size=50")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/sdo/hus.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/sdo/ordbog")
            .newBuilder()
            .addQueryParameter("entry_id", "88115759")
            .addQueryParameter("query", "hus")
            .build()

        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("hus")
        assertThat(word?.pos).isEqualTo(Pos.NOUN)
        assertThat(word?.definitions).isNotEmpty()
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition).isNotEmpty()
        assertThat(word?.idioms).isNotEmpty()
    }

    @Test
    fun testGetApiWord() {
        val query = File("../testdata/sdo/skaffa.html").readText()
        val html = File("../testdata/sdo/skaffa.html").readText()
        server.enqueue(MockResponse().setBody(query))
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/sdo/query")
            .newBuilder()
            .addQueryParameter("app", "android")
            .addQueryParameter("version", "2.1.5")
            .addQueryParameter("q", "skaffa")
            .build()

        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("skaffa")

        server.takeRequest()
        val entryRequest = server.takeRequest()
        assertThat(entryRequest.path)
            .isEqualTo("/sdo/ordbog?entry_id=88135215&query=.")
    }

    @Test
    fun testGetRehostsSiteQueryUrl() {
        // A `query?q=` URL resolved against a main-site word uri comes back on
        // the site host; get() must re-host it onto the api base.
        val query = File("../testdata/sdo/skaffa.html").readText()
        server.enqueue(MockResponse().setBody(query))
        server.enqueue(MockResponse().setBody(query))

        val uri = server.url("/sdo/ordbog")
            .newBuilder()
            .addQueryParameter("entry_id", "88135215")
            .addQueryParameter("query", ".")
            .build()
        // SDO synonym links resolve `query?q=...` against the word's page
        // uri. When the word lives on the site base, the result is a *site*
        // query URL; get() re-hosts it onto the api base and resolves it
        // (in the test both bases share one MockWebServer, so the request
        // still lands on the server).
        val siteQueryUri = server.url("/sdo/query")
            .newBuilder()
            .addQueryParameter("q", "skaffa")
            .build()

        val word = dictionary.get(siteQueryUri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("skaffa")

        val queryRequest = server.takeRequest()
        assertThat(queryRequest.path).isEqualTo("/sdo/query?q=skaffa")
        val entryRequest = server.takeRequest()
        assertThat(entryRequest.path).isEqualTo("/sdo/ordbog?entry_id=88135215&query=.")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/sdo/ordbog?entry_id=88115759").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }
}