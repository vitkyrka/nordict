package se.whitchurch.nordict.cli

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import se.whitchurch.nordict.AgentCommand
import se.whitchurch.nordict.AgentOps
import se.whitchurch.nordict.AgentProtocol
import se.whitchurch.nordict.AgentResult
import se.whitchurch.nordict.AgentState
import se.whitchurch.nordict.ExactMatch
import se.whitchurch.nordict.SearchResult
import se.whitchurch.nordict.Word
import se.whitchurch.nordict.toSearchResultData
import se.whitchurch.nordict.wordResultOf
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.net.Socket

/** The RAE `/__ref` query parameter selecting one homograph/sub-entry. */
private const val REFPARAM = "__ref"

/** Fetches a URL over HTTP (the headless default; tests inject a fetcher). */
fun liveFetch(url: HttpUrl): String {
    val request = Request.Builder().url(url).build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} for $url")
        }
        return response.body?.string() ?: ""
    }
}

/** Something that answers one [AgentCommand] with one [AgentResult]. */
interface AgentBackend {
    fun execute(command: AgentCommand): AgentResult
}

/**
 * Drives a [Dict] session headlessly on the desktop: search resolves against
 * the dictionary's search endpoint, `open` navigates only on a unique exact
 * match (same rule as the app's cross-dictionary switch), and `/__ref` or the
 * URL's last path segment selects one word when a page yields homographs.
 * `fetch` is injectable so tests can back it with the `testdata/` fixtures
 * instead of the network.
 */
class HeadlessAgentDriver(
    private val dictionaries: List<Dict>,
    private val fetch: (HttpUrl) -> String = { url -> liveFetch(url) }
) : AgentBackend {

    private var active: Dict = dictionaries.first()
    private var currentPage: List<Word> = emptyList()
    private var selectedIdx: Int = 0
    private var loadedWord: Word? = null
    private var lastQuery: String? = null
    private var activity: String = "MainActivity"

    override fun execute(command: AgentCommand): AgentResult {
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
        lastQuery = query
        activity = "MainActivity"
        val results = searchResults(query)
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
        val results = searchResults(query)
        lastQuery = query

        val exact = ExactMatch.resolve(query, results)
        if (exact == null) {
            val suggestions = results.map { it.mTitle }.distinct()
            return AgentResult.error(
                AgentOps.OPEN,
                "no unique exact match for '$query'" +
                    if (suggestions.isEmpty()) "" else " (run search to pick from: ${suggestions.joinToString(", ")})"
            )
        }
        return openAt(exact.uri, query, AgentOps.OPEN)
    }

    private fun opOpenUri(command: AgentCommand): AgentResult {
        val raw = command.require("uri", command.uri)
        val uri = raw.toHttpUrlOrNull()
            ?: return AgentResult.error(AgentOps.OPEN_URI, "invalid uri '$raw'")

        lastQuery = null
        return openAt(uri, null, AgentOps.OPEN_URI)
    }

    private fun openAt(uri: HttpUrl, query: String?, op: String): AgentResult {
        val page = fetch(uri)
        val words = active.parse(page, uri)
        if (words.isEmpty()) {
            return AgentResult.error(op, "no words parsed from $uri (${active.tag})")
        }

        val selected = selectWord(words, uri)
        currentPage = words
        selectedIdx = words.indexOf(selected).takeIf { it >= 0 } ?: 0
        loadedWord = selected
        lastQuery = query
        activity = "WordActivity"
        return okPayload(op, snapshot())
    }

    private fun selectWord(words: List<Word>, uri: HttpUrl): Word {
        val ref = uri.queryParameter(REFPARAM)
        if (ref != null) {
            words.firstOrNull { ref in it.xrefs }?.let { return it }
            return words[0]
        }
        val wanted = uri.pathSegments.lastOrNull()
        if (wanted != null) {
            words.firstOrNull { it.mSlug == wanted || it.mTitle == wanted }?.let { return it }
        }
        return words[0]
    }

    private fun opNextPage(command: AgentCommand): AgentResult {
        if (currentPage.isEmpty()) {
            return AgentResult.error(AgentOps.NEXT_PAGE, "no word loaded — open a word first")
        }
        if (selectedIdx >= currentPage.size - 1) {
            val state = snapshot()
            return AgentResult(
                ok = true,
                op = AgentOps.NEXT_PAGE,
                message = "already at last entry (${selectedIdx + 1} of ${currentPage.size})",
                state = state,
                word = state.word
            )
        }
        selectedIdx++
        loadedWord = currentPage[selectedIdx]
        return okPayload(AgentOps.NEXT_PAGE, snapshot())
    }

    private fun opBack(command: AgentCommand): AgentResult {
        currentPage = emptyList()
        loadedWord = null
        lastQuery = null
        activity = "MainActivity"
        return AgentResult(
            ok = true,
            op = AgentOps.BACK,
            message = "back: no word loaded",
            state = snapshot()
        )
    }

    private fun opSetDict(command: AgentCommand): AgentResult {
        val tagOrAlias = command.require("tag", command.tag)
        val dict = dictionaries.firstOrNull { it.tag == tagOrAlias || tagOrAlias in it.aliases }
            ?: return AgentResult.error(
                AgentOps.SET_DICT,
                "unknown dictionary '$tagOrAlias' — known: ${dictionaries.joinToString(", ") { it.aliases.joinToString("/") + " (" + it.tag + ")" }}"
            )
        active = dict
        clearLoaded()
        return AgentResult(
            ok = true,
            op = AgentOps.SET_DICT,
            message = "dictionary is now ${dict.tag}",
            state = snapshot()
        )
    }

    private fun opSetLang(command: AgentCommand): AgentResult {
        val lang = command.require("lang", command.lang)
        val dict = dictionaries.firstOrNull { it.lang == lang }
            ?: return AgentResult.error(
                AgentOps.SET_LANG,
                "unknown language '$lang' — known: ${dictionaries.map { it.lang }.distinct().joinToString(", ")}"
            )
        active = dict
        clearLoaded()
        return AgentResult(
            ok = true,
            op = AgentOps.SET_LANG,
            message = "language is now ${dict.lang} (${dict.tag})",
            state = snapshot()
        )
    }

    private fun clearLoaded() {
        currentPage = emptyList()
        loadedWord = null
        selectedIdx = 0
        lastQuery = null
        activity = "MainActivity"
    }

    private fun searchResults(query: String): List<SearchResult> =
        active.searchResults(fetch(active.searchUrl(query)))

    private fun snapshot(): AgentState =
        AgentState(
            activity = activity,
            dict = active.tag,
            lang = active.lang,
            query = lastQuery,
            word = loadedWord?.let { wordResultOf(it, currentPage) }
        )

    private fun okPayload(op: String, state: AgentState): AgentResult =
        AgentResult(ok = true, op = op, state = state, word = state.word)
}

