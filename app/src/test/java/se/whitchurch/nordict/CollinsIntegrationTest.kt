package se.whitchurch.nordict

import android.net.Uri
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
class CollinsIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: CollinsSpanishEnglishDictionary
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        baseUrl = server.url("/").toString().removeSuffix("/")
        dictionary = CollinsSpanishEnglishDictionary(client, baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = File("../testdata/colspan-search.json").readText()
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("cagar")

        assertThat(results).hasSize(3)
        assertThat(results[0].mTitle).isEqualTo("cagar")
        assertThat(results[0].uri.toString()).contains("/dictionary/spanish-english/cagar")

        val request = server.takeRequest()
        assertThat(request.path).contains("/autocomplete/")
        assertThat(request.path).contains("dictCode=spanish-english")
        assertThat(request.path).contains("q=cagar")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/colspan/morir.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri = Uri.parse("$baseUrl/dictionary/spanish-english/morir")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("morir")
        assertThat(word?.dictionary).isEqualTo("Collins Spanish-English")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.pos).isEqualTo("intransitive verb")
        assertThat(word?.definitions?.get(0)?.idioms).isEmpty()
        assertThat(word?.definitions?.get(0)?.phrases).isEmpty()
        assertThat(word?.definitions?.get(0)?.glosses).hasSize(2)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.idioms).hasSize(1)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.phrases).hasSize(5)
        assertThat(word?.audio).hasSize(2)
    }

    @Test
    fun testGetRefResolvesEasyLearning() {
        val html = File("../testdata/colspan/frente.html").readText()

        // Default (no __ref) -> main dictionary headword.
        server.enqueue(MockResponse().setBody(html))
        val mainUri = Uri.parse("$baseUrl/dictionary/spanish-english/frente")
        val main = dictionary.get(mainUri)
        assertThat(main).isNotNull()
        assertThat(main?.mTitle).isEqualTo("frente")
        assertThat(main?.dictionary).isEqualTo("Collins Spanish-English")

        // __ref=1 -> the first easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri = Uri.parse("$baseUrl/dictionary/spanish-english/frente").buildUpon()
            .appendQueryParameter("__ref", "1").build()
        val easy = dictionary.get(refUri)
        assertThat(easy).isNotNull()
        assertThat(easy?.mTitle).isEqualTo("la frente")
        assertThat(easy?.dictionary).isEqualTo("Collins Easy Learning")

        // __ref=2 -> the second easy-learning headword.
        server.enqueue(MockResponse().setBody(html))
        val refUri2 = Uri.parse("$baseUrl/dictionary/spanish-english/frente").buildUpon()
            .appendQueryParameter("__ref", "2").build()
        val easy2 = dictionary.get(refUri2)
        assertThat(easy2).isNotNull()
        assertThat(easy2?.mTitle).isEqualTo("el frente")
        assertThat(easy2?.dictionary).isEqualTo("Collins Easy Learning")
    }
}
