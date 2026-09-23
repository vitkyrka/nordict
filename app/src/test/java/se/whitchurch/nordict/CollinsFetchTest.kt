package se.whitchurch.nordict

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Wiring tests for the Collins challenge fallback: the clearance store, the
 * transport status, the main-thread guards (no WebView is ever created here),
 * and the [Ordboken] default registry giving only the two Collins
 * dictionaries the WebView-backed transport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CollinsFetchTest {

    private val client = OkHttpClient()

    @Before
    fun setUp() {
        CollinsClearance.reset()
        CollinsTransport.reset()
        Ordboken.reset()
    }

    @After
    fun tearDown() {
        CollinsClearance.reset()
        CollinsTransport.reset()
        Ordboken.reset()
    }

    @Test
    fun testCollinsFetcherIsOkHttpWithWebViewFallback() {
        val fetcher = collinsPageFetcher(client)

        assertThat(fetcher).isInstanceOf(FallbackPageFetcher::class.java)
        val fallback = fetcher as FallbackPageFetcher
        assertThat(fallback.primary).isInstanceOf(OkHttpPageFetcher::class.java)
        assertThat(fallback.secondary).isInstanceOf(WebViewPageFetcher::class.java)
    }

    @Test
    fun testClearanceSyncGatesOnHostAndPresence() {
        // Nothing held: no headers anywhere.
        assertThat(CollinsClearance.cookieHeaderFor("https://www.collinsdictionary.com/dictionary/spanish-english/x"))
            .isEmpty()

        // A WebView cookie header mints the token; other cookies ride along ignored.
        CollinsClearance.noteCookies(
            "https://www.collinsdictionary.com/dictionary/spanish-english/x",
            "cf_clearance=tok123; _ga=abc"
        )
        assertThat(
            CollinsClearance.cookieHeaderFor("https://www.collinsdictionary.com/autocomplete/?q=x")
        ).containsExactly("Cookie", "cf_clearance=tok123")
        // Foreign hosts never get the cookie.
        assertThat(CollinsClearance.cookieHeaderFor("https://dle.rae.es/frente")).isEmpty()
    }

    @Test
    fun testClearanceIgnoresForeignCookies() {
        CollinsClearance.noteCookies("https://dle.rae.es/frente", "cf_clearance=nope")
        assertThat(CollinsClearance.cookieHeaderFor("https://www.collinsdictionary.com/x")).isEmpty()
    }

    @Test
    fun testTransportNoticeTracksFallback() {
        assertThat(CollinsTransport.recentFallbackNotice()).isNull()

        CollinsTransport.noteFallback(true)
        assertThat(CollinsTransport.recentFallbackNotice()).contains("embedded browser")

        CollinsTransport.noteFallback(false)
        assertThat(CollinsTransport.recentFallbackNotice()).contains("could not solve")
    }

    @Test
    fun testWebViewFetcherRefusesMainThread() {
        // Robolectric tests run on the main looper: the fetcher must fail
        // fast (no latch, no WebView) rather than deadlock it.
        val result = WebViewPageFetcher().fetch("https://www.collinsdictionary.com/x", emptyMap())

        assertThat(result.code).isEqualTo(-1)
        assertThat(result.body).isEmpty()
    }

    @Test
    fun testChallengeHolderFailsFastBeforeInit() {
        // Off the main thread but never initialized: graceful failure, and in
        // particular no WebView is created.
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<ChallengeWebView.WebFetch> {
                ChallengeWebView.loadAndExtract("https://www.collinsdictionary.com/x", 5_000L)
            }
            val fetched = future.get(15, TimeUnit.SECONDS)
            assertThat(fetched.result.code).isEqualTo(-1)
            assertThat(fetched.result.body).isEmpty()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun testDefaultRegistryGivesCollinsTheFallbackTransport() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        NordictPrefs.clearBlocking(app)
        val ordboken = Ordboken.getInstance(app, client)

        val colspan = ordboken.dictMap["COLSPAN"]!!
        val colfren = ordboken.dictMap["COLFREN"]!!
        assertThat(colspan.pageFetcher).isInstanceOf(FallbackPageFetcher::class.java)
        assertThat(colfren.pageFetcher).isInstanceOf(FallbackPageFetcher::class.java)
        assertThat((colspan.pageFetcher as FallbackPageFetcher).secondary)
            .isInstanceOf(WebViewPageFetcher::class.java)

        // Every other dictionary keeps the plain OkHttp transport.
        for ((tag, dict) in ordboken.dictMap) {
            if (tag == "COLSPAN" || tag == "COLFREN") continue
            assertThat(dict.pageFetcher).isInstanceOf(OkHttpPageFetcher::class.java)
        }
    }
}
