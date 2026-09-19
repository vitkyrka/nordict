package se.whitchurch.nordict

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

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

    // [Ordboken.isOnline] reads the modern NetworkCapabilities API, which
    // Robolectric leaves empty by default (no active network) — report an
    // online network so the results screen fetches instead of showing the
    // offline error. The NetworkInfo types here are deprecated in the SDK but
    // are the only way to drive Robolectric's shadow connectivity manager
    // (its getActiveNetwork() resolves the active info's type to a network).
    @Suppress("DEPRECATION")
    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        NordictPrefs.clearBlocking(app)
        Ordboken.reset()

        // [Ordboken.isOnline] reads the modern NetworkCapabilities API, which
        // Robolectric leaves empty by default (no active network) — report an
        // online network so the results screen fetches instead of showing the
        // offline error.
        val connMgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // The shadow keys networks by net id but resolves the active network
        // by the active info's type, so both must be TYPE_WIFI.
        val network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        val info = ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED
        )
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connMgr).setActiveNetworkInfo(info)
        shadowOf(connMgr).addNetwork(network, info)
        shadowOf(connMgr).setNetworkCapabilities(network, capabilities)

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
                    onOpenWord = {},
                    onOpenHistory = { _, _, _ -> }
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

    @Test
    fun emptyQueryShowsHistoryInsteadOfNoResults() {
        kotlinx.coroutines.runBlocking {
            saveHistoryEntry(
                app,
                dict = "SO",
                title = "histword",
                summary = "a past lookup",
                url = "https://example.com/histword",
                sources = ""
            )
        }

        setSearch("")

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("histword")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(
            composeRule.onAllNodesWithText("a past lookup").fetchSemanticsNodes()
        ).isNotEmpty()
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
