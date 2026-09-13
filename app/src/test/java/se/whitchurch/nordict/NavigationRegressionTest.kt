package se.whitchurch.nordict

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the navigation bugs that the single-activity Compose
 * conversion introduced. Runs the real [MainActivity] under Robolectric with
 * DLE/EST backed by MockWebServer fixtures:
 *
 *  1. the last *shown* word (not the first) is persisted on pause and
 *     restored on restart,
 *  2. switching to another dictionary of the same language and then pressing
 *     back must re-render the original word instead of leaving a blank
 *     WebView,
 *  3. pressing back while the global search overlay is expanded only collapses
 *     the overlay and must not pop the destination underneath it.
 *
 * The [fixturesRule] is chained *outside* the compose rule so MockWebServer and
 * the test `Ordboken` are installed before [MainActivity] is launched.
 *
 * DLE and EST share the host-only prefix check in `Dictionary.get`, so the two
 * dictionary hosts must differ (127.0.0.1 / 127.0.0.2) to behave like the real
 * dictionaries' distinct domains; otherwise the first dictionary would "own"
 * every probe. Fixtures: DLE parses only `testdata/dle.html`, EST only the
 * `testdata/est*` pages, so each dictionary resolves its own host's pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationRegressionTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()
    private var app: Application? = null
    private lateinit var dle: MockWebServer
    private lateinit var est: MockWebServer

    private val fixturesRule = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    setUpFixtures()
                    try {
                        base.evaluate()
                    } finally {
                        tearDownFixtures()
                    }
                }
            }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fixturesRule).around(composeRule)

    // ---------- Fixture plumbing ----------

    private fun setUpFixtures() {
        app = ApplicationProvider.getApplicationContext<Application>()
        app!!.getSharedPreferences("ordboken", Context.MODE_PRIVATE).edit().clear().commit()
        // History tables persist across tests in Robolectric's native
        // SQLite; a fresh file avoids double-CREATE crashes on the first open.
        app!!.deleteDatabase("Ordboken.db")
        Ordboken.reset()

        dle = MockWebServer()
        dle.start(InetAddress.getByName("127.0.0.1"), 0)
        est = MockWebServer()
        est.start(InetAddress.getByName("127.0.0.2"), 0)

        val client = OkHttpClient()
        Ordboken.getInstance(
            app!!,
            client,
            arrayOf(
                DleDictionary(client, dleBase()),
                EstDictionary(client, estBase())
            )
        )
        // Create the history tables exactly once, before the activity
        // launch; the compose test rule's dispatchers make reads/writes overlap
        // otherwise and a write sneaks in before the first open finishes.
        OrdbokenDbHelper(app!!).readableDatabase.close()
    }

    private fun tearDownFixtures() {
        Ordboken.reset()
        est.shutdown()
        dle.shutdown()
    }

    private fun dleBase(): String = dle.url("/").toString().removeSuffix("/")
    private fun estBase(): String = est.url("/").toString().removeSuffix("/")

    private fun dleFrente() = File("../testdata/dle.html").readText()
    private fun estFrente() = File("../testdata/est.html").readText()
    private fun estMuerte() = File("../testdata/est/muerte.html").readText()
    private fun estCagar() = File("../testdata/est/cagar.html").readText()

    private fun ordboken(): Ordboken = Ordboken.getInstance(app!!)

    // ---------- Navigation helpers (main-thread + looper-idled) ----------

    /** Waits for the app's NavHost to be attached (it is launched by the rule). */
    private fun awaitNav() {
        awaitCondition {
            try {
                (composeRule.activity as? MainActivity)?.navController != null
            } catch (t: Throwable) {
                false
            }
        }
    }

    private fun openWord(server: MockWebServer, path: String) {
        onMain {
            composeRule.activity.navigateToWord(
                Uri.parse(server.url(path).toString()), ""
            )
        }
        val expected = server.url(path).toString()
        awaitCondition {
            ordboken().currentWord?.uri?.toString() == expected
        }
    }

    /** JUnit-friendly `Await`: idles the main looper and the Compose test
     * clock until [condition] (navigation and viewModel dispatches run on the
     * Compose rule's test dispatcher, which the raw looper alone never pumps). */
    private fun awaitCondition(timeoutMs: Long = 20_000, message: String = "", condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            try {
                composeRule.waitForIdle()
            } catch (t: Throwable) {
                // idling can be interrupted while a fresh destination recomposes
            }
            try {
                if (condition()) return
            } catch (t: Throwable) {
                // the condition may probe an activity mid-restart
            }
            Thread.sleep(20)
        }
        throw AssertionError("$message: condition not met within ${timeoutMs}ms")
    }

    /** Runs [block] on the main looper and returns its result. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(30, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** The [WordViewModel] of the current word destination, or null. */
    private fun topWordViewModel(): WordViewModel? = onMain {
        val nav = (composeRule.activity as? MainActivity)?.navController
            ?: return@onMain null
        val entry = nav.currentBackStackEntry ?: return@onMain null
        if (entry.destination.route?.startsWith("word?") != true) return@onMain null
        // Compose's `viewModel(entry)` may key the entry store by either the
        // plain class name or the DefaultKey prefix depending on the compose
        // version; probe both.
        return@onMain entry.viewModelStore["se.whitchurch.nordict.WordViewModel"] as? WordViewModel
            ?: entry.viewModelStore[
        "androidx.lifecycle.ViewModelProvider.DefaultKey:se.whitchurch.nordict.WordViewModel"
        ] as? WordViewModel
    }

    // ---------- Tests ----------

    @Test
    fun onPausePersistsTheTopmostWordNotTheFirstOne() {
        awaitNav()

        // Open word A, then navigate to word B via a link (a second word
        // destination is pushed on top of A).
        est.enqueue(MockResponse().setBody(estMuerte()))
        openWord(est, "/muerte")
        est.enqueue(MockResponse().setBody(estCagar()))
        openWord(est, "/cagar")

        // Backgrounding the app persists the currently shown word (B), not the
        // first word (A).
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertThat(ordboken().lastWhere).isEqualTo(Ordboken.Where.WORD)
        assertThat(ordboken().lastWhat).isEqualTo(est.url("/cagar").toString())

        // Kill + restart: the app must come back on B.
        composeRule.activityRule.scenario.recreate()
        awaitCondition(message = "restart restores word B") {
            ordboken().currentWord?.uri?.toString() == est.url("/cagar").toString()
        }
        assertThat(ordboken().lastWhere).isEqualTo(Ordboken.Where.WORD)
        assertThat(ordboken().lastWhat).isEqualTo(est.url("/cagar").toString())
    }

    @Test
    fun backAfterDictSwitchReloadsTheOriginalWordIntoItsWebview() {
        awaitNav()

        // Open the DLE word form of "frente", then switch to EST (same
        // language): the cross-link hook searches EST for the headword, finds
        // the unique exact match, and pushes the EST word view on top.
        dle.enqueue(MockResponse().setBody(dleFrente()))
        openWord(dle, "/frente")
        assertThat(ordboken().currentWord?.dict).isEqualTo("DLE")

        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().setCurrentDictionary("est") }
        awaitCondition(message = "cross-link to the EST word") {
            val w = ordboken().currentWord
            w != null && w.dict == "EST" && w.uri.toString() == est.url("/frente").toString()
        }

        // Back to the DLE word: the recreated WebView must re-render it.
        onMain { composeRule.activity.navController?.popBackStack() }
        awaitCondition(message = "back restores the DLE word") {
            val w = ordboken().currentWord
            w != null && w.dict == "DLE" && w.uri.toString() == dle.url("/frente").toString()
        }
        awaitCondition(message = "DLE word page is rendered again") {
            topWordViewModel()?.webViewLoaded == true
        }
        // The reload actually repopulated the WebView, not a blank view.
        assertThat(topWordViewModel()?.mWord?.uri?.toString())
            .isEqualTo(dle.url("/frente").toString())
        assertThat(composeRule.activity.navController?.currentBackStackEntry
            ?.destination?.route?.startsWith("word?")).isTrue()
    }

    @Test
    fun quickSwitchBackWhileTheTargetWordIsStillLoadingReloadsTheWord() {
        awaitNav()

        // Open the EST word "frente".
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        assertThat(ordboken().currentWord?.dict).isEqualTo("EST")

        // Switch to DLE. The cross-link search is fast, but the DLE word page
        // is deliberately delayed so the pushed word destination stays in its
        // loading state — the window a quick second switch falls into.
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(
            MockResponse().setBody(dleFrente()).setBodyDelay(1500, TimeUnit.MILLISECONDS)
        )
        onMain { ordboken().setCurrentDictionary("dle") }

        awaitCondition(message = "DLE word destination is up but still loading") {
            val vm = topWordViewModel()
            vm != null && vm.mWord == null
        }

        // Quick switch back to EST while that destination's word has not loaded
        // yet. The loading destination's cross-link hook must defer this switch
        // and apply it once the word lands (it used to be dropped, leaving the
        // DLE word on screen under an EST selection).
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().setCurrentDictionary("est") }

        awaitCondition(timeoutMs = 10_000, message = "word reloads in EST after the quick switch") {
            val w = ordboken().currentWord
            w != null && w.dict == "EST" && w.uri.toString() == est.url("/frente").toString()
        }
    }

    @Test
    fun backWhileSearchIsOpenOnlyCollapsesTheSearchOverlay() {
        awaitNav()

        // Expanding the search bar shows the (empty) suggestions panel. The M3
        // SearchBar activates on field focus, and Robolectric does not grant
        // focus from a synthetic click, so drive the activation explicitly.
        composeRule.onNode(hasSetTextAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertExists()

        // Back collapses the overlay without popping the home destination.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertDoesNotExist()
        assertThat(composeRule.activity.navController?.currentDestination?.route)
            .isEqualTo("home")
    }

    // ---------- Zoom persistence (regression: the Compose conversion moved the
    // WebView zoom save from onPause to composition dispose, where the WebView
    // is already destroyed, so zooming, pausing and closing the app lost the
    // zoom) ----------

    /** Pretends the shown page is zoomed: the ViewModel's save reads
     * `WebView.getScale()` at the next pause, but Robolectric's WebView gets
     * a bogus value from its provider proxy, so hand it a scale we control. */
    private fun zoomTo(vm: WordViewModel, scale: Float) {
        onMain {
            vm.webView = object : WebView(composeRule.activity) {
                override fun getScale(): Float = scale
            }
            vm.webViewVisible = true
        }
    }

    @Test
    fun zoomPersistsOnPauseAndAppCloseDoesNotClobberIt() {
        awaitNav()
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val vm = topWordViewModel()!!

        // The user pinch-zooms the shown word to 150%.
        zoomTo(vm, 1.5f)

        // Backgrounding the app must persist the zoom…
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        awaitCondition(message = "zoom persisted on pause") {
            ordboken().mPrefs.getInt("scale", 0) == 150
        }

        // …and closing the app (destroying the task) must not clobber it: the
        // disposed WordScreen must not re-save a default scale from a WebView
        // that has already been destroyed.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertThat(ordboken().mPrefs.getInt("scale", 0)).isEqualTo(150)
    }

    @Test
    fun backFromAWordPersistsTheZoom() {
        awaitNav()
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val vm = topWordViewModel()!!

        zoomTo(vm, 1.6f)

        // Popping the word with back while the app stays foregrounded is an
        // onPause for the destination in the single-activity app (the old
        // WordActivity finished on back and saved in its onPause).
        onMain { composeRule.activity.navController?.popBackStack() }
        awaitCondition(message = "zoom persisted on back") {
            ordboken().mPrefs.getInt("scale", 0) == 160
        }
        assertThat(composeRule.activity.navController?.currentDestination?.route)
            .isEqualTo("home")
    }

    @Test
    fun resetZoomKeepsTheClearedScaleOnPause() {
        awaitNav()
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val vm = topWordViewModel()!!

        zoomTo(vm, 1.5f)

        // "Reset zoom" clears the saved scale immediately…
        onMain { vm.resetZoom() }
        assertThat(ordboken().mPrefs.getInt("scale", -1)).isEqualTo(0)

        // …and the next pause must not re-save the still-zoomed page's value,
        // or the next open would apply the zoom the user just reset.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        awaitCondition(message = "reset zoom survives pause") {
            ordboken().mPrefs.getInt("scale", -1) == 0
        }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
        assertThat(ordboken().mPrefs.getInt("scale", -1)).isEqualTo(0)
    }
}