/**
 * Line-framed repl: reads one JSON [AgentCommand] per line from [input] (stdin),
 * writes exactly one JSON [AgentResult] per command to [output] (stdout), in
 * order. `quit` closes the session. Malformed lines produce an error result and
 * the session continues with the next line.
 */
class Repl(private val backend: AgentBackend) {
    fun run(input: InputStream, output: PrintStream): Int {
        val gson = AgentProtocol.gson
        val reader = BufferedReader(InputStreamReader(input))
        var failed = false

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            val result = try {
                val command = gson.fromJson(line, AgentCommand::class.java)
                if (command.op == AgentOps.QUIT) {
                    AgentResult(ok = true, op = AgentOps.QUIT, message = "bye")
                } else {
                    backend.execute(command)
                }
            } catch (e: Exception) {
                failed = true
                AgentResult.error(null, "protocol error: ${e.message ?: e}")
            }

            output.println(gson.toJson(result))
            output.flush()

            if (result.op == AgentOps.QUIT) break
        }
        return if (failed) 1 else 0
    }
}

/**
 * Remote backend: a `:cli repl` on the host talks to the debug app over
 * `adb forward` (a host-side listener on `[AgentProtocol.PORT]` forwards to
 * the app's loopback `AgentProtocol.PORT` on the device). Commands are
 * forwarded as line JSON over one socket connection; if the app dies the next
 * command re-runs the forward and reconnects.
 */
class DeviceAgentBackend(private val serial: String) : AgentBackend {
    private val gson = AgentProtocol.gson
    private var socket: Socket? = null

    /** Sets up `adb forward` and launches the app on the device. */
    fun activate() {
        adb("forward", "tcp:${AgentProtocol.PORT}", "tcp:${AgentProtocol.PORT}")
        adb("shell", "am", "start", "-n", "se.whitchurch.nordict/.MainActivity")
    }

    override fun execute(command: AgentCommand): AgentResult {
        // A cold app takes a moment to bind its loopback server, so retry
        // connection-level failures a few times with a short pause.
        var lastErr: Exception? = null
        for (attempt in 0 until MAX_CONN_RETRIES) {
            try {
                ensureConnected()
                val to = socket!!.getOutputStream()
                to.write((gson.toJson(command) + "\n").toByteArray(Charsets.UTF_8))
                to.flush()
                val line = socket!!.getInputStream().bufferedReader().readLine()
                    ?: throw IOException("device closed the agent connection")
                return gson.fromJson(line, AgentResult::class.java)
            } catch (e: Exception) {
                lastErr = e
                close()
                if (attempt < MAX_CONN_RETRIES - 1) Thread.sleep(2_000)
            }
        }
        return AgentResult.error(command.op, "device error: ${lastErr?.message ?: lastErr}")
    }

    private fun ensureConnected() {
        if (socket == null || socket!!.isClosed) {
            socket = Socket("127.0.0.1", AgentProtocol.PORT).apply {
                soTimeout = 120_000
            }
        }
    }

    private fun close() {
        socket?.close()
        socket = null
    }

    private fun adb(vararg args: String) {
        val command = listOf("adb", "-s", serial) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) {
            throw IOException("adb ${args.joinToString(" ")} exited ${code}: $out")
        }
    }

    companion object {
        private const val MAX_CONN_RETRIES = 6
    }
}