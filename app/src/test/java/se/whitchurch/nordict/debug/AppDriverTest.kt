package se.whitchurch.nordict.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.DleDictionary
import se.whitchurch.nordict.EstDictionary
import se.whitchurch.nordict.GdlcDictionary
import se.whitchurch.nordict.MainActivity
import se.whitchurch.nordict.Ordboken
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Drives [AppDriver] (the app-side agent) against a Robolectric app seeded
 * with DLE/EST pointing at MockWebServer. [AppDriver.execute] runs on a
 * background thread (exactly like the agent server's connection thread) while
 * this test thread keeps the main looper idling so the app's own coroutines
 * run.
 *
 * The app is a single-[MainActivity] navigation graph, so unlike the legacy
 * separate-activity app, `open`/`openUri`/`nextPage`/`back` all run inside
 * the same launched activity and are fully covered here end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], instrumentedPackages = ["no.such.package.sandbox.isolation"])
class AppDriverTest {

    private lateinit var server: MockWebServer
    private lateinit var driver: AppDriver
    private var app: android.app.Application? = null
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        // The GlobalSnapshotManager's apply-loop coroutine can be stranded at
        // a Robolectric test boundary (its dispatch is cleared from the looper
        // queue) leaving `sent=true` permanently, which blocks all subsequent
        // snapshot-write notifications and prevents the first compose frame
        // from applying.  Reset the manager so ensureStarted() re-creates a
        // fresh channel + coroutine for this test.
        runCatching {
            val startedField = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
                .getDeclaredField("started").also { it.isAccessible = true }
            val sentField = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager")
                .getDeclaredField("sent").also { it.isAccessible = true }
            startedField.set(null, java.util.concurrent.atomic.AtomicBoolean(false))
            sentField.set(null, java.util.concurrent.atomic.AtomicBoolean(false))
            val observers = Class.forName("androidx.compose.runtime.snapshots.SnapshotKt")
                .getDeclaredField("globalWriteObservers").also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (observers.get(null) as MutableList<Any>).clear()
        }

        app = ApplicationProvider.getApplicationContext<android.app.Application>()
        app!!.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        Ordboken.reset()

        server = MockWebServer()
        server.start()

