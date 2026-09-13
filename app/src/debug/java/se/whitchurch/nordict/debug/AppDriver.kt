package se.whitchurch.nordict.debug

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.AgentState
import se.whitchurch.nordict.ExactMatch
import se.whitchurch.nordict.MainActivity
import se.whitchurch.nordict.MultiDict
import se.whitchurch.nordict.Ordboken
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.toSearchResultData
import se.whitchurch.nordict.wordResultOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes agent commands against the live app. Runs on a background thread
 * (the agent server's connection thread): UI launches are marshalled onto the
 * main looper via [onMain], while searches and page loads are awaited by
 * polling [Ordboken.currentWord] and the navigation destination — the app's
 * own coroutines do the heavy lifting.
 *
 * The app is a single-[MainActivity] Compose navigation graph (`home`,
 * `search`, `word` routed through `NavHostController`), so `open`/`openUri`/
 * `nextPage` navigate a word destination and `back` pops one destination.
 * `state.activity` reports the current route rather than an activity class.
 */
class AppDriver(private val app: android.app.Application) {

    private val tracker: ActivityTracker
        get() = (app as? DebugApp)?.activityTracker ?: ActivityTracker()

    private fun ordboken(): Ordboken = Ordboken.getInstance(app)

    private fun requireMainActivity(): MainActivity {
        val activity = tracker.current as? MainActivity
            ?: throw IllegalStateException("no MainActivity resumed — launch the app first")
        return activity
    }

    fun execute(command: AgentCommand): AgentResult {
        return try {
            when (command.op) {
                AgentOps.SEARCH -> opSearch(command)
                AgentOps.OPEN -> opOpen(command)
                AgentOps.OPEN_URI -> opOpenUri(command)
                AgentOps.NEXT_PAGE -> opNextPage(command)
                AgentOps.BACK -> opBack(command)
                AgentOps.SET_DICT -> opSetDict(command)
                AgentOps.SET_LANG -> opSetLang(command)
                AgentOps.STATE -> AgentResult(ok = true, op = command.op, state = snapshot())
                else -> AgentResult.error(command.op, "unknown op '${command.op}'")
            }
        } catch (e: Exception) {
            AgentResult.error(command.op, e.message ?: e.toString())
        }
    }

    private fun opSearch(command: AgentCommand): AgentResult {
        val query = command.require("query", command.query)
        val results = ordboken().search(query, 0)
        return AgentResult(
            ok = true,
            op = AgentOps.SEARCH,
            message = if (results.isEmpty()) "no search results for '$query'" else null,
            state = snapshot(),
            results = results.map { it.toSearchResultData() }
        )
    }

    private fun opOpen(command: AgentCommand): AgentResult {
        if (command.uri != null) return opOpenUri(command)
        val query = command.require("query", command.query)
        val results = ordboken().search(query, 0)
        val exact = ExactMatch.resolve(query, results)
        if (exact == null) {
            val suggestions = results.map { it.mTitle }.distinct()
            return AgentResult.error(
                AgentOps.OPEN,
                "no unique exact match for '$query'" +
                    if (suggestions.isEmpty()) "" else " (run search to pick from: ${suggestions.joinToString(", ")})"
            )
        }
        val uri = Uri.parse(exact.uri.toString())
        if (exact.sources.isNotEmpty()) {
            // Combined selection: open the merged page addressed by its sources;
            // the loaded word's uri is the first source's page. The combined
            // fetch always builds a fresh instance (no single-uri word cache),
            // so wait for that fetch rather than a previously loaded same-uri
            // combined word sitting in Ordboken.currentWord.
            onMain {
                requireMainActivity().navigateToSources(exact.sources, exact.mTitle, null)
            }
            val firstSource = exact.sources.first().uri
            val previous = ordboken().currentWord
            await({
                val w = ordboken().currentWord
                w != null && w !== previous && loadedUriMatches(firstSource, w)
            }, 30_000)
            val word = ordboken().currentWord
                ?: throw IllegalStateException("word never loaded: $firstSource")
            return wordResult(AgentOps.OPEN, word)
        }
        return openUri(AgentOps.OPEN, uri, exact.mTitle)
    }

    private fun opOpenUri(command: AgentCommand): AgentResult {
        val raw = command.require("uri", command.uri)
        val uri = Uri.parse(raw)
        return openUri(AgentOps.OPEN_URI, uri, command.title ?: uri.lastPathSegment ?: "")
    }

    private fun openUri(op: String, uri: Uri, title: String): AgentResult {
        onMain {
            requireMainActivity().navigateToWord(uri, title)
        }
        val word = waitForOpen(uri.toString().toHttpUrlOrNull())
        return wordResult(op, word)
    }

    private fun opNextPage(command: AgentCommand): AgentResult {
        val word = ordboken().currentWord
            ?: return AgentResult.error(AgentOps.NEXT_PAGE, "no word loaded — open a word first")
        val entries = word.mHomonymEntries
        val ref = word.xrefs.firstOrNull() ?: ""
        val idx = entries.indexOfFirst { it.ref == ref && it.mTitle == word.mTitle }

        if (entries.isEmpty() || idx < 0 || idx >= entries.size - 1) {
            val total = entries.size
            return AgentResult(
                ok = true,
                op = AgentOps.NEXT_PAGE,
                message = if (total <= 1) "no next entry (single-entry page)"
                else "already at last entry ($total of $total)",
                state = snapshot(),
                word = wordResultOf(word)
            )
        }

        val next = entries[idx + 1]
        if (MultiDict.isCombinedRef(ref)) {
            // A combined page has no single fetchable page for the next entry;
            // the merged word is already cached in memory, so swap the entry
            // in place (fresh Word carrying the full combined entry set).
            val swapped = onMain {
                val combined = Word.withEntry(word, next, word.searchHeadword)
                ordboken().currentWord = combined
                combined
            }
            return wordResult(AgentOps.NEXT_PAGE, swapped)
        }
        // The loaded word's uri already carries its own __ref (e.g.
        // /muerte?__ref=2); building the next page uri must not stack a
        // second __ref param, or the dictionary resolves the first one again.
        val pageUri = word.uri.newBuilder()
            .removeAllQueryParameters("__ref")
            .addQueryParameter("__ref", next.ref)
            .build()
        onMain {
            requireMainActivity().navigateToWord(Uri.parse(pageUri.toString()), next.mTitle)
        }
        val opened = waitForOpen(pageUri)
        return wordResult(AgentOps.NEXT_PAGE, opened)
    }

    private fun opBack(command: AgentCommand): AgentResult {
        val poppedFrom = onMain {
            val nav = requireMainActivity().navController ?: return@onMain null
            val route = nav.currentDestination?.route
            if (route == null || !nav.popBackStack()) null else route
        }
        val word = ordboken().currentWord
        return AgentResult(
            ok = true,
            op = AgentOps.BACK,
            message = when {
                poppedFrom == null -> "back: no destination to pop"
                routeName(poppedFrom) == "word" -> "back: closed the word view"
                else -> "back: left the word view"
            },
            state = snapshot(),
            word = word?.let { wordResultOf(it) }
        )
    }

    private fun opSetDict(command: AgentCommand): AgentResult {
        val tags = command.selectionTags()
        if (tags.isEmpty()) return AgentResult.error(AgentOps.SET_DICT, "no dictionary tag(s) given")
        return switchOp(AgentOps.SET_DICT) {
            val ok = ordboken().setCurrentDictionaries(tags)
            ok to (if (ok) "selection is now ${ordboken().selectionSignature}"
            else "unknown or incompatible selection '${tags.joinToString(",")}'")
        }
    }

    private fun opSetLang(command: AgentCommand): AgentResult {
        val lang = command.require("lang", command.lang)
        return switchOp(AgentOps.SET_LANG) {
            val ok = ordboken().setLanguage(lang)
            ok to (if (ok) "language is now ${ordboken().currentDictionary.lang} (${ordboken().currentDictionary.tag})"
            else "unknown language '$lang'")
        }
    }

    /**
     * Runs a dictionary/language switch on the main thread. Switching while a
     * word destination is up would otherwise fire the cross-link hook and
     * navigate to the new dictionary, so the hook is cleared first and the
     * switch happens in place: the agent stays on the current word view, but
     * the active dictionary/language (and the next `search`/`open`) is the
     * new one. We deliberately do *not* pop the word destination — popping
     * would restore the previous word and immediately reopen another word
     * view, so the view would never go away.
     */
    private fun switchOp(op: String, switch: () -> Pair<Boolean, String>): AgentResult {
        val result = onMain {
            ordboken().onDictChanged = null
            switch()
        }
        return if (result.first) {
            AgentResult(ok = true, op = op, message = result.second, state = snapshot())
        } else {
            AgentResult.error(op, result.second)
        }
    }

    /** Project route patterns (e.g. `word?uri={uri}&title={title}`) onto their base names. */
    private fun routeName(route: String): String = when {
        route == "home" -> "home"
        route.startsWith("search?") -> "search"
        route.startsWith("word?") -> "word"
        else -> route.substringBefore('?')
    }

    private fun snapshot(): AgentState {
        val word = ordboken().currentWord
        val route = (tracker.current as? MainActivity)?.navController?.currentDestination?.route
        val signature = ordboken().selectionSignature
        return AgentState(
            activity = route?.let { routeName(it) } ?: (tracker.current?.javaClass?.simpleName ?: ""),
            dict = signature,
            lang = ordboken().currentDictionary.lang,
            dicts = ordboken().activeDicts.takeIf { it.isNotEmpty() }?.map { it.tag },
            word = word?.let { wordResultOf(it) }
        )
    }

    private fun wordResult(op: String, word: Word): AgentResult {
        return AgentResult(ok = true, op = op, state = snapshot(), word = wordResultOf(word))
    }

    /**
     * Polls until the app has loaded the word served from [request] (host and
     * path must match, and if the request carries a `__ref`, that ref must be
     * among the loaded word's xrefs). The word JSON is available as soon as
     * the word ViewModel finishes, so this doubles as the "render" barrier.
     * Reopening a single-dictionary page legitimately reuses the cached word
     * instance (Ordboken's HttpUrl-keyed cache); combined pages handle their
     * own freshness in [opOpen].
     */
    private fun waitForOpen(request: HttpUrl?, timeoutMs: Int = 30_000): Word {
        if (request == null) {
            await({ ordboken().currentWord != null })
            return ordboken().currentWord ?: throw IllegalStateException("word never loaded")
        }
        await({ loadedUriMatches(request, ordboken().currentWord) }, timeoutMs)
        return ordboken().currentWord ?: throw IllegalStateException("word never loaded: $request")
    }

    private fun loadedUriMatches(request: HttpUrl, word: Word?): Boolean {
        if (word == null) return false
        if (word.uri.host != request.host || word.uri.encodedPath != request.encodedPath) return false
        val ref = request.queryParameter("__ref")
        return ref == null || word.xrefs.contains(ref)
    }

    /** Polls [condition] every ~25ms until true or [timeoutMs] elapses. */
    private fun await(condition: () -> Boolean, timeoutMs: Int = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("timed out waiting for the app")
            }
            Thread.sleep(25)
        }
    }

    /** Runs [block] on the main looper and returns its result; rethrows errors. */
    private fun <T> onMain(block: () -> T): T {
        val looper = Looper.getMainLooper()
        if (Looper.myLooper() == looper) return block()
        var result: T? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(looper).post {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}