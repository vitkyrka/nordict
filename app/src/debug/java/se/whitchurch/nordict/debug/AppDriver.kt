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
import se.whitchurch.nordict.Ordboken
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordActivity
import se.whitchurch.nordict.toSearchResultData
import se.whitchurch.nordict.wordResultOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Executes agent commands against the live app. Runs on a background thread
 * (the agent server's connection thread): UI launches are marshalled onto the
 * main looper via [onMain], while searches and page loads are awaited by
 * polling [Ordboken.currentWord] and the tracked activity — the app's own
 * async tasks do the heavy lifting.
 */
class AppDriver(private val app: android.app.Application) {

    private val tracker: ActivityTracker
        get() = (app as? DebugApp)?.activityTracker ?: ActivityTracker()

    private fun ordboken(): Ordboken = Ordboken.getInstance(app)

    private fun requireActivity(): AppCompatActivity {
        val activity = tracker.current as? AppCompatActivity
            ?: throw IllegalStateException("no activity resumed — launch the app first")
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
        return openUri(AgentOps.OPEN, uri, exact.mTitle)
    }

    private fun opOpenUri(command: AgentCommand): AgentResult {
        val raw = command.require("uri", command.uri)
        val uri = Uri.parse(raw)
        return openUri(AgentOps.OPEN_URI, uri, command.title ?: uri.lastPathSegment ?: "")
    }

    private fun openUri(op: String, uri: Uri, title: String): AgentResult {
        onMain {
            Ordboken.startWordActivity(requireActivity(), title, uri)
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
        // The loaded word's uri already carries its own __ref (e.g.
        // /muerte?__ref=2); building the next page uri must not stack a
        // second __ref param, or the dictionary resolves the first one again.
        val pageUri = word.uri.newBuilder()
            .removeAllQueryParameters("__ref")
            .addQueryParameter("__ref", next.ref)
            .build()
        onMain {
            Ordboken.startWordActivity(requireActivity(), next.mTitle, Uri.parse(pageUri.toString()))
        }
        val opened = waitForOpen(pageUri)
        return wordResult(AgentOps.NEXT_PAGE, opened)
    }

    private fun opBack(command: AgentCommand): AgentResult {
        val finished = onMain {
            val activity = requireActivity()
            if (activity is WordActivity) {
                activity.finish()
                activity
            } else {
                null
            }
        }
        if (finished != null) {
            // The destroy runs on the next main-loop pass; wait for it so the
            // snapshot reflects the resumed activity beneath (the previous
            // word view, or MainActivity) rather than the finishing one.
            // Tolerate the timeout: under Robolectric a finished scenario
            // activity is never destroyed, and on a device the destroy
            // normally lands within one loop pass.
            try {
                await({ tracker.current !== finished }, timeoutMs = 3_000)
            } catch (e: IllegalStateException) {
                // ignore: fall through to the snapshot below
            }
        }
        val word = ordboken().currentWord
        return AgentResult(
            ok = true,
            op = AgentOps.BACK,
            message = if (finished != null) {
                if (tracker.current is WordActivity) {
                    "back: closed the word view"
                } else {
                    "back: left the word view"
                }
            } else {
                "back: not on a word view"
            },
            state = snapshot(),
            word = word?.let { wordResultOf(it) }
        )
    }

    private fun opSetDict(command: AgentCommand): AgentResult {
        val tag = command.require("tag", command.tag)
        return switchOp(AgentOps.SET_DICT) {
            val ok = ordboken().setCurrentDictionary(tag)
            ok to (if (ok) "dictionary is now ${ordboken().currentDictionary.tag}" else "unknown dictionary '$tag'")
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
     * [WordActivity] is up would otherwise fire the cross-link hook and
     * navigate to the new dictionary, so the hook is cleared first and the
     * switch happens in place: the agent stays on the current word view, but
     * the active dictionary/language (and the next `search`/`open`) is the
     * new one. We deliberately do *not* finish the word view — on a real
     * device the HistoryActivity behind it restores the last word and
     * immediately reopens a WordActivity, so the view would never go away.
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

    private fun snapshot(): AgentState {
        val word = ordboken().currentWord
        return AgentState(
            activity = tracker.current?.javaClass?.simpleName ?: "",
            dict = ordboken().currentDictionary.tag,
            lang = ordboken().currentDictionary.lang,
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
     * the app's word task finishes, so this doubles as the "render" barrier.
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