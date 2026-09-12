package se.whitchurch.nordict.debug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.DleDictionary
import se.whitchurch.nordict.EstDictionary
import se.whitchurch.nordict.MainActivity
import se.whitchurch.nordict.Ordboken
import se.whitchurch.nordict.WordActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Drives [AppDriver] (the app-side agent) against a Robolectric app seeded
 * with DLE/EST pointing at MockWebServer. [AppDriver.execute] runs on a
 * background thread (exactly like the agent server's connection thread) while
 * this test thread keeps the main looper idling so the app's own tasks run.
 *
 * Robolectric does not auto-create activities started via `startActivity`,
 * so operations that merely start a new word view (`open`, `openUri`,
 * `nextPage`) are covered end-to-end on a device (see AGENTS.md, `repl
 * --device`); here we cover the operations that run on the current view
 * (search, setDict/setLang, state, back) plus the exact-match error paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppDriverTest {

    private lateinit var server: MockWebServer
    private lateinit var driver: AppDriver
    private var app: android.app.Application? = null

    @Before
    fun setUp() {
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
    }

    /**
     * Launches [MainActivity]. MainActivity.restoreLastView() auto-searches the
     * last query on launch, so a dummy response is queued first and we wait for
     * that one request to be served; the tests below then own every enqueue.
     */
    private fun launchMain() {
        server.enqueue(MockResponse().setBody("[]"))
        ActivityScenario.launch(MainActivity::class.java)
        val started = System.currentTimeMillis()
        while (server.requestCount < 1) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
            if (System.currentTimeMillis() - started > 10_000) {
                throw AssertionError("startup auto-search never hit the mock server")
            }
        }
    }

    /** Launches a word view straight into [path] (an EST URL). */
    private fun launchWord(path: String): ActivityScenario<WordActivity> {
        val intent = Intent(app, WordActivity::class.java)
            .setData(Uri.parse(server.url(path).toString()))
        return ActivityScenario.launch(intent)
    }

    /** Same as [launchWord] but via [Robolectric.buildActivity] so the view can
     * be destroyed deterministically (Robolectric cannot reproduce the
     * finish()-triggers-destroy lifecycle of a scenario activity). */
    private fun launchWordController(path: String): org.robolectric.android.controller.ActivityController<WordActivity> =
        Robolectric.buildActivity(WordActivity::class.java, Intent(app, WordActivity::class.java)
            .setData(Uri.parse(server.url(path).toString())))
            .setup()

    /** JUnit-friendly `Await`: idles the main looper until [condition]. */
    private fun awaitCondition(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms (tracked=${trackedActivity()})")
    }

    /**
     * Drives one command on a background thread while this thread keeps the
     * main looper going. The word-view ops that need the app to load a page
     * return as soon as [Ordboken.currentWord] reflects the result.
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
        assertThat(unknown.error).contains("unknown dictionary 'nope'")
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
    fun stateSnapshotsWordView() {
        val html = File("../testdata/est/muerte.html").readText()
        // getWord probes dictionaries in order; DLE also fetches /muerte (and
        // returns null), so serve the page twice for the EST retrieval.
        server.enqueue(MockResponse().setBody(html))
        server.enqueue(MockResponse().setBody(html))
        val scenario = launchWord("/muerte")
        awaitCondition { Ordboken.getInstance(app!!).currentWord?.mTitle == "muerte" }

        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.ok).isTrue()
        assertThat(state.state?.activity).isEqualTo("WordActivity")
        assertThat(state.state?.dict).isEqualTo("DLE")
        assertThat(state.state?.word?.word?.mTitle).isEqualTo("muerte")
        assertThat(state.state?.word!!.homonyms.map { it.ref }).containsExactly("1", "2", "3").inOrder()
        assertThat(state.state!!.word!!.selected).isEqualTo(0)
        scenario.close()
    }

    @Test
    fun backLeavesWordView() {
        val html = File("../testdata/est/muerte.html").readText()
        server.enqueue(MockResponse().setBody(html))
        server.enqueue(MockResponse().setBody(html))
        val controller = launchWordController("/muerte")
        awaitCondition { Ordboken.getInstance(app!!).currentWord?.mTitle == "muerte" }

        val back = drive(AgentCommand(op = AgentOps.BACK))
        assertThat(back.ok).isTrue()
        // Robolectric never destroys a finished scenario activity, so the
        // driver reports the view as closed (the real destroy/teardown is
        // simulated below); on a device it lands on whatever was beneath.
        assertThat(back.message).contains("closed the word view")

        // Robolectric cannot reproduce finish()-driven teardown, so simulate
        // the system destroying the finished view; the device E2E covers the
        // real back-button lifecycle.
        controller.destroy()
        awaitCondition { trackedActivity() == null }
        assertThat(drive(AgentCommand(op = AgentOps.STATE)).state?.activity).isEmpty()
    }

    private fun trackedActivity(): android.app.Activity? =
        (ApplicationProvider.getApplicationContext<android.app.Application>() as DebugApp).activityTracker.current
}