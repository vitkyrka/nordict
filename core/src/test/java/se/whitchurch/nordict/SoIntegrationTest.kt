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
 * Fake-network integration tests for the SO dictionary against the
 * `svenska.se` JSON API (autocomplete + article endpoints), exercised via the
 * `baseUrl` constructor param (the app keeps the production default).
 */
class SoIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var dictionary: SoDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dictionary = SoDictionary(OkHttpClient(), server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val search = Goldens.fixtureText("../testdata/so-search.json")
        server.enqueue(MockResponse().setBody(search))

        val results = dictionary.search("kutter")

        assertThat(results).hasSize(7)
        assertThat(results[0].mTitle).isEqualTo("kutter")
        assertThat(results[0].mSummary).isEqualTo("substantiv")
        // uris point straight at the article API endpoint.
        assertThat(results.map { it.uri.toString() })
            .contains(server.url("/api/article/so/221211").toString())

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/autocomplete?q=kutter&size=10")
    }

    @Test
    fun testGet() {
        val article = Goldens.fixtureText("../testdata/so/hus.article.json")
        server.enqueue(MockResponse().setBody(article))

        val uri = server.url("/api/article/so/185690")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("hus")
        assertThat(word?.pos).isEqualTo(Pos.NOUN)
        assertThat(word?.definitions).hasSize(3)
        assertThat(word?.idioms).hasSize(12)
        assertThat(word?.audio).containsExactly(
            "https://isolve-so-service.appspot.com/pronounce?id=185690_1.mp3"
        )

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/article/so/185690")
    }

    @Test
    fun testGetCanonicalSiteUrl() {
        // A legacy ?activeTab=so&q=<word> page (or any url carrying `id`) resolves
        // to the numbered article.
        val article = Goldens.fixtureText("../testdata/so/kutter.article.json")
        server.enqueue(MockResponse().setBody(article))

        val uri = server.url("/so/")
            .newBuilder()
            .addQueryParameter("activeTab", "so")
            .addQueryParameter("q", "kutter")
            .addQueryParameter("id", "221211")
            .build()
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("kutter")
        assertThat(word?.pronunciation).isEqualTo("kutt´er")

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/article/so/221211")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/api/article/so/185690").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
        assertThat(server.requestCount).isEqualTo(0)
    }
}