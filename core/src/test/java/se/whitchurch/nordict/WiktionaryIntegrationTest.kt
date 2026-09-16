package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class WiktionaryIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var dictionary: FrWiktionary

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        dictionary = FrWiktionary(client, server.url("/").toString().removeSuffix("/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testSearch() {
        val json = File("../testdata/wfr-search.json").readText()
        server.enqueue(MockResponse().setBody(json))

        val results = dictionary.search("table")

        assertThat(results).hasSize(10)
        assertThat(results[0].mTitle).isEqualTo("table")
        assertThat(results[1].mTitle).isEqualTo("tableau")
        assertThat(results[0].uri.toString()).contains("curid=774")

        val request = server.takeRequest()
        assertThat(request.path).contains("/w/rest.php/v1/search/title?q=table")
    }

    @Test
    fun testGet() {
        val html = File("../testdata/wfr/table.html").readText()
        server.enqueue(MockResponse().setBody(html))

        val uri = server.url("/wiki/table")
        val word = dictionary.get(uri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("table")
        assertThat(word?.renderAsJson).isTrue()
        assertThat(word?.definitions).isNotEmpty()
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition)
            .contains("Surface plane de bois")
        assertThat(word?.audio).isNotEmpty()
        assertThat(word?.mHomonymEntries).hasSize(2)
    }

    @Test
    fun testGetHomographRef() {
        val html = File("../testdata/wfr/table.html").readText()

        // A __ref=fr-flex-verb-1 URL resolves the Forme de verbe homograph.
        server.enqueue(MockResponse().setBody(html))
        val refUri = server.url("/wiki/table").newBuilder()
            .addQueryParameter("__ref", "fr-flex-verb-1")
            .build()
        val word = dictionary.get(refUri)

        assertThat(word).isNotNull()
        assertThat(word?.mTitle).isEqualTo("table")
        assertThat(word?.pos).isEqualTo(Pos.VERB)
        assertThat(word?.definitions?.get(0)?.glosses?.get(0)?.definition)
            .isEqualTo("Première personne du singulier du présent de l’indicatif de tabler.")

        // A plain URL resolves the first (Nom commun) lemma.
        server.enqueue(MockResponse().setBody(html))
        val mainUri = server.url("/wiki/table")
        val word2 = dictionary.get(mainUri)

        assertThat(word2).isNotNull()
        assertThat(word2?.mTitle).isEqualTo("table")
        assertThat(word2?.definitions?.get(0)?.glosses?.get(0)?.gender)
            .isEqualTo(Genders.FEMININE)
        assertThat(word2?.renderAsJson).isTrue()
    }

    @Test
    fun testGetRejectsForeignHost() {
        val uri = server.url("/wiki/table").newBuilder()
            .host("somewhere-else.example")
            .build()
        assertThat(dictionary.get(uri)).isNull()
    }
}