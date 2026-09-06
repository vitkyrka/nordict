package se.whitchurch.nordict

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
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
        val json = File("../testdata/est-search.json").readText()
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
        val html = File("../testdata/est.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri = Uri.parse(server.url("/frente").toString())
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("frente")
        assertThat(word?.definitions).hasSize(6)
        assertThat(word?.idioms).hasSize(14)

        assertThat(word?.definitions?.get(0)?.definition).contains("Parte superior de la cara")
        assertThat(word?.definitions?.get(0)?.examples).contains("Cayó de bruces y se hizo una herida en la frente.")

        assertThat(word?.idioms?.get(0)?.idiom).isEqualTo("al frente")
        assertThat(word?.idioms?.get(0)?.definition).contains("Hacia delante.")
    }

    @Test
    fun testGetSubEntry() {
        val html = File("../testdata/est/muerte.html").readText()

        // Search-result URL for a .sols sub-entry resolves to that headword.
        server.enqueue(MockResponse().setBody(html))
        val uri = Uri.parse(server.url("/muerte%20natural").toString())
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("muerte natural")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.idioms).isEmpty()

        // Homograph/ref URL selects another sub-entry.
        server.enqueue(MockResponse().setBody(html))
        val refUri = Uri.parse(server.url("/muerte").toString()).buildUpon()
            .appendQueryParameter("__ref", "3").build()
        val word2 = dictionary.get(refUri)

        assertThat(word2).isNotNull()
        assertThat(word2?.mTitle).isEqualTo("muerte violenta")
        assertThat(word2?.definitions).hasSize(1)

        // A plain main-page URL still resolves to the parent headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri = Uri.parse(server.url("/muerte").toString())
        val word3 = dictionary.get(mainUri)

        assertThat(word3).isNotNull()
        assertThat(word3?.mTitle).isEqualTo("muerte")
        assertThat(word3?.definitions).hasSize(3)
        assertThat(word3?.idioms).hasSize(7)
    }

    @Test
    fun testRegistration() {
        Ordboken.reset()
        val ordboken = Ordboken.getInstance(ApplicationProvider.getApplicationContext(), client)
        assertThat(ordboken.client).isSameInstanceAs(client)
        assertThat(ordboken.dictMap).containsKey("EST")
        val est = ordboken.dictMap["EST"]
        assertThat(est).isNotNull()
        assertThat(est).isInstanceOf(EstDictionary::class.java)
        assertThat(est?.tag).isEqualTo("EST")
        assertThat(est?.lang).isEqualTo("es")
    }
}
