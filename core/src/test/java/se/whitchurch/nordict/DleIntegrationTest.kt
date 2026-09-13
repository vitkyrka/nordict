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

class DleIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: DleDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        dictionary = DleDictionary(client, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = File("../testdata/dle-search.json").readText()
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("frente")

        assertThat(results).hasSize(2)
        assertThat(results[0].mTitle).isEqualTo("frente")
        assertThat(results[0].uri.toString()).contains("/frente")
        assertThat(results[1].mTitle).isEqualTo("frentero")
        assertThat(results[1].uri.toString()).contains("/frentero")

        val request = server.takeRequest()
        assertThat(request.path).contains("/srv/keys?q=frente")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/dle.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/frente")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("frente")
        assertThat(word?.definitions).isNotEmpty()
        assertThat(word?.renderAsJson).isTrue()
        assertThat(word?.etymology).isNotEmpty()
    }

    @Test
    fun testGetHomonyms() {
        // A homonym page: two <article> lemmas sharing the same headword.
        val html = """
            <!DOCTYPE html><html><head></head><body>
            <div id="resultados">
            <article>
                <header><h1>cura</h1></header>
                <div class="n2 c-text-intro">Del lat. curas.</div>
                <ol class="c-definitions">
                    <li><div class="c-definitions__item">
                        <div><span class="n_acep">1</span><abbr title="nombre femenino">f.</abbr> Cuidado de algo.</div>
                    </div></li>
                </ol>
            </article>
            <article>
                <header><h1>cura</h1></header>
                <div class="n2 c-text-intro">Del lat. cura.</div>
                <ol class="c-definitions">
                    <li><div class="c-definitions__item">
                        <div><span class="n_acep">1</span><abbr title="nombre masculino">m.</abbr> Sacerdote católico.</div>
                    </div></li>
                </ol>
            </article>
            </div>
            </body></html>
        """.trimIndent()

        // Default URL resolves to the first homonym and carries both entries.
        server.enqueue(MockResponse().setBody(html))
        val uri: HttpUrl = server.url("/cura")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("cura")
        assertThat(word?.mHomonymEntries).hasSize(2)
        assertThat(word?.mHomonymEntries?.map { it.ref }).containsExactly("1", "2").inOrder()
        assertThat(word?.mHomonymEntries?.get(0)?.definitions?.get(0)?.grammar)
            .isEqualTo("nombre femenino")
        assertThat(word?.mHomonymEntries?.get(1)?.definitions?.get(0)?.grammar)
            .isEqualTo("nombre masculino")

        // __ref selects the second homonym; the entry list still covers both.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = uri.newBuilder().addQueryParameter("__ref", "2").build()
        val word2 = dictionary.get(refUri)

        assertThat(word2).isNotNull()
        assertThat(word2?.mTitle).isEqualTo("cura")
        assertThat(word2?.mHomonymEntries).hasSize(2)
        assertThat(word2?.mHomonymEntries?.get(1)?.definitions?.get(0)?.glosses?.get(0)?.definition)
            .isEqualTo("Sacerdote católico.")
    }
}