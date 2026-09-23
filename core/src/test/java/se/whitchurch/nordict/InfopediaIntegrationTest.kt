package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class InfopediaIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: InfopediaDictionary
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        baseUrl = server.url("/").toString().removeSuffix("/")
        dictionary = InfopediaDictionary(client, baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val body = Goldens.fixtureText("../testdata/infopedia-search.json")
        server.enqueue(MockResponse().setBody(body))

        val results = dictionary.search("mesa")

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        // Search results stay on the same host (the MockWebServer).
        assertThat(results[0].uri.toString()).contains("/dicionarios/lingua-portuguesa/mesa")
        assertThat(results[3].mTitle).isEqualTo("mesada")

        val request = server.takeRequest()
        assertThat(request.path).contains("/dicionarios/lingua-portuguesa/sugestao-pesquisa/mesa")
        // The endpoint serves its {"html": ...} JSON only to XHR callers (the
        // site's own jQuery dataType: "json" request); without these a plain
        // page load gets the full word-page HTML instead.
        assertThat(request.getHeader("X-Requested-With")).isEqualTo("XMLHttpRequest")
        assertThat(request.getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun testGet() {
        val html = Goldens.fixtureText("../testdata/infopedia/mesa.html")
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/dicionarios/lingua-portuguesa/mesa")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("mesa")
        assertThat(word?.definitions).hasSize(12)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition).contains("móvel")
        assertThat(word?.idioms).hasSize(8)
        assertThat(word?.audio).hasSize(1)
        assertThat(word?.audio?.get(0)).contains("/tts/word/mesa")
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/dicionarios/lingua-portuguesa/mesa").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }

    @Test
    fun testGetFallsBackOnChallenge() {
        // www.infopedia.pt answers plain HTTP clients with a Cloudflare 403;
        // the app's FallbackPageFetcher retries in the hidden WebView, whose
        // rendered DOM parses exactly like the OkHttp body.
        val html = Goldens.fixtureText("../testdata/infopedia/mesa.html")
        val challenged = InfopediaDictionary(
            client, "https://www.infopedia.pt",
            FallbackPageFetcher(
                PageFetcher { _, _ -> PageResult(403, "Just a moment...") },
                PageFetcher { _, _ -> PageResult(200, html) }
            )
        )

        val uri = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa".toHttpUrlOrNull()!!
        val word = challenged.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("mesa")
        assertThat(word?.definitions).hasSize(12)
        assertThat(word?.idioms).hasSize(8)
    }

    @Test
    fun testSearchFallsBackOnChallenge() {
        val body = Goldens.fixtureText("../testdata/infopedia-search.json")
        val seen = ArrayList<Map<String, String>>()
        val challenged = InfopediaDictionary(
            client, "https://www.infopedia.pt",
            FallbackPageFetcher(
                PageFetcher { _, headers -> seen.add(headers); PageResult(403, "Just a moment...") },
                PageFetcher { _, headers -> seen.add(headers); PageResult(200, body) }
            )
        )

        val results = challenged.search("mesa")

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        // The XHR headers reach both transports, so the WebView fallback
        // answers JSON too instead of the full word page.
        assertThat(seen).hasSize(2)
        for (headers in seen) {
            assertThat(headers["X-Requested-With"]).isEqualTo("XMLHttpRequest")
            assertThat(headers["Accept"]).isEqualTo("application/json")
        }
    }
}