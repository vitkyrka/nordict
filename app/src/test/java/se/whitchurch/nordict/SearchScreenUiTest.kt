package se.whitchurch.nordict

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for [SearchScreen]'s results list.
 *
 * SO's autocomplete can list a compound entry with the same article id as its
 * base word, so two [SearchResult]s share a URI. Keying the LazyColumn by URI
 * then crashed the whole app on the results screen with
 * `Key "…" was already used`; the list must tolerate repeated URIs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: android.app.Application
    private lateinit var server: MockWebServer
    private lateinit var ordboken: Ordboken

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("ordboken", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        Ordboken.reset()

        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                // The search bar's suggestions and the results screen both hit
                // the autocomplete endpoint; serve the duplicate-id body for
                // either so the results list reliably composes.
                return if (path.startsWith("/api/autocomplete")) {
                    MockResponse().setBody(DUPLICATE_AUTOCOMPLETE)
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }

        val client = OkHttpClient()
        ordboken = Ordboken.getInstance(
            app, client,
            arrayOf(SoDictionary(client, server.url("/").toString().removeSuffix("/")))
        )
    }

    @After
    fun tearDown() {
        Ordboken.reset()
        server.shutdown()
    }

    private fun setSearch(query: String) {
        composeRule.setContent {
            MaterialTheme {
                SearchScreen(
                    context = app,
                    ordboken = ordboken,
                    query = query,
                    onOpenWord = {}
                )
            }
        }
    }

    @Test
    fun resultsWithDuplicateUrisRenderBothRows() {
        setSearch("husar")

        // Both rows must compose; a URI-keyed LazyColumn throws from its
        // measure here before the second row ever appears.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("husarregemente (husar)")
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertThat(composeRule.onAllNodesWithText("husar").fetchSemanticsNodes()).hasSize(1)
        assertThat(composeRule.onAllNodesWithText("husarregemente (husar)").fetchSemanticsNodes())
            .hasSize(1)
    }

    companion object {
        /**
         * SO autocomplete payload whose two entries both target the same
         * article id (a base word and its compound), so their URIs collide —
         * the input that used to crash the results screen.
         */
        private val DUPLICATE_AUTOCOMPLETE = """
            {"so":[
              {"label":"husar","word_class":"substantiv","target":{"id":"185704"}},
              {"label":"husarregemente (husar)","word_class":"substantiv","target":{"id":"185704"}}
            ]}
        """.trimIndent()
    }
}
