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
        val body = File("../testdata/infopedia-search.json").readText()
        server.enqueue(MockResponse().setBody(body))

        val results = dictionary.search("mesa")

        assertThat(results).hasSize(5)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        // Search results stay on the same host (the MockWebServer).
        assertThat(results[0].uri.toString()).contains("/dicionarios/lingua-portuguesa/mesa")
        assertThat(results[3].mTitle).isEqualTo("mesada")

        val request = server.takeRequest()
        assertThat(request.path).contains("/dicionarios/lingua-portuguesa/sugestao-pesquisa/mesa")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/infopedia/mesa.html").readText()
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
}