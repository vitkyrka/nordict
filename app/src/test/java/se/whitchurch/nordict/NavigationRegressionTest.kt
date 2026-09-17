package se.whitchurch.nordict

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
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
    fun backAfterDictSwitchExitsToTheDestinationNotTheStaleWord() {
        awaitNav()

        // Open the DLE word form of "frente", then switch to EST (same
        // language): the cross-link hook searches EST for the headword, finds
        // the unique exact match, and reloads the word under the new dict. The
        // reload is a pop-then-push (replace), so the pre-switch DLE
        // destination is consumed, not stacked.
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

        // Back from the switched word lands on the previous destination
        // (home): the DLE word was replaced, not left underneath for back to
        // resurrect.
        onMain { composeRule.activity.navController?.popBackStack() }
        awaitCondition(message = "back exits to the previous destination") {
            composeRule.activity.navController?.currentDestination?.route == "home"
        }
        assertThat(ordboken().currentWord?.dict).isEqualTo("EST")
    }

    @Test
    fun togglingCombinedSelectionReloadsTheWordIntoTheCombinedPage() {
        awaitNav()

        // Open the DLE word form of "frente".
        dle.enqueue(MockResponse().setBody(dleFrente()))
        openWord(dle, "/frente")
        assertThat(ordboken().currentWord?.dict).isEqualTo("DLE")
        assertThat(ordboken().activeDicts).isEmpty()

        // Toggle EST on: the selection hook resolves the headword in both
        // dictionaries, then fetches and merges the two pages.
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(MockResponse().setBody(dleFrente()))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().toggleDictionary("EST") }

        awaitCondition(message = "word reloads as the combined DLE+EST page") {
            val w = ordboken().currentWord
            w != null && w.mHomonymEntries.map { it.ref } == listOf("DLE::1", "EST::1")
        }
        assertThat(ordboken().selectionSignature).isEqualTo("DLE,EST")
    }

    @Test
    fun reorderingCombinedSelectionReloadsInNewOrder() {
        awaitNav()

        dle.enqueue(MockResponse().setBody(dleFrente()))
        openWord(dle, "/frente")

        // First switch into combined DLE+EST.
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(MockResponse().setBody(dleFrente()))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().toggleDictionary("EST") }
        awaitCondition(message = "combined page with DLE first") {
            ordboken().currentWord?.mHomonymEntries?.map { it.ref } == listOf("DLE::1", "EST::1")
        }

        // Reorder so EST comes first: the whole combined page reloads in the
        // new dictionary order.
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody(estFrente()))
        dle.enqueue(MockResponse().setBody(dleFrente()))
        onMain { ordboken().setDictionaryOrder(listOf("EST", "DLE")) }

        awaitCondition(message = "combined page reloads with EST first") {
            ordboken().currentWord?.mHomonymEntries?.map { it.ref } == listOf("EST::1", "DLE::1")
        }
        assertThat(ordboken().selectionSignature).isEqualTo("EST,DLE")
    }

    @Test
    fun collapsingCombinedSelectionReloadsInSingleDict() {
        awaitNav()

        dle.enqueue(MockResponse().setBody(dleFrente()))
        openWord(dle, "/frente")

        // Switch into combined DLE+EST.
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(MockResponse().setBody(dleFrente()))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().toggleDictionary("EST") }
        awaitCondition(message = "combined page with DLE first") {
            ordboken().currentWord?.mHomonymEntries?.map { it.ref } == listOf("DLE::1", "EST::1")
        }

        // Collapse back to a single dictionary: the word reloads as the plain
        // EST article under the chosen dictionary.
        est.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain { ordboken().setCurrentDictionary("est") }

        awaitCondition(message = "word reloads in single EST after the collapse") {
            val w = ordboken().currentWord
            w != null && w.dict == "EST" && w.uri.toString() == est.url("/frente").toString()
        }
        assertThat(ordboken().activeDicts).isEmpty()
        assertThat(ordboken().selectionSignature).isEqualTo("EST")
    }

    @Test
    fun openInBrowserOnACombinedPageLaunchesEveryDictionary() {
        awaitNav()

        // Open the merged DLE+EST page directly (bypass the search: the word
        // route is addressed by its sources probe list).
        dle.enqueue(MockResponse().setBody(dleFrente()))
        est.enqueue(MockResponse().setBody(estFrente()))
        onMain {
            composeRule.activity.navigateToSources(
                listOf(
                    CombSource("DLE", dle.url("/frente")),
                    CombSource("EST", est.url("/frente"))
                ),
                "frente",
                null
            )
        }
        awaitCondition(message = "combined page with DLE first") {
            ordboken().currentWord?.mHomonymEntries?.map { it.ref } == listOf("DLE::1", "EST::1")
        }

        // "Open in browser" from the word action-bar menu.
        composeRule.onNodeWithContentDescription(app!!.getString(R.string.menu_more))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.open_in_browser))
            .performClick()
        composeRule.waitForIdle()

        // One external browser intent per selected dictionary (it used to open
        // only the single word's uri — the first source).
        val browserUris = mutableListOf<String>()
        while (true) {
            val intent = shadowOf(composeRule.activity).nextStartedActivity ?: break
            if (intent.action == Intent.ACTION_VIEW) browserUris.add(intent.data!!.toString())
        }
        assertThat(browserUris)
            .containsExactly(dle.url("/frente").toString(), est.url("/frente").toString())
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
    fun dictionarySwitchWhileTheWordDestinationIsPausedIsNotDropped() {
        awaitNav()

        // Open the EST word "frente".
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        assertThat(ordboken().currentWord?.dict).isEqualTo("EST")

        // Background the app: the word destination's lifecycle goes through
        // ON_PAUSE, the same transition gap a cross-dictionary navigation uses
        // on a real device (old dest paused, new dest not yet resumed). The
        // cross-link hook must survive that pause, otherwise a dictionary tap
        // in the gap is dropped and the word view keeps the previous dict.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        dle.enqueue(MockResponse().setBody(dleFrente()))
        onMain { ordboken().setCurrentDictionary("dle") }

        // Back in the foreground, the word must have reloaded in DLE — not
        // stayed on the EST article under a DLE selection.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitCondition(timeoutMs = 10_000, message = "word reloads in DLE after the paused-window switch") {
            val w = ordboken().currentWord
            w != null && w.dict == "DLE" && w.uri.toString() == dle.url("/frente").toString()
        }
    }

    @Test
    fun backWhileSearchIsOpenOnlyCollapsesTheSearchOverlay() {
        awaitNav()

        // Expanding the search bar shows the (empty) suggestions panel. The M3
        // 1.4 SearchBar's InputField expands on touch input (focus-based
        // expansion is gated on touch mode, which Robolectric reports as
        // keyboard mode), so drive the expansion with a synthetic touch tap.
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertExists()

        // Back collapses the overlay without popping the home destination.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertDoesNotExist()
        assertThat(composeRule.activity.navController?.currentDestination?.route)
            .isEqualTo("home")
    }

    @Test
    fun backWhileSearchIsOpenKeepsTheWordDestination() {
        awaitNav()

        // Open a word, then expand the search overlay: the current word is
        // offered as the first artificial suggestion with its fill arrow.
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(app!!.getString(R.string.search_fill_current_word))
            .assertExists()

        // Back collapses the overlay only. Regression for the M3 1.4
        // fullscreen-search dialog, which swallowed the system back button
        // (its Dialog.cancel() was overridden to a no-op); the in-window
        // overlay lets the key reach the activity's dispatcher and the
        // overlay's own BackHandler, which must beat the word destination's.
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(app!!.getString(R.string.search_fill_current_word))
            .assertDoesNotExist()
        assertThat(composeRule.activity.navController?.currentDestination?.route)
            .startsWith("word?")
    }

    @Test
    fun emptySearchShowsCurrentWordWithAFillArrow() {
        awaitNav()

        // Load a word so Ordboken has a "current word" to offer.
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val headword = ordboken().currentWord!!.searchHeadword

        // Expanding the search bar with an empty query shows the current word
        // as the first artificial suggestion (the legacy SearchView behavior).
        // Expand via a synthetic touch tap (see the back-collapse test).
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(headword).assertExists()

        // The north-west arrow fills the current word into the search field so
        // it can be edited manually instead of re-searching from scratch.
        dle.enqueue(MockResponse().setBody("""["frente|frente"]"""))
        composeRule
            .onNodeWithContentDescription(
                app!!.getString(R.string.search_fill_current_word)
            )
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        // The expanded fullscreen sheet renders its own copy of the input
        // field on top of the (still composed) collapsed bar's, so match the
        // editable field by collection; both share the same TextFieldState.
        composeRule.onAllNodes(hasSetTextAction())[0].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString(headword)
            )
        )
        // The caret must land after the filled word so a backspace strips a
        // trailing suffix ("frente a" → "frente") instead of eating the
        // headword itself: the regression this test pins (the legacy
        // String-based SearchBar kept the caret at the start on fill).
        composeRule.onAllNodes(hasSetTextAction())[0].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TextSelectionRange,
                TextRange(headword.length)
            )
        )
    }

    @Test
    fun clearButtonOnTheCollapsedBarOpensTheSearchOverlay() {
        awaitNav()

        // Produce a collapsed bar that still holds a query the way a finished
        // search leaves it: type into the open overlay, then press back to
        // collapse it (the M3 collapsed field itself is not editable).
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("frente")
        composeRule.waitForIdle()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(app!!.getString(R.string.search_clear))
            .assertExists()

        // The X on the collapsed bar clears the stale query and then does what
        // a tap anywhere else on the bar does: open the search overlay with the
        // caret back in the field, ready for a new query.
        composeRule
            .onNodeWithContentDescription(app!!.getString(R.string.search_clear))
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertExists()
        composeRule.onAllNodes(hasSetTextAction())[0].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("")
            )
        )
        // The overlay's copy of the field (the only focused editable node) owns
        // the caret; the collapsed bar's copy behind it does not.
        val focusedEditableFields = composeRule
            .onAllNodes(hasSetTextAction())
            .fetchSemanticsNodes()
            .count { it.config.getOrNull(SemanticsProperties.Focused) == true }
        assertThat(focusedEditableFields).isEqualTo(1)
    }

    @Test
    fun clearButtonInTheOpenOverlayOnlyClearsTheText() {
        awaitNav()

        // Expand the overlay (synthetic touch tap) and type a query into it.
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertExists()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("frente")
        composeRule.waitForIdle()
        assertThat(
            composeRule.onAllNodesWithContentDescription(
                app!!.getString(R.string.search_clear)
            ).fetchSemanticsNodes().size
        ).isEqualTo(2)

        // The in-overlay X clears the text but keeps the overlay open (it must
        // not collapse it back to the bare bar).
        val clearNodes =
            composeRule.onAllNodesWithContentDescription(app!!.getString(R.string.search_clear))
        clearNodes[clearNodes.fetchSemanticsNodes().size - 1]
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(app!!.getString(R.string.no_results)).assertExists()
        composeRule.onAllNodes(hasSetTextAction())[0].assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                AnnotatedString("")
            )
        )
    }

    @Test
    fun dictionaryNavLivesOnTheExpandedSearchSheetOnly() {
        awaitNav()

        // Collapsed: the dictionary nav (language switcher + dict chips) moved
        // off the main screens — only the search bar remains, so the nav
        // controls are absent from the home screen.
        composeRule.onNodeWithText("DLE").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app!!.getString(R.string.change_language))
            .assertDoesNotExist()

        // Expanding the search sheet reveals the dictionary nav pinned above
        // the suggestions panel (the language split button + the combining
        // chips of the current language).
        composeRule.onNode(hasSetTextAction())
            .performTouchInput {
                down(center)
                up()
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("DLE").assertExists()
        composeRule.onNodeWithText("EST").assertExists()
        composeRule.onNodeWithContentDescription(app!!.getString(R.string.change_language))
            .assertExists()
    }

    @Test
    fun collapsedSearchBarMatchesTheMaterial3Geometry() {
        awaitNav()

        // The collapsed global bar is the full-width M3 search bar: the field
        // spans the bar edge to edge, and the bar's own 8dp vertical breathing
        // room is the only space above the next row. The pre-1.4 `SearchBar`
        // modifier padding that used to be reapplied onto the InputField broke
        // both: it shrank the field (12.dp each side) and inflated the pill
        // (+8.dp), stealing 16.dp more vertical space from the dictionary row
        // and content below. Robolectric's font metrics make the field taller
        // than the spec 56dp here, so we assert layout geometry (the field
        // fills the pill, the pill has no padded rim), not absolute dp.
        val field = composeRule.onAllNodes(hasSetTextAction())[0].fetchSemanticsNode()
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().size.width
        val tolerancePx = with(composeRule.density) { 2.dp.roundToPx() }

        // The field spans the whole bar, so its content isn't pushed in from
        // the edges (a re-added side padding shrinks it below the screen).
        assertThat(field.size.width).isAtLeast(rootWidth - tolerancePx)

        // That the field's content spans the whole bar means no side padding
        // was re-added on the InputField. The nav row's first control is the
        // language split button's menu segment, whose top is not a stable
        // anchor here: the old single current-language button was shorter than the row and sat below its top by the row-centering
        // slack, while the merged split button is the row's tallest element and
        // sits flush. So assert the bar's own geometry instead — the pill
        // (the full-width clickable search bar) is exactly the field's bounds
        // with no padded rim, which is what a re-added InputField modifier
        // padding would inflate.
        val bar = composeRule
            .onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .first { it.boundsInRoot.width >= rootWidth - tolerancePx }
            .boundsInRoot
        assertThat(Math.abs(bar.top - field.boundsInRoot.top).toDouble())
            .isAtMost(tolerancePx.toDouble())
        assertThat(Math.abs(bar.bottom - (field.boundsInRoot.top + field.size.height)).toDouble())
            .isAtMost(tolerancePx.toDouble())
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun webViewScrollCollapsesTheWordBarForJsonWords() {
        awaitNav()
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val vm = topWordViewModel()!!
        awaitCondition(message = "word renders and wires the bar behavior") {
            vm.bottomBarScrollBehavior != null
        }
        val behavior = vm.bottomBarScrollBehavior!!
        // The custom pill must measure itself and set the travel limit, like
        // M3's BottomAppBarLayout does; without it heightOffset stays 0.
        awaitCondition(message = "the bar recorded its travel distance") {
            behavior.state.heightOffsetLimit < 0f
        }

        val rootHeight = composeRule.onRoot().fetchSemanticsNode().size.height.toFloat()
        val restingTop = toolbarTop()
        assertThat(restingTop).isLessThan(rootHeight)

        // Words scroll inside the pinned WebView (invisible to Compose nested
        // scroll), so WordScreen bridges it to the bar behavior: a downward
        // scroll hides the bar by its own travel distance, leaving the pill
        // fully below the root.
        onMain { vm.webViewScrolled(0, 8000) }
        assertThat(toolbarTop()).isAtLeast(rootHeight)

        // A small scroll back up starts revealing it immediately, however far
        // down the page is (the regression: reading the unbounded contentOffset
        // kept the bar off-screen until the page was back near the top).
        onMain { vm.webViewScrolled(8000, 7980) }
        assertThat(toolbarTop()).isLessThan(rootHeight)

        // Scrolling all the way back restores the resting position.
        onMain { vm.webViewScrolled(7980, 0) }
        assertThat(toolbarTop()).isWithin(0.5f).of(restingTop)
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

    // ---------- Scroll-position persistence (a covered word destination is
    // torn down and its WebView re-created, resetting the pinned WebView's
    // internal scroll; the ViewModel must remember the offset and restore it
    // on the re-render) ----------

    /** A detached [WebView] pre-scrolled to [scrollY], standing in for a
     * user-scrolled page: Robolectric's `View.scrollTo` updates the real
     * `mScrollY`, so `getScrollY()` reports what it was told. */
    private fun scrolledWebView(scrollY: Int): WebView =
        WebView(composeRule.activity).also { it.scrollTo(0, scrollY) }

    /** The top edge of the floating word bar (its always-present add-card
     * button) relative to the root; at or below the root height when the bar is
     * fully out. Uses `positionInRoot` because `boundsInRoot` clips a node that
     * is off-screen back to the origin. */
    private fun toolbarTop(): Float {
        composeRule.waitForIdle()
        return composeRule
            .onNodeWithContentDescription(app!!.getString(R.string.menu_add_card))
            .fetchSemanticsNode().positionInRoot.y
    }

    @Test
    fun jsonWordScrollPositionSurvivesAnotherWordCoveringItAndBack() {
        awaitNav()

        // Word A scrolls inside its pinned WebView (JSON words). Push word B
        // over it — the bug's "navigate to a different word" — then pop B: the
        // re-created WebView must come back at A's saved offset, not the top.
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val a = topWordViewModel()!!
        onMain { a.webView = scrolledWebView(800) }
        val frente = est.url("/frente").toString()

        est.enqueue(MockResponse().setBody(estMuerte()))
        openWord(est, "/muerte")

        // Word A must have recorded its scroll offset as B covered it (the
        // destination's ON_PAUSE or its disposal, whichever runs first).
        awaitCondition(message = "word A remembers its scroll while covered") {
            a.savedScrollY == 800
        }

        // Back to A: the same restoreWebViewScroll the page-finish listener
        // runs must scroll the freshly re-created WebView back down.
        onMain { composeRule.activity.navController?.popBackStack() }
        awaitCondition(message = "back restores word A's display") {
            val vm = topWordViewModel()
            vm != null && vm.webView != null &&
                ordboken().currentWord?.uri?.toString() == frente
        }
        onMain { topWordViewModel()?.restoreWebViewScroll() }
        awaitCondition(message = "the re-created WebView is scrolled back down") {
            topWordViewModel()?.webView?.scrollY == 800
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun jsonWordScrollRestoreDoesNotStrandTheHidBar() {
        awaitNav()

        // Word A is scrolled far down with the word bar fully hidden.
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val a = topWordViewModel()!!
        awaitCondition(message = "the bar recorded its travel distance") {
            a.bottomBarScrollBehavior?.state?.heightOffsetLimit ?: 0f < 0f
        }
        val rootHeight = composeRule.onRoot().fetchSemanticsNode().size.height.toFloat()
        onMain {
            a.webView = scrolledWebView(800)
            a.webViewScrolled(0, 8000)
            a.captureScroll()
        }
        assertThat(toolbarTop()).isAtLeast(rootHeight)

        // Push word B over A, then pop back to A exactly like the covered
        // destination flow: the re-created WebView starts at the top and the
        // page-finish listener restores the saved offset with
        // restoreWebViewScroll, whose programmatic scroll fires the bridge
        // again ("double-counting" the raw offset).
        est.enqueue(MockResponse().setBody(estMuerte()))
        openWord(est, "/muerte")
        onMain { composeRule.activity.navController?.popBackStack() }
        awaitCondition(message = "back restores word A's view") {
            val vm = topWordViewModel()
            vm != null && vm.webView != null
        }

        val vm = topWordViewModel()!!
        onMain { vm.restoreWebViewScroll() }
        awaitCondition(message = "re-created WebView is scrolled back down") {
            topWordViewModel()?.webView?.scrollY == 800
        }

        // Because the bar is positioned from the clamped heightOffset, the
        // re-fed restore scroll can't push it out of reach: it is still exactly
        // one travel distance out...
        assertThat(toolbarTop()).isAtLeast(rootHeight)

        // ...and a small scroll up brings it straight back on screen (before,
        // the unbounded contentOffset ended a full savedScrollY deeper than any
        // up-scroll could recover, so the bar never reappeared on word A).
        onMain { vm.webViewScrolled(800, 780) }
        assertThat(toolbarTop()).isLessThan(rootHeight)
    }

    @Test
    fun jsonWordScrollCaptureReadsThePinnedWebView() {
        awaitNav()
        est.enqueue(MockResponse().setBody(estFrente()))
        openWord(est, "/frente")
        val vm = topWordViewModel()!!
        onMain { vm.webView = scrolledWebView(700) }
        onMain { vm.captureScroll() }
        assertThat(vm.savedScrollY).isEqualTo(700)
    }
}
