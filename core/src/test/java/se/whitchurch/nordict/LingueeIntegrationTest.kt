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

class LingueeIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: LingueeDictionary
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        baseUrl = server.url("/").toString().removeSuffix("/")
        dictionary = LingueeDictionary(client, baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val body = File("../testdata/lingpt-search.json").readText(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setBody(body))

        val results = dictionary.search("mesa")

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("mesa")
        // Search results stay on the same host (the MockWebServer).
        assertThat(results[0].uri.toString()).contains("/portugues-ingles/traducao/mesa.html")
        assertThat(results[1].mTitle).isEqualTo("mesa redonda")

        val request = server.takeRequest()
        assertThat(request.path).contains("/portugues-ingles/search")
        assertThat(request.path).contains("qe=mesa")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/lingpt/mesa.html").readText(Charsets.ISO_8859_1)
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/portugues-ingles/traducao/mesa.html")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("mesa")
        assertThat(word?.renderAsJson).isTrue()
        assertThat(word?.definitions).hasSize(2)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition).isEqualTo("table")
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.examples).hasSize(2)
        assertThat(word?.audio).hasSize(1)
        assertThat(word?.audio?.get(0)).contains("/PT_PT/")
    }

    @Test
    fun testGetRefResolvesSecondEntry() {
        val html = File("../testdata/lingpt/mesa.html").readText(Charsets.ISO_8859_1)

        // Default (no __ref) -> the first exact match on the page.
        server.enqueue(MockResponse().setBody(html))
        val mainUri: HttpUrl = server.url("/portugues-ingles/traducao/mesa.html")
        val main = dictionary.get(mainUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("mesa")
        // The combined page carries both exact matches (mesa, mês).
        assertThat(main?.mHomonymEntries).hasSize(2)
        assertThat(main?.mHomonymEntries?.map { it.mTitle })
            .containsExactly("mesa", "mês").inOrder()

        // __ref=2 -> the "mês" spelling-variant entry.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/portugues-ingles/traducao/mesa.html").newBuilder()
            .addQueryParameter("__ref", "2").build()
        val mes = dictionary.get(refUri)
        assertThat(mes).isNotNull()
        assertThat(mes?.mTitle).isEqualTo("mês")
        assertThat(mes?.definitions?.get(0)?.gender).isEqualTo(Genders.MASCULINE)
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/portugues-ingles/traducao/mesa.html").newBuilder()
            .host("somewhere-else.example").build()
        assertThat(dictionary.get(uri)).isNull()
    }
}