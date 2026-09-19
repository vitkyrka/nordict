package se.whitchurch.nordict.debug

import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import se.whitchurch.nordict.AnkiApi
import se.whitchurch.nordict.CardActivity
import se.whitchurch.nordict.DleDictionary
import se.whitchurch.nordict.EstDictionary
import se.whitchurch.nordict.GdlcDictionary
import se.whitchurch.nordict.LeRobertDictionary
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
        se.whitchurch.nordict.NordictPrefs.clearBlocking(app!!)
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
     * so the app lands on the search destination (empty query = history) with no network calls.
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

    /**
     * `runSearch` is the navigation op behind "hit enter in the search bar":
     * it lands on the search-results destination for the current dictionary.
     * (The results list's own rendering — including the duplicate-uri LazyColumn
     * key regression — is covered by [se.whitchurch.nordict.SearchScreenUiTest].)
     */
    @Test
    fun runSearchNavigatesToTheResultsDestination() {
        launchMain()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("[]")
        }

        val search = drive(AgentCommand(op = AgentOps.RUN_SEARCH, query = "frente"))
        assertThat(search.ok).isTrue()
        assertThat(search.state?.activity).isEqualTo("search")
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

        // Popping the word destination lands back on search.
        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.state?.activity).isEqualTo("search")
    }

    // ---------------------------------------------------------------------------
    // Fake AnkiApi
    // ---------------------------------------------------------------------------

    private class RecordingAnkiApi : AnkiApi {
        val createdDecks = mutableListOf<String>()
        val createdModels = mutableListOf<String>()
        var addedNotes = mutableListOf<Long>()
        var addedFields = mutableListOf<Array<String>>()

        override fun deckList(): Map<Long, String>? = mapOf(99L to "Existing")
        override fun modelList(): Map<Long, String>? = null
        override fun addNewDeck(name: String): Long? { createdDecks.add(name); return 100L }
        override fun addNewCustomModel(
            name: String,
            fields: Array<String>,
            cardNames: Array<String>,
            questionFormats: Array<String>,
            answerFormats: Array<String>,
            css: String?,
            did: Long?,
            usn: Int?
        ): Long? { createdModels.add(name); return 200L }
        override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>?): Long? {
            val id = 42L + addedNotes.size
            addedNotes.add(id)
            addedFields.add(fields)
            return id
        }
    }

    private fun seedAndOpenWord(
        tag: String = "dle",
        path: String = "/frente",
        htmlFile: String = "../testdata/dle.html",
        enqueueTwice: Boolean = false
    ): String {
        val html = File(htmlFile).readText()
        server.enqueue(MockResponse().setBody(html))
        if (enqueueTwice) server.enqueue(MockResponse().setBody(html))
        launchMain()
        val open = drive(AgentCommand(op = AgentOps.OPEN_URI, uri = server.url(path).toString()))
        assertThat(open.ok).isTrue()
        return htmlFile
    }

    /**
     * Brings the card screen up for the current word, exactly like the FAB /
     * the agent's `openCards` op. Under Robolectric a runtime `startActivity`
     * never resumes a second activity, so the test pre-launches CardActivity
     * through [ActivityScenario] and then drives `openCards`, whose await is
     * satisfied by the already-resumed card screen (the op's redundant
     * relaunch is a no-op).
     */
    private fun launchCardActivity(): ActivityScenario<CardActivity> =
        ActivityScenario.launch(CardActivity::class.java).also {
            shadowOf(Looper.getMainLooper()).runToEndOfTasks()
            assertThat(trackedActivity()).isInstanceOf(CardActivity::class.java)
        }

    @Test
    fun openCardsLaunchesCardActivity() {
        seedAndOpenWord()
        launchCardActivity()
        val result = drive(AgentCommand(op = AgentOps.OPEN_CARDS))
        assertThat(result.ok).isTrue()
        assertThat(result.state?.activity).isEqualTo("CardActivity")
    }

    @Test
    fun openCardsWithoutWordErrors() {
        launchMain()
        val result = drive(AgentCommand(op = AgentOps.OPEN_CARDS))
        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("no word loaded")
    }

    @Test
    fun createCardWithFakeApiAndReturnsNoteId() {
        val fake = RecordingAnkiApi()
        CardActivity.debugAnkiApi = fake
        seedAndOpenWord()
        launchCardActivity()

        val open = drive(AgentCommand(op = AgentOps.OPEN_CARDS))
        assertThat(open.ok).isTrue()
        assertThat(open.state?.activity).isEqualTo("CardActivity")

        val created = drive(AgentCommand(op = AgentOps.CREATE_CARD))
        assertThat(created.ok).isTrue()
        assertThat(created.message).contains("card created")
        assertThat(created.message).contains("note id 42")

        // Back field is the 4th element (index 3) in the encoded note fields.
        assertThat(fake.addedFields).hasSize(1)
        assertThat(fake.addedFields[0][3]).isNotEmpty()
    }

    @Test
    fun createCardWithExplicitIndex() {
        val fake = RecordingAnkiApi()
        CardActivity.debugAnkiApi = fake
        seedAndOpenWord()
        launchCardActivity()

        // The DLE fixture proposes 7 definitions + 4 grouped idioms, so
        // index 2 is the 3rd definition.
        val created = drive(AgentCommand(op = AgentOps.CREATE_CARD, index = 2))
        assertThat(created.ok).isTrue()
        assertThat(created.message).contains("note id 42")
    }

    @Test
    fun createCardWithoutCardActivityErrors() {
        seedAndOpenWord()
        val result = drive(AgentCommand(op = AgentOps.CREATE_CARD))
        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("no card screen up")
    }

    @Test
    fun previewCardReturnsFrontAndBackWithoutTouchingAnki() {
        val fake = RecordingAnkiApi()
        CardActivity.debugAnkiApi = fake
        seedAndOpenWord()
        launchCardActivity()

        val preview = drive(AgentCommand(op = AgentOps.PREVIEW_CARD))
        assertThat(preview.ok).isTrue()
        assertThat(preview.preview).isNotNull()
        assertThat(preview.preview!!.frontHtml).isNotEmpty()
        assertThat(preview.preview!!.backField).contains("<div style=\"text-align: left\">")

        // Previewing never inserts a note.
        assertThat(fake.addedNotes).isEmpty()

        // An explicit index previews that proposal: the first idiom card
        // renders the <strong> idiom header, not a definition fragment.
        val word = Ordboken.getInstance(app!!).currentWord!!
        val proposals = se.whitchurch.nordict.Cards.proposals(word)
        val firstIdiom = proposals.indexOfFirst { it is se.whitchurch.nordict.CardProposal.Idiom }
        assertThat(firstIdiom).isGreaterThan(0)
        val idiom = drive(AgentCommand(op = AgentOps.PREVIEW_CARD, index = firstIdiom))
        assertThat(idiom.ok).isTrue()
        assertThat(idiom.preview!!.backField).contains("<strong>")
    }

    @Test
    fun previewCardWithoutCardActivityErrors() {
        seedAndOpenWord()
        val result = drive(AgentCommand(op = AgentOps.PREVIEW_CARD))
        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("no card screen up")
    }

    @Test
    fun backClosesTheCardScreen() {
        seedAndOpenWord()
        launchCardActivity()
        assertThat(trackedActivity()).isInstanceOf(CardActivity::class.java)

        // Under Robolectric finish() marks the activity finishing without ever
        // destroying the scenario-managed instance, so assert the driver took
        // the card-screen branch (immediate isFinishing await) rather than the
        // tracker teardown. On-device the destroy + MainActivity re-resume
        // follow a frame later.
        val back = drive(AgentCommand(op = AgentOps.BACK))
        assertThat(back.ok).isTrue()
        assertThat(back.message).contains("closed the card screen")
        assertThat(back.state?.activity).isEqualTo("CardActivity")
    }

    @Test
    fun audioWithoutAWordErrors() {
        launchMain()
        val result = drive(AgentCommand(op = AgentOps.AUDIO))
        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("no word loaded")
    }

    @Test
    fun audioReplayResetsThePlaylistNotStacksIt() {
        seedAndOpenWord()
        val first = server.url("/sounds/a.mp3").toString()
        val second = server.url("/sounds/b.mp3").toString()

        // First tap plays the pronunciation…
        val one = drive(AgentCommand(op = AgentOps.AUDIO, url = first))
        assertThat(one.ok).isTrue()
        assertThat(one.message).contains("playlist has 1 item(s)")

        // …the second tap must start a fresh single-item playlist, not stack a
        // second copy (the regression: the waiting player stayed parked on the
        // already-ended item and never restarted).
        val two = drive(AgentCommand(op = AgentOps.AUDIO, url = second))
        assertThat(two.ok).isTrue()
        assertThat(two.message).contains("playlist has 1 item(s)")
    }

    @Test
    fun audioForAWordWithoutAudioErrors() {
        // DLE's /frente fixture carries no audio; the op must say so clearly
        // instead of silently playing nothing.
        seedAndOpenWord()
        val result = drive(AgentCommand(op = AgentOps.AUDIO))
        assertThat(result.ok).isFalse()
        assertThat(result.error).contains("has no audio URLs")
    }

    @Test
    fun stateReportsSoundDisabledForAWordWithoutAudio() {
        // The A/V is on-screen so `sound` must be false (play button disabled)
        // for a loaded word with no audio — here DLE's /frente fixture.
        seedAndOpenWord()
        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.state?.word).isNotNull()
        assertThat(state.state?.sound).isFalse()
    }

    @Test
    fun stateReportsSoundNullWhenNoWordIsLoaded() {
        launchMain()
        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.state?.word).isNull()
        assertThat(state.state?.sound).isNull()
    }

    @Test
    fun stateReportsSoundEnabledForAWordWithAudio() {
        // Serve the LE ROBERT fixture: DLE and EST probe the page first (each
        // consumes a response and finds nothing), then ROB parses it into a
        // word whose audio list is non-empty, so `sound` must be true (play
        // button enabled).
        val html = File("../testdata/rob/table.html").readText()
        repeat(3) { server.enqueue(MockResponse().setBody(html)) }

        // Temporarily reseed the app with the ROB dictionary added so the
        // getWord probe loop reaches it after the es dictionaries return null.
        Ordboken.reset()
        val testClient = OkHttpClient()
        Ordboken.getInstance(
            app!!,
            testClient,
            arrayOf(
                DleDictionary(testClient, server.url("/").toString().removeSuffix("/")),
                EstDictionary(testClient, server.url("/").toString().removeSuffix("/")),
                LeRobertDictionary(testClient, server.url("/").toString().removeSuffix("/"))
            )
        )
        driver = AppDriver(app!!)
        launchMain()

        val open = drive(AgentCommand(
            op = AgentOps.OPEN_URI,
            uri = server.url("/definition/table").toString()
        ))
        assertThat(open.ok).isTrue()
        assertThat(open.state?.word).isNotNull()
        assertThat(open.state?.sound).isTrue()

        val state = drive(AgentCommand(op = AgentOps.STATE))
        assertThat(state.state?.sound).isTrue()
    }

    private fun trackedActivity(): android.app.Activity? =
        (ApplicationProvider.getApplicationContext<android.app.Application>() as DebugApp).activityTracker.current
}