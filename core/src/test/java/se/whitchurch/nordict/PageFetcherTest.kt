package se.whitchurch.nordict

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM tests for the pluggable page transport: challenge detection, the
 * OkHttp fetcher (headers + per-URL extras), and the fallback policy (the
 * WebView secondary engages only on a challenge, never on normal pages or
 * normal failures).
 */
class PageFetcherTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testIsChallenge() {
        // An outright 403 is a challenge whatever the body.
        assertThat(isChallenge(403, "")).isTrue()
        assertThat(isChallenge(403, "forbidden")).isTrue()
        // A 200 carrying the Cloudflare interstitial is a challenge too.
        assertThat(isChallenge(200, "<title>Just a moment...</title>")).isTrue()
        assertThat(isChallenge(200, "https://challenges.cloudflare.com/cdn-cgi/challenge")).isTrue()
        // Normal pages and normal failures are not challenges.
        assertThat(isChallenge(200, "<main>frente</main>")).isFalse()
        assertThat(isChallenge(404, "not found")).isFalse()
        assertThat(isChallenge(-1, "")).isFalse()
    }

    @Test
    fun testOkHttpFetcherPassesHeaders() {
        server.enqueue(MockResponse().setBody("[{\"title\":\"cagar\"}]"))
        val fetcher = OkHttpPageFetcher(OkHttpClient())

        val result = fetcher.fetch(
            server.url("/autocomplete/").toString(),
            mapOf("Accept" to "application/json")
        )

        assertThat(result.code).isEqualTo(200)
        assertThat(result.isSuccessful).isTrue()
        assertThat(result.body).contains("cagar")
        assertThat(server.takeRequest().getHeader("Accept")).isEqualTo("application/json")
    }

    @Test
    fun testOkHttpFetcherAppliesExtraHeaders() {
        server.enqueue(MockResponse().setBody("ok"))
        val fetcher = OkHttpPageFetcher(OkHttpClient()) {
            mapOf("Cookie" to "cf_clearance=abc")
        }

        val result = fetcher.fetch(
            server.url("/dictionary/spanish-english/x").toString(),
            emptyMap()
        )

        assertThat(result.code).isEqualTo(200)
        assertThat(server.takeRequest().getHeader("Cookie")).isEqualTo("cf_clearance=abc")
    }

    @Test
    fun testOkHttpFetcherFailsGracefully() {
        val fetcher = OkHttpPageFetcher(OkHttpClient())

        // Unresolvable host: no exception escapes, code -1 with an empty body
        // (the dictionaries' empty-result behavior).
        val failed = fetcher.fetch("http://collins.invalid/x", emptyMap())

        assertThat(failed.code).isEqualTo(-1)
        assertThat(failed.isSuccessful).isFalse()
        assertThat(failed.body).isEmpty()
    }

    @Test
    fun testFallbackUsesPrimaryWhenNotChallenged() {
        var secondaryCalls = 0
        val secondary = PageFetcher { _, _ ->
            secondaryCalls += 1
            PageResult(200, "secondary")
        }
        val fallback = FallbackPageFetcher(OkHttpPageFetcher(OkHttpClient()), secondary)

        server.enqueue(MockResponse().setBody("primary page"))
        val result = fallback.fetch(server.url("/dle/frente").toString(), emptyMap())

        assertThat(result.body).isEqualTo("primary page")
        assertThat(secondaryCalls).isEqualTo(0)
    }

    @Test
    fun testFallbackEngagesSecondaryOnChallenge() {
        var fallbackSeen: PageResult? = null
        val secondary = PageFetcher { _, _ -> PageResult(200, "<main>real page</main>") }
        val fallback = FallbackPageFetcher(
            OkHttpPageFetcher(OkHttpClient()), secondary,
            onFallback = { _, result -> fallbackSeen = result }
        )

        server.enqueue(MockResponse().setResponseCode(403).setBody("challenge"))
        val result = fallback.fetch(
            server.url("/dictionary/spanish-english/frente").toString(),
            emptyMap()
        )

        assertThat(result.body).isEqualTo("<main>real page</main>")
        assertThat(fallbackSeen?.body).isEqualTo("<main>real page</main>")
    }

    @Test
    fun testFallbackIgnoresNormalFailures() {
        var secondaryCalls = 0
        val secondary = PageFetcher { _, _ ->
            secondaryCalls += 1
            PageResult(200, "secondary")
        }
        val fallback = FallbackPageFetcher(OkHttpPageFetcher(OkHttpClient()), secondary)

        // A 404 is a normal failure, not a challenge: no fallback, and the
        // primary's failure propagates (today's empty-result behavior).
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val result = fallback.fetch(server.url("/nope").toString(), emptyMap())

        assertThat(result.isSuccessful).isFalse()
        assertThat(result.body).isEqualTo("missing")
        assertThat(secondaryCalls).isEqualTo(0)
    }
}
