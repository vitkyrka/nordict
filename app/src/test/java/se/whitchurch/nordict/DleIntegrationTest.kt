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

        val uri = Uri.parse(server.url("/frente").toString())
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("frente")
        assertThat(word?.definitions).isNotEmpty()
    }

    @Test
    fun testRegistration() {
        Ordboken.reset()
        val ordboken = Ordboken.getInstance(ApplicationProvider.getApplicationContext(), client)
        assertThat(ordboken.client).isSameInstanceAs(client)
        assertThat(ordboken.dictMap).containsKey("DLE")
        val dle = ordboken.dictMap["DLE"]
        assertThat(dle).isNotNull()
        assertThat(dle).isInstanceOf(DleDictionary::class.java)
        assertThat(dle?.tag).isEqualTo("DLE")
        assertThat(dle?.lang).isEqualTo("es")
    }
}
