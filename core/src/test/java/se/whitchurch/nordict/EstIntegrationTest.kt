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

class EstIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: EstDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        dictionary = EstDictionary(client, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = Goldens.fixtureText("../testdata/est-search.json")
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("frente")

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("frente")
        assertThat(results[0].uri.toString()).contains("/frente")
        assertThat(results[4].mTitle).isEqualTo("al frente")
        assertThat(results[4].uri.toString()).contains("/al%20frente")

        val request = server.takeRequest()
        assertThat(request.path).contains("/srv/keys?q=frente")
    }

    @Test
    fun testGet() {
        val html = Goldens.fixtureText("../testdata/est.html")
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/frente")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("frente")
        assertThat(word?.definitions).hasSize(6)
        assertThat(word?.idioms).hasSize(9)

        assertThat(word?.definitions?.get(0)?.definition).contains("Parte superior de la cara")
        assertThat(word?.definitions?.get(0)?.examples).contains("Cayó de bruces y se hizo una herida en la frente.")

        assertThat(word?.idioms?.get(0)?.idiom).isEqualTo("al frente")
        assertThat(word?.idioms?.get(0)?.definition).contains("Hacia delante.")
    }

    @Test
    fun testGetSubEntry() {
        val html = Goldens.fixtureText("../testdata/est/muerte.html")

        // Search-result URL for a .sols sub-entry resolves to that headword.
        server.enqueue(MockResponse().setBody(html))
        val uri: HttpUrl = server.url("/muerte%20natural")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("muerte natural")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.idioms).isEmpty()

        // All page entries are attached for the combined in-page rendering.
        assertThat(word?.mHomonymEntries).hasSize(3)
        assertThat(word?.mHomonymEntries?.map { it.mTitle })
            .containsExactly("muerte", "muerte natural", "muerte violenta").inOrder()
        assertThat(word?.mHomonymEntries?.map { it.ref })
            .containsExactly("1", "2", "3").inOrder()
        assertThat(word?.mHomonymEntries?.get(2)?.mTitle).isEqualTo("muerte violenta")

        // Homograph/ref URL selects another sub-entry.
        server.enqueue(MockResponse().setBody(html))
        val refUri: HttpUrl = server.url("/muerte").newBuilder()
            .addQueryParameter("__ref", "3").build()
        val word2 = dictionary.get(refUri)

        assertThat(word2).isNotNull()
        assertThat(word2?.mTitle).isEqualTo("muerte violenta")
        assertThat(word2?.definitions).hasSize(1)

        // A plain main-page URL still resolves to the parent headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri: HttpUrl = server.url("/muerte")
        val word3 = dictionary.get(mainUri)

        assertThat(word3).isNotNull()
        assertThat(word3?.mTitle).isEqualTo("muerte")
        assertThat(word3?.definitions).hasSize(3)
        assertThat(word3?.idioms).hasSize(6)
    }
}