        val client = OkHttpClient()
        Ordboken.getInstance(
            app!!,
            client,
            arrayOf(
                DleDictionary(client, server.url("/").toString().removeSuffix("/")),
                EstDictionary(client, server.url("/").toString().removeSuffix("/"))
            )
        )
        driver = AppDriver(app!!)
    }

    @After
    fun tearDown() {
        Ordboken.reset()
        server.shutdown()
        if (::scenario.isInitialized) scenario.close()
    }

    /**
     * Launches [MainActivity]. A fresh-test prefs file has no saved "lastWhere",
     * so the app lands on the Home (history) destination with no network calls.
     */
    private fun launchMain() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitCondition { (trackedActivity() as? MainActivity)?.navController != null }
    }

    /** JUnit-friendly `Await`: idles the main looper until [condition]. */
    private fun awaitCondition(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            // Under Robolectric the GlobalSnapshotManager apply-loop (which
            // normally fires Snapshot.sendApplyNotifications() on a frame) can
            // get stranded at a test boundary, so its notifications never reach
            // the recomposers and a freshly launched composition never applies
            // its first frame. Drive the Compose test clock and pump the
            // notifications directly so the awaited state (e.g. the NavHost
            // controller) shows up reliably.
            runCatching { composeRule.waitForIdle() }
            runCatching { Snapshot.sendApplyNotifications() }
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms (tracked=${trackedActivity()})")
    }

    /**
     * Drives one command on a background thread while this thread keeps the
     * main looper going. Word-loading ops return as soon as
     * [Ordboken.currentWord] reflects the result.
     */
    private fun drive(command: AgentCommand): AgentResult {
        val executor = Executors.newSingleThreadExecutor()
        val future: Future<AgentResult> = executor.submit<AgentResult> { driver.execute(command) }
        val deadline = System.currentTimeMillis() + 30_000
        var result: AgentResult? = null
        while (result == null) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("driver timed out for $command")
            }
            shadowOf(Looper.getMainLooper()).idle()
            try {
                result = future.get(25, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // keep idling
            }
        }
        executor.shutdownNow()
        return result
    }

    @Test
    fun searchReturnsResults() {
        server.enqueue(MockResponse().setBody(File("../testdata/dle-search.json").readText()))

        val search = drive(AgentCommand(op = AgentOps.SEARCH, query = "frente"))
        assertThat(search.ok).isTrue()
        assertThat(search.state?.dict).isEqualTo("DLE")
        assertThat(search.results.orEmpty()).hasSize(2)
        assertThat(search.results!![0].mTitle).isEqualTo("frente")
        assertThat(search.results!![0].uri).startsWith(server.url("/").toString())
    }

    @Test
    fun searchEmptyIsNotAnError() {
        server.enqueue(MockResponse().setBody("[]"))

        val search = drive(AgentCommand(op = AgentOps.SEARCH, query = "zzz"))
        assertThat(search.ok).isTrue()
        assertThat(search.message).contains("no search results for 'zzz'")
        assertThat(search.results).isEmpty()
    }

    @Test
    fun openRequiresExactMatch() {
        server.enqueue(MockResponse().setBody(
            """[{"title":"frente","uri":"x"},{"title":"frentero","uri":"y"}]"""
        ))

        val open = drive(AgentCommand(op = AgentOps.OPEN, query = "no-such-word"))
        assertThat(open.ok).isFalse()
        assertThat(open.error).contains("no unique exact match for 'no-such-word'")
    }

    @Test
    fun setDictAndSetLang() {
        launchMain()

        val est = drive(AgentCommand(op = AgentOps.SET_DICT, tag = "est"))
        assertThat(est.ok).isTrue()
        assertThat(est.state?.dict).isEqualTo("EST")
        assertThat(est.message).contains("EST")

        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.ok).isTrue()
        assertThat(state.state?.dict).isEqualTo("EST")
        assertThat(state.state?.lang).isEqualTo("es")

        // setDict saved a per-language selection, so setLang("es") restores EST.
        val es = drive(AgentCommand(op = AgentOps.SET_LANG, lang = "es"))
        assertThat(es.ok).isTrue()
        assertThat(es.message).contains("EST")
        assertThat(es.state?.dict).isEqualTo("EST")

        val unknown = drive(AgentCommand(op = AgentOps.SET_DICT, tag = "nope"))
        assertThat(unknown.ok).isFalse()
        assertThat(unknown.error).contains("unknown or incompatible selection 'nope'")
    }

    @Test
    fun setLangFallsBackToFirstDict() {
        launchMain()

        // No per-language selection saved yet: "es" falls back to the first es dict (DLE).
        val es = drive(AgentCommand(op = AgentOps.SET_LANG, lang = "es"))
        assertThat(es.ok).isTrue()
        assertThat(es.state?.dict).isEqualTo("DLE")

        val ca = drive(AgentCommand(op = AgentOps.SET_LANG, lang = "ca"))
        assertThat(ca.ok).isFalse()
        assertThat(ca.error).contains("unknown language 'ca'")
    }

    @Test
    fun swapLangWithoutASecondLanguageErrors() {
        // The seeded app has only es dictionaries, so there is nothing to swap
        // to. swapLang drives only Ordboken, so no activity launch is needed.
        val swap = drive(AgentCommand(op = AgentOps.SWAP_LANG))
        assertThat(swap.ok).isFalse()
        assertThat(swap.error).contains("no last language to swap to")
    }

    @Test
    fun swapLangSwitchesBackToTheLastLanguage() {
        Ordboken.reset()
        val client = OkHttpClient()
        Ordboken.getInstance(
            app!!, client,
            arrayOf(
                DleDictionary(client),
                GdlcDictionary(client)
            )
        )
        driver = AppDriver(app!!)

        // setLang("ca") leaves es behind, so the swap targets es and flips the
        // remembered language each time (es <-> ca toggle).
        val ca = drive(AgentCommand(op = AgentOps.SET_LANG, lang = "ca"))
        assertThat(ca.ok).isTrue()
        assertThat(ca.state?.lang).isEqualTo("ca")

        val back = drive(AgentCommand(op = AgentOps.SWAP_LANG))
        assertThat(back.ok).isTrue()
        assertThat(back.message).contains("language swapped to es")
        assertThat(back.state?.lang).isEqualTo("es")
        assertThat(back.state?.dict).isEqualTo("DLE")

        val again = drive(AgentCommand(op = AgentOps.SWAP_LANG))
        assertThat(again.ok).isTrue()
        assertThat(again.state?.lang).isEqualTo("ca")
        assertThat(again.state?.dict).isEqualTo("GDLC")
    }

    @Test
    fun combinedSelectionSearchesAndOpensAcrossDictionaries() {
        // Re-seed Ordboken with the two dictionaries on distinct base paths so
        // the combined engine can tell DLE and EST apart; a single dispatcher
        // then serves every endpoint.
        Ordboken.reset()
        val client = OkHttpClient()
        Ordboken.getInstance(
            app!!, client,
            arrayOf(
                DleDictionary(client, server.url("/").toString().removeSuffix("/")),
                EstDictionary(
                    client,
                    server.url("/diccionario-estudiante").toString().removeSuffix("/")
                )
            )
        )
        driver = AppDriver(app!!)
        launchMain()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                val q = request.requestUrl?.queryParameter("q").orEmpty()
                val fixtures = File("../testdata")
                return when {
                    path.startsWith("/diccionario-estudiante/srv/keys") ->
                        if (q == "frente") MockResponse().setBody(File(fixtures, "est-search.json").readText())
                        else MockResponse().setBody("[]")
                    path.startsWith("/srv/keys") ->
                        when (q) {
                            "frente" -> MockResponse().setBody(File(fixtures, "dle-search.json").readText())
                            "frentero" -> MockResponse().setBody("""["frentero|frentero"]""")
                            else -> MockResponse().setBody("[]")
                        }
                    path == "/diccionario-estudiante/frente" -> MockResponse().setBody(File(fixtures, "est.html").readText())
                    path == "/diccionario-estudiante/muerte" -> MockResponse().setBody(File(fixtures, "est/muerte.html").readText())
                    path == "/frente" -> MockResponse().setBody(File(fixtures, "dle.html").readText())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        // Combined selection: setDict with a tag list stays combined.
        val setDict = drive(AgentCommand(op = AgentOps.SET_DICT, tags = listOf("DLE", "EST")))
        assertThat(setDict.ok).isTrue()
        assertThat(setDict.message).contains("DLE,EST")
        assertThat(setDict.state?.dict).isEqualTo("DLE,EST")
        assertThat(setDict.state?.dicts).containsExactly("DLE", "EST").inOrder()

        // frente exists in both dictionaries; frentero only in DLE.
        val search = drive(AgentCommand(op = AgentOps.SEARCH, query = "frente"))
        assertThat(search.ok).isTrue()
        val frente = search.results!!.first { it.mTitle == "frente" }
        assertThat(frente.dicts).containsExactly("DLE", "EST").inOrder()

        // Opening a shared headword renders the merged page (refs namespaced).
        val open = drive(AgentCommand(op = AgentOps.OPEN, query = "frente"))
        assertThat(open.ok).isTrue()
        assertThat(open.state?.dict).isEqualTo("DLE,EST")
        assertThat(open.word!!.word?.mTitle).isEqualTo("frente")
        assertThat(open.word!!.homonyms.map { it.ref })
            .containsExactly("DLE::1", "EST::1").inOrder()

        // nextPage walks the combined set in memory (DLE first, then EST).
        val next = drive(AgentCommand(op = AgentOps.NEXT_PAGE))
        assertThat(next.ok).isTrue()
        assertThat(next.word!!.homonyms.map { it.ref })
            .containsExactly("DLE::1", "EST::1").inOrder()
        assertThat(next.word!!.word?.xrefs).containsExactly("EST::1")

        val last = drive(AgentCommand(op = AgentOps.NEXT_PAGE))
        assertThat(last.ok).isTrue()
        assertThat(last.message).contains("already at last entry (2 of 2)")

        // Reopening the same combined headword while an old combined word is
        // still loaded must return the fresh fetch (default DLE::1 selection),
        // not the previously loaded SWAPPED word instance.
        val stale = Ordboken.getInstance(app!!).currentWord
        val reopened = drive(AgentCommand(op = AgentOps.OPEN, query = "frente"))
        assertThat(reopened.ok).isTrue()
        assertThat(reopened.word!!.word?.xrefs).containsExactly("DLE::1")
        assertThat(Ordboken.getInstance(app!!).currentWord !== stale).isTrue()

        // Any single-dict collapse (agent or UI chip) restores the plain path.
        val collapse = drive(AgentCommand(op = AgentOps.SET_DICT, tag = "DLE"))
        assertThat(collapse.ok).isTrue()
        assertThat(collapse.state?.dict).isEqualTo("DLE")
        assertThat(collapse.state?.dicts).isNull()
    }

    @Test
    fun combinedSelectionRejectsUnknownTags() {
        launchMain()

        val unknown = drive(AgentCommand(op = AgentOps.SET_DICT, tags = listOf("DLE", "nope")))
        assertThat(unknown.ok).isFalse()
        assertThat(unknown.error).contains("unknown or incompatible selection 'DLE,nope'")
        // The failed selection leaves the previous one intact.
        assertThat(drive(AgentCommand(op = AgentOps.STATE)).state?.dict).isEqualTo("DLE")
        assertThat(drive(AgentCommand(op = AgentOps.STATE)).state?.dicts).isNull()

        // An empty tag list is rejected too.
        val empty = drive(AgentCommand(op = AgentOps.SET_DICT))
        assertThat(empty.ok).isFalse()
        assertThat(empty.error).contains("no dictionary tag(s) given")
    }

    @Test
    fun stateSnapshotsWordView() {
        val html = File("../testdata/est/muerte.html").readText()
        // getWord probes dictionaries in order; DLE also fetches /muerte (and
        // returns null), so serve the page twice for the EST retrieval.
        server.enqueue(MockResponse().setBody(html))
        server.enqueue(MockResponse().setBody(html))
        launchMain()

        val open = drive(AgentCommand(
            op = AgentOps.OPEN_URI,
            uri = server.url("/muerte").toString()
        ))
        assertThat(open.ok).isTrue()

        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.ok).isTrue()
        assertThat(state.state?.activity).isEqualTo("word")
        assertThat(state.state?.dict).isEqualTo("DLE")
        assertThat(state.state?.word?.word?.mTitle).isEqualTo("muerte")
        assertThat(state.state?.word!!.homonyms.map { it.ref }).containsExactly("1", "2", "3").inOrder()
        assertThat(state.state!!.word!!.selected).isEqualTo(0)
    }

    @Test
    fun backLeavesWordView() {
        val html = File("../testdata/est/muerte.html").readText()
        server.enqueue(MockResponse().setBody(html))
        server.enqueue(MockResponse().setBody(html))
        launchMain()

        val open = drive(AgentCommand(
            op = AgentOps.OPEN_URI,
            uri = server.url("/muerte").toString()
        ))
        assertThat(open.ok).isTrue()

        val back = drive(AgentCommand(op = AgentOps.BACK))
        assertThat(back.ok).isTrue()
        assertThat(back.message).contains("closed the word view")

        // Popping the word destination lands back on Home.
        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.state?.activity).isEqualTo("home")
    }

    private fun trackedActivity(): android.app.Activity? =
        (ApplicationProvider.getApplicationContext<android.app.Application>() as DebugApp).activityTracker.current
}