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
import java.nio.file.Files
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
