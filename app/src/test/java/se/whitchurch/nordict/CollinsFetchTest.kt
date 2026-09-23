package se.whitchurch.nordict

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        InfopediaClearance.reset()
        InfopediaTransport.reset()
        Ordboken.reset()
    }

    @After
    fun tearDown() {
        CollinsClearance.reset()
        CollinsTransport.reset()
        InfopediaClearance.reset()
        InfopediaTransport.reset()
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
                ChallengeWebView.loadAndExtract("https://www.collinsdictionary.com/x", timeoutMs = 5_000L)
            }
            val fetched = future.get(15, TimeUnit.SECONDS)
            assertThat(fetched.result.code).isEqualTo(-1)
            assertThat(fetched.result.body).isEmpty()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun testAudioByteFetchFailsFastBeforeInit() {
        // Robolectric never inits the holder (and runs on the main thread):
        // the WebView-bytes audio fallback must fail fast, never deadlock.
        val fetched = ChallengeWebView.fetchBytes(
            "https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0034030.mp3",
            "https://www.collinsdictionary.com/dictionary/spanish-english/frente",
            timeoutMs = 5_000L
        )

        assertThat(fetched.ok).isFalse()
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

        // Infopedia is challenged too, so it shares the fallback transport.
        val infopedia = ordboken.dictMap["INFOPEDIA"]!!
        assertThat(infopedia.pageFetcher).isInstanceOf(FallbackPageFetcher::class.java)
        assertThat((infopedia.pageFetcher as FallbackPageFetcher).secondary)
            .isInstanceOf(WebViewPageFetcher::class.java)

        // Every other dictionary keeps the plain OkHttp transport.
        for ((tag, dict) in ordboken.dictMap) {
            if (tag == "COLSPAN" || tag == "COLFREN" || tag == "INFOPEDIA") continue
            assertThat(dict.pageFetcher).isInstanceOf(OkHttpPageFetcher::class.java)
        }
    }

    @Test
    fun testInfopediaFetcherIsOkHttpWithWebViewFallback() {
        val fetcher = infopediaPageFetcher(client)

        assertThat(fetcher).isInstanceOf(FallbackPageFetcher::class.java)
        val fallback = fetcher as FallbackPageFetcher
        assertThat(fallback.primary).isInstanceOf(OkHttpPageFetcher::class.java)
        assertThat(fallback.secondary).isInstanceOf(WebViewPageFetcher::class.java)
    }

    @Test
    fun testInfopediaClearanceSyncGatesOnHostAndPresence() {
        assertThat(InfopediaClearance.cookieHeaderFor("https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa"))
            .isEmpty()

        InfopediaClearance.noteCookies(
            "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa",
            "cf_clearance=tok456; _ga=abc"
        )
        assertThat(
            InfopediaClearance.cookieHeaderFor("https://www.infopedia.pt/dicionarios/lingua-portuguesa/sugestao-pesquisa/mesa")
        ).containsExactly("Cookie", "cf_clearance=tok456")
        // Foreign hosts never get the cookie.
        assertThat(InfopediaClearance.cookieHeaderFor("https://dle.rae.es/frente")).isEmpty()
        // ... nor does Collins get Infopedia's token.
        assertThat(InfopediaClearance.cookieHeaderFor("https://www.collinsdictionary.com/x")).isEmpty()
    }

    @Test
    fun testInfopediaClearanceIgnoresForeignCookies() {
        InfopediaClearance.noteCookies("https://dle.rae.es/frente", "cf_clearance=nope")
        assertThat(InfopediaClearance.cookieHeaderFor("https://www.infopedia.pt/x")).isEmpty()
    }

    @Test
    fun testInfopediaTransportNoticeTracksFallback() {        assertThat(InfopediaTransport.recentFallbackNotice()).isNull()

        InfopediaTransport.noteFallback(true)
        assertThat(InfopediaTransport.recentFallbackNotice()).contains("Infopedia")
        assertThat(InfopediaTransport.recentFallbackNotice()).contains("embedded browser")

        InfopediaTransport.noteFallback(false)
        assertThat(InfopediaTransport.recentFallbackNotice()).contains("could not solve")
    }

    @Test
    fun testAudioHeadersCarryInfopediaClearanceToTtsClips() {
        // The word view's ExoPlayer fetches the TTS clip with its own HTTP
        // stack: without the synced clearance the clip 403s and nothing plays.
        assertThat(
            audioRequestHeaders("https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0")
        ).doesNotContainKey("Cookie")

        InfopediaClearance.noteCookies(
            "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa",
            "cf_clearance=tok456; _ga=abc"
        )
        assertThat(
            audioRequestHeaders("https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0")["Cookie"]
        ).isEqualTo("cf_clearance=tok456")
    }

    @Test
    fun testAudioHeadersCarryCollinsClearanceToSoundClips() {
        assertThat(
            audioRequestHeaders("https://www.collinsdictionary.com/sounds/hwd_sounds/ES-419-A0021400.mp3")
        ).doesNotContainKey("Cookie")

        CollinsClearance.noteCookies(
            "https://www.collinsdictionary.com/dictionary/spanish-english/mesa",
            "cf_clearance=tok123"
        )
        assertThat(
            audioRequestHeaders("https://www.collinsdictionary.com/sounds/hwd_sounds/ES-419-A0021400.mp3")["Cookie"]
        ).isEqualTo("cf_clearance=tok123")
        // Collins' token never leaks onto Infopedia clips and vice versa.
        assertThat(
            audioRequestHeaders("https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0")
        ).doesNotContainKey("Cookie")
    }

    @Test
    fun testAudioHeadersStayEmptyOffChallengedHosts() {
        CollinsClearance.noteCookies("https://www.collinsdictionary.com/x", "cf_clearance=tok123")
        InfopediaClearance.noteCookies("https://www.infopedia.pt/x", "cf_clearance=tok456")

        assertThat(audioRequestHeaders("https://dle.rae.es/frente")).isEmpty()
    }

    @Test
    fun testAudioHeadersAddWordPageRefererForInfopediaTts() {
        // Infopedia hotlink-guards its TTS endpoint: a bare fetch of the clip
        // URL answers 404 unless it carries the word page as Referer.
        val tts = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0"
        val page = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa"

        val withReferer = audioRequestHeaders(tts, page)
        assertThat(withReferer["Referer"]).isEqualTo(page)
        assertThat(withReferer["Sec-Fetch-Dest"]).isEqualTo("audio")
        // Without a word page there is no Referer, but the subresource
        // metadata still goes out; other hosts never get any of it.
        val bare = audioRequestHeaders(tts)
        assertThat(bare).doesNotContainKey("Referer")
        assertThat(bare["Sec-Fetch-Dest"]).isEqualTo("audio")
        // Collins' static sound files get the word page as Referer too (a
        // bare fetch 403s); unrelated hosts still get nothing.
        val collins = audioRequestHeaders(
            "https://www.collinsdictionary.com/sounds/hwd_sounds/ES-419-A0021400.mp3",
            "https://www.collinsdictionary.com/dictionary/spanish-english/mesa"
        )
        assertThat(collins["Referer"])
            .isEqualTo("https://www.collinsdictionary.com/dictionary/spanish-english/mesa")
        assertThat(collins["Sec-Fetch-Dest"]).isEqualTo("audio")
        assertThat(audioRequestHeaders("https://dle.rae.es/frente", "https://dle.rae.es/frente"))
            .isEmpty()
    }

    @Test
    fun testMergeCookiesPrefersJarAndAppendsMissingClearance() {        assertThat(mergeCookies(null, null)).isNull()
        assertThat(mergeCookies("a=b", null)).isEqualTo("a=b")
        assertThat(mergeCookies(null, "cf_clearance=t")).isEqualTo("cf_clearance=t")
        // The jar already carries a clearance: no duplicate.
        assertThat(mergeCookies("x=y; cf_clearance=j", "cf_clearance=s"))
            .isEqualTo("x=y; cf_clearance=j")
        // Otherwise the synced clearance rides along with the jar.
        assertThat(mergeCookies("__cf_bm=z", "cf_clearance=s"))
            .isEqualTo("__cf_bm=z; cf_clearance=s")
    }

    @Test
    fun testFallbackCacheFileKeysByUrl() {
        val dir = Files.createTempDirectory("fallback").toFile()
        try {
            val tts = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/tts/word/mesa?homografia=0"
            assertThat(audioFallbackFile(dir, tts)).isEqualTo(audioFallbackFile(dir, tts))
            assertThat(audioFallbackFile(dir, tts).name).startsWith("audio-fallback-")
            assertThat(audioFallbackFile(dir, tts).name).endsWith(".mp3")
            assertThat(audioFallbackFile(dir, tts))
                .isNotEqualTo(audioFallbackFile(dir, "$tts&x=1"))

            // Usability is presence plus a non-empty body.
            val file = audioFallbackFile(dir, tts)
            assertThat(isUsableFallbackFile(file)).isFalse()
            file.createNewFile()
            assertThat(isUsableFallbackFile(file)).isFalse()
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertThat(isUsableFallbackFile(file)).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testBuildAudioRequestCarriesClearanceRefererAndBrowserUa() {
        val clip = "https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0034030.mp3"
        val page = "https://www.collinsdictionary.com/dictionary/spanish-english/mesa"

        var request = buildAudioRequest(clip, page)
        assertThat(request.header("Referer")).isEqualTo(page)
        assertThat(request.header("Sec-Fetch-Dest")).isEqualTo("audio")
        assertThat(request.header("User-Agent")).isEqualTo(AUDIO_BROWSER_UA)
        assertThat(request.header("Cookie")).isNull()

        CollinsClearance.noteCookies(page, "cf_clearance=tok123")
        request = buildAudioRequest(clip, page)
        assertThat(request.header("Cookie")).isEqualTo("cf_clearance=tok123")

        // Unchallenged hosts stay bare: no UA override, no referer metadata.
        val plain = buildAudioRequest("https://dle.rae.es/frente", "https://dle.rae.es/frente")
        assertThat(plain.header("User-Agent")).isNull()
        assertThat(plain.header("Referer")).isNull()
    }

    @Test
    fun testFetchAudioBytesReadsSharedCacheWithoutNetwork() {
        // A clip the word view already recovered embeds/plays with no network:
        // the fetcher must not touch the client or the WebView fallback.
        val dir = Files.createTempDirectory("shared-cache").toFile()
        try {
            val clip = "https://www.collinsdictionary.com/sounds/hwd_sounds/ES-ES-W0034030.mp3"
            val expected = byteArrayOf(1, 2, 3, 4)
            audioFallbackFile(dir, clip).writeBytes(expected)

            val webFetchCalled = AtomicBoolean(false)
            val bytes = fetchAudioBytes(
                clip, "https://www.collinsdictionary.com/dictionary/spanish-english/mesa",
                dir, client,
                webFetch = { _, _ ->
                    webFetchCalled.set(true)
                    ChallengeWebView.WebBytes(-1, null)
                }
            )

            assertThat(bytes).isEqualTo(expected)
            assertThat(webFetchCalled.get()).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testFetchAudioBytesDirectSuccessWarmsSharedCache() {
        // `isChallenged` forces the challenged path so a MockWebServer URL
        // stands in for a Collins clip: direct bytes win, the WebView fallback
        // stays untouched, and the shared cache file is warmed for the other
        // screen. The forced UA goes out even off the real host.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(byteArrayOf(9, 8, 7))))
        server.start()
        val dir = Files.createTempDirectory("warm-cache").toFile()
        try {
            val url = server.url("/clip.mp3").toString()
            val webFetchCalled = AtomicBoolean(false)
            val bytes = fetchAudioBytes(
                url, "https://example.com/word", dir, OkHttpClient(), isChallenged = true,
                webFetch = { _, _ ->
                    webFetchCalled.set(true)
                    ChallengeWebView.WebBytes(-1, null)
                }
            )

            assertThat(bytes).isEqualTo(byteArrayOf(9, 8, 7))
            assertThat(webFetchCalled.get()).isFalse()
            assertThat(audioFallbackFile(dir, url).readBytes()).isEqualTo(byteArrayOf(9, 8, 7))

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertThat(recorded.getHeader("User-Agent")).isEqualTo(AUDIO_BROWSER_UA)
        } finally {
            dir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun testFetchAudioBytesFallsBackToWebViewBytesOnChallenge() {
        // A 403 direct fetch (the Cloudflare answer to a bare client) recovers
        // silently through the injected WebView-bytes fetch — the path
        // CardActivity.urlsToData relies on for Collins/Infopedia clips — and
        // caches the recovery under the shared name. The word page goes along
        // as referer (Infopedia's TTS hotlink guard needs it).
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        server.start()
        val dir = Files.createTempDirectory("fallback-bytes").toFile()
        try {
            val url = server.url("/clip.mp3").toString()
            val referer = "https://www.infopedia.pt/dicionarios/lingua-portuguesa/mesa"
            val seenReferer = AtomicReference<String?>()
            val bytes = fetchAudioBytes(
                url, referer, dir, OkHttpClient(), isChallenged = true,
                webFetch = { _, r ->
                    seenReferer.set(r)
                    ChallengeWebView.WebBytes(200, byteArrayOf(5, 6))
                }
            )

            assertThat(bytes).isEqualTo(byteArrayOf(5, 6))
            assertThat(seenReferer.get()).isEqualTo(referer)
            assertThat(audioFallbackFile(dir, url).readBytes()).isEqualTo(byteArrayOf(5, 6))
        } finally {
            dir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun testFetchAudioBytesSkipsFallbackOffChallengedHosts() {
        // Unchallenged dictionaries keep the old behavior: a failed direct
        // fetch embeds silence and never pays for a WebView round-trip.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        server.start()
        val dir = Files.createTempDirectory("no-fallback").toFile()
        try {
            val webFetchCalled = AtomicBoolean(false)
            val bytes = fetchAudioBytes(
                server.url("/frente").toString(), null, dir, OkHttpClient(),
                webFetch = { _, _ ->
                    webFetchCalled.set(true)
                    ChallengeWebView.WebBytes(200, byteArrayOf(5, 6))
                }
            )

            assertThat(bytes).isNull()
            assertThat(webFetchCalled.get()).isFalse()
        } finally {
            dir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun testFetchAudioBytesIgnoresEmptyCacheFile() {
        // A zero-byte cache entry is corrupt, not a hit: the direct fetch
        // still runs instead of embedding silence.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(byteArrayOf(3))))
        server.start()
        val dir = Files.createTempDirectory("empty-cache").toFile()
        try {
            val url = server.url("/clip.mp3").toString()
            audioFallbackFile(dir, url).createNewFile()

            val bytes = fetchAudioBytes(
                url, null, dir, OkHttpClient(), isChallenged = true,
                webFetch = { _, _ -> ChallengeWebView.WebBytes(-1, null) }
            )

            assertThat(bytes).isEqualTo(byteArrayOf(3))
        } finally {
            dir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun testPruneFallbackCacheKeepsNewest() {
        val dir = Files.createTempDirectory("prune").toFile()
        try {
            val now = System.currentTimeMillis()
            for (i in 0 until 22) {
                val f = java.io.File(dir, "audio-fallback-$i.mp3")
                f.writeBytes(byteArrayOf(1))
                f.setLastModified(now - i * 1_000L)
            }
            // Unrelated files are never touched.
            val keep = java.io.File(dir, "other.txt")
            keep.writeBytes(byteArrayOf(1))

            pruneAudioFallbackCache(dir, keep = 20)

            assertThat(dir.listFiles { f -> f.name.startsWith("audio-fallback-") }!!.size)
                .isEqualTo(20)
            assertThat(java.io.File(dir, "audio-fallback-21.mp3").exists()).isFalse()
            assertThat(java.io.File(dir, "audio-fallback-0.mp3").exists()).isTrue()
            assertThat(keep.exists()).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }
}
