package se.whitchurch.nordict.debug

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.AgentState
import se.whitchurch.nordict.CardActivity
import se.whitchurch.nordict.CollinsTransport
import se.whitchurch.nordict.InfopediaTransport
import se.whitchurch.nordict.ExactMatch
import se.whitchurch.nordict.MainActivity
import se.whitchurch.nordict.MultiDict
import se.whitchurch.nordict.Ordboken
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.WordViewModel
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
 * The app is a single-[MainActivity] Compose navigation graph (`search`,
 * `word` routed through `NavHostController`), so `open`/`openUri`/
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
                AgentOps.RUN_SEARCH -> opRunSearch(command)
                AgentOps.OPEN -> opOpen(command)
                AgentOps.OPEN_URI -> opOpenUri(command)
                AgentOps.NEXT_PAGE -> opNextPage(command)
                AgentOps.BACK -> opBack(command)
                AgentOps.OPEN_CARDS -> opOpenCards(command)
                AgentOps.CREATE_CARD -> opCreateCard(command)
                AgentOps.PREVIEW_CARD -> opPreviewCard(command)
                AgentOps.AUDIO -> opAudio(command)
                AgentOps.SET_DICT -> opSetDict(command)
                AgentOps.SET_LANG -> opSetLang(command)
                AgentOps.SWAP_LANG -> opSwapLang(command)
                AgentOps.HTML -> opHtml(command)
                AgentOps.STATE -> AgentResult(ok = true, op = command.op, state = snapshot())
                else -> AgentResult.error(command.op, "unknown op '${command.op}'")
            }
        } catch (e: Exception) {
            AgentResult.error(command.op, e.message ?: e.toString())
        }
    }

    private fun opSearch(command: AgentCommand): AgentResult {
        val query = command.require("query", command.query).trim()
        val results = ordboken().search(query, 0)
        // When a Collins/Infopedia lookup just went through the challenge
        // fallback, say so: an empty result then means "challenged", not "no
        // such word".
        val notice = listOfNotNull(
            CollinsTransport.recentFallbackNotice(),
            InfopediaTransport.recentFallbackNotice()
        ).joinToString(". ").takeIf { it.isNotEmpty() }
        return AgentResult(
            ok = true,
            op = AgentOps.SEARCH,
            message = if (results.isEmpty()) {
                listOfNotNull("no search results for '$query'", notice).joinToString(". ")
            } else notice,
            state = snapshot(),
            results = results.map { it.toSearchResultData() }
        )
    }

    /**
     * Runs a full search in the UI, exactly like hitting enter in the search
     * bar: navigates to the search-results destination, whose `fullSearch`
     * renders the current dictionary's results. Waits for the destination to
     * land so a search-results composition crash (e.g. duplicate LazyColumn
     * keys) surfaces here instead of racing the caller.
     */
    private fun opRunSearch(command: AgentCommand): AgentResult {
        val query = command.require("query", command.query).trim()
        closeCardScreenIfUp()
        onMain {
            requireMainActivity().navigateToSearch(query)
        }
        await({ snapshot().activity == "search" }, 15_000)
        return AgentResult(
            ok = true,
            op = AgentOps.RUN_SEARCH,
            message = "search result screen up for '$query'",
            state = snapshot()
        )
    }

    private fun opOpen(command: AgentCommand): AgentResult {
        if (command.uri != null) return opOpenUri(command)
        val query = command.require("query", command.query).trim()
        val results = ordboken().search(query, 0)
        // Same singular fallback as the word view's `/search/` handler, so an
        // agent can `open` an inflected form (e.g. Spanish "casas" -> "casa").
        val exact = ExactMatch.resolveWithSearch(query, results) { ordboken().search(it, 0) }
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
            closeCardScreenIfUp()
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
        closeCardScreenIfUp()
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
        closeCardScreenIfUp()
        onMain {
            requireMainActivity().navigateToWord(Uri.parse(pageUri.toString()), next.mTitle)
        }
        val opened = waitForOpen(pageUri)
        return wordResult(AgentOps.NEXT_PAGE, opened)
    }

    private fun opBack(command: AgentCommand): AgentResult {
        // Close the card screen first if it's open (mirrors its back arrow):
        // the word view it floats above still owns the loaded word.
        val cardScreen = tracker.current
        if (cardScreen?.javaClass?.simpleName == "CardActivity") {
            val card = cardScreen as CardActivity
            onMain { card.finish() }
            // finish() sets isFinishing synchronously; the destroy lags a
            // frame (forever under Robolectric's scenario controller), so
            // wait for the flag, not the tracker teardown.
            await({ card.isFinishing }, 10_000)
            return AgentResult(
                ok = true,
                op = AgentOps.BACK,
                message = "back: closed the card screen",
                state = snapshot()
            )
        }
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

    /**
     * Opens the card screen for the current word, exactly like the word
     * view's "add card" action: the same `CardActivity` intent (deck name
     * `"Nordict - <dict>"` for a single dictionary, `"Nordict - <LANG>"` for
     * a combined multi-dictionary word). The page CSS is left as the last
     * value in [Ordboken.currentCss] — the WebView `getCSS()` call happens
     * on the FAB path only — so the agent path is headless-friendly while
     * the cards produced are structurally identical.
     */
    private fun opOpenCards(command: AgentCommand): AgentResult {
        val word = ordboken().currentWord
            ?: return AgentResult.error(AgentOps.OPEN_CARDS, "no word loaded — open a word first")
        if (tracker.current == null) {
            return AgentResult.error(AgentOps.OPEN_CARDS, "no activity resumed")
        }
        val deckName = onMain { topWordViewModel()?.deckName } ?: "Nordict - ${word.dict}"
        val intent = android.content.Intent(app, CardActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("deckName", deckName)
        }
        onMain { app.startActivity(intent) }
        await({
            tracker.current?.javaClass?.simpleName == "CardActivity"
        }, 10_000)
        return AgentResult(ok = true, op = AgentOps.OPEN_CARDS, state = snapshot())
    }

    /** Creates the card for proposal [index] on the open card screen. */
    private fun opCreateCard(command: AgentCommand): AgentResult {
        val activity = tracker.current
        if (activity?.javaClass?.simpleName != "CardActivity") {
            return AgentResult.error(
                AgentOps.CREATE_CARD,
                "no card screen up — call openCards first (resumed activity is '${activity?.javaClass?.simpleName}')"
            )
        }
        val card = activity as CardActivity
        // The word/audio load task populates mWord asynchronously on open.
        await({ card.isCardReady() }, 10_000)
        val id = onMain { card.agentCreateCard(command.index) }
        return if (id != null) {
            val remaining = onMain { card.visibleProposals().size }
            AgentResult(
                ok = true,
                op = AgentOps.CREATE_CARD,
                message = "card created (note id $id, $remaining cards left)",
                state = snapshot()
            )
        } else {
            AgentResult.error(
                AgentOps.CREATE_CARD,
                "card creation failed (no proposal, word not loaded yet, or AnkiDroid unreachable)"
            )
        }
    }

    /**
     * Builds the front/back preview for proposal [index] on the open card
     * screen, through the same pipeline the Preview button drives, without
     * touching Anki — so card layout can be inspected without leaving the app.
     */
    private fun opPreviewCard(command: AgentCommand): AgentResult {
        val activity = tracker.current
        if (activity?.javaClass?.simpleName != "CardActivity") {
            return AgentResult.error(
                AgentOps.PREVIEW_CARD,
                "no card screen up — call openCards first (resumed activity is '${activity?.javaClass?.simpleName}')"
            )
        }
        val card = activity as CardActivity
        // The word/audio load task populates mWord asynchronously on open.
        await({ card.isCardReady() }, 10_000)
        val preview = onMain { card.agentPreviewCard(command.index) }
        return if (preview != null) {
            AgentResult(
                ok = true,
                op = AgentOps.PREVIEW_CARD,
                message = "preview for proposal ${command.index ?: 0}",
                state = snapshot(),
                preview = se.whitchurch.nordict.CardPreviewData(
                    frontHtml = preview.frontHtml,
                    backField = preview.backField
                )
            )
        } else {
            AgentResult.error(
                AgentOps.PREVIEW_CARD,
                "preview failed (no proposal or word not loaded yet)"
            )
        }
    }

    /**
     * Plays the current word's pronunciation through the word view's ExoPlayer,
     * exactly like the docked play button, and reports how the playlist settled.
     * Re-`audio` on the same word must reset the playlist — the "playback only
     * works once" bug regressed this: every play stacked another copy of the
     * media item while the player, parked at the already-ended item, never
     * restarted it. A `url` argument plays that URL instead (handy for the
     * search-first dictionaries, where the headword result page rather than
     * the word itself carries the speaker links).
     */
    private fun opAudio(command: AgentCommand): AgentResult {
        val word = ordboken().currentWord
            ?: return AgentResult.error(AgentOps.AUDIO, "no word loaded — open a word first")
        val urls = command.url?.let { java.util.ArrayList(listOf(it)) }
            ?: java.util.ArrayList(word.audio)
        if (urls.isEmpty()) {
            return AgentResult.error(
                AgentOps.AUDIO,
                "loaded word has no audio URLs (${word.dictionary}) — pass a `url` to `audio`"
            )
        }
        val (count, loading) = onMain {
            val vm = topWordViewModel() ?: return@onMain Pair(-1, false)
            vm.playAudio(urls)
            Pair(vm.player.mediaItemCount, vm.isAudioLoading)
        }
        if (count < 0) {
            return AgentResult.error(AgentOps.AUDIO, "no word destination up to play audio")
        }
        return AgentResult(
            ok = true,
            op = AgentOps.AUDIO,
            message = "queued ${urls.size} audio URL(s); playlist has $count item(s); loading=$loading",
            state = snapshot()
        )
    }

    /** The [WordViewModel] of the current word destination, or null. */
    private fun topWordViewModel(): WordViewModel? {
        val activity = tracker.current as? MainActivity ?: return null
        val nav = activity.navController ?: return null
        val entry = nav.currentBackStackEntry ?: return null
        if (entry.destination.route?.startsWith("word?") != true) return null
        return ViewModelProvider(entry)[WordViewModel::class.java]
    }

    /** Closes the card screen if it's still up, so main-activity routes run. */
    private fun closeCardScreenIfUp() {
        val cardScreen = tracker.current
        if (cardScreen?.javaClass?.simpleName == "CardActivity") {
            val card = cardScreen as CardActivity
            onMain { card.finish() }
            await({ card.isFinishing }, 10_000)
        }
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

    private fun opSwapLang(command: AgentCommand): AgentResult {
        return switchOp(AgentOps.SWAP_LANG) {
            val ok = ordboken().swapLang()
            ok to (if (ok) "language swapped to ${ordboken().currentDictionary.lang} (${ordboken().currentDictionary.tag})"
            else "no last language to swap to")
        }
    }

    /**
     * Fetches a page's raw HTML through the dictionary's own transport (the
     * same OkHttp/WebView path a lookup takes, challenges included) and saves
     * it to /data/local/tmp for `adb pull` inspection. Either `uri` directly,
     * or the exact match of `query` in [tag]'s dictionary.
     */
    private fun opHtml(command: AgentCommand): AgentResult {
        val tag = command.require("tag", command.tag).uppercase()
        val dict = ordboken().dictMap[tag]
            ?: return AgentResult.error(AgentOps.HTML, "unknown dictionary tag '$tag'")
        val url = if (command.uri != null) {
            command.uri!!
        } else {
            val query = command.require("query", command.query).trim()
            val results = ordboken().search(query, 0)
            val exact = ExactMatch.resolveWithSearch(query, results) { ordboken().search(it, 0) }
                ?: return AgentResult.error(AgentOps.HTML, "no unique exact match for '$query'")
            exact.uri.toString()
        }
        val result = dict.pageFetcher.fetch(url, emptyMap())
        val slug = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "page"
        // External cache: readable via `adb pull` without root (the app
        // sandbox cannot write /data/local/tmp on recent Android).
        val dir = app.externalCacheDir ?: app.cacheDir
        val file = java.io.File(dir, "nordict-${tag.lowercase()}-$slug.html")
        return try {
            file.writeText(result.body)
            AgentResult(
                ok = true,
                op = AgentOps.HTML,
                message = "saved ${result.body.length} bytes (code ${result.code}) to ${file.absolutePath}",
                state = snapshot()
            )
        } catch (e: Exception) {
            AgentResult.error(AgentOps.HTML, e.message ?: e.toString())
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
        route == "search" || route.startsWith("search?") -> "search"
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
            word = word?.let { wordResultOf(it) },
            sound = word?.audio?.isNotEmpty()
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