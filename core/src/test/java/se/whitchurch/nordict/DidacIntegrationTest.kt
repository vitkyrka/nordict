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

class DidacIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: DidacDictionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        dictionary = DidacDictionary(client, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = Goldens.fixtureText("../testdata/didac-search.json")
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("cap")

        assertThat(results).hasSize(9)
        assertThat(results[0].mTitle).isEqualTo("cap")
        assertThat(results[0].uri.toString()).contains("/didac/cap1")
        assertThat(results[4].mTitle).isEqualTo("cap-roig")
        assertThat(results[4].uri.toString()).contains("/didac/cap-roig")
        assertThat(results[5].mTitle).isEqualTo("al cap de")

        val request = server.takeRequest()
        assertThat(request.path).contains("/search_api_autocomplete/didac")
        assertThat(request.path).contains("q=cap")
    }

    @Test
    fun testFullSearch() {
        val html = Goldens.fixtureText("../testdata/didac/cap.html")
        server.enqueue(MockResponse().setBody(html))

        val results = dictionary.fullSearch("cap")

        assertThat(results).hasSize(9)
        assertThat(results[0].mTitle).isEqualTo("cap")
        assertThat(results[0].uri.toString()).contains("/didac/cap1")
        assertThat(results[0].mSummary).contains("El cap és la part de dalt del cos")
        assertThat(results[5].mTitle).isEqualTo("al cap de")

        val request = server.takeRequest()
        assertThat(request.path).contains("/cerca/didac")
        assertThat(request.path).contains("search_api_fulltext_cust=cap")
    }

    @Test
    fun testGet() {
        val html = Goldens.fixtureText("../testdata/didac/cap1.html")
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/didac/cap1")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("cap")
        assertThat(word?.definitions).hasSize(6)
        assertThat(word?.idioms).hasSize(1)

        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition)
            .contains("El cap és la part de dalt del cos")
        assertThat(word?.definitions?.get(3)?.glosses?.get(0)?.examples).hasSize(1)
        assertThat(word?.definitions?.get(3)?.glosses?.get(0)?.examples?.get(0))
            .contains("Has begut massa i la beguda t'ha pujat al cap.")
        assertThat(word?.idioms?.get(0)?.idiom).isEqualTo("fa cap")
    }

    @Test
    fun testGetLocutionFromCombinedPage() {
        // A locution URL is served from the search-view page that embeds all
        // the "cap" entries; the URL slug picks the right headword.
        val html = Goldens.fixtureText("../testdata/didac/cap.html")
        server.enqueue(MockResponse().setBody(html))

        val uri: HttpUrl = server.url("/didac/al-cap-de")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("al cap de")
        assertThat(word?.definitions).hasSize(1)
        assertThat(word?.definitions?.get(0)?.grammar).isEqualTo("locució que fa de preposició")
        assertThat(word?.idioms).isEmpty()

        // The full entry set is attached for the combined in-page rendering.
        assertThat(word?.mHomonymEntries).hasSize(9)
        assertThat(word?.mHomonymEntries?.map { it.mTitle })
            .containsExactly(
                "cap", "cap", "cap", "cap", "cap-roig",
                "al cap de", "pel cap alt", "pel cap baix", "al cap i a la fi"
            )
            .inOrder()
    }
}