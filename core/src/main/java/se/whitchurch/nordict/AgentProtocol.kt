package se.whitchurch.nordict

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Wire protocol for the agent REPL: a small, stable set of semantic commands an
 * AI agent can send to inspect and drive the app. One JSON schema is used by
 * the headless desktop session (`:cli repl`) and by the on-device remote
 * session (the debug-only agent server), so a command means the same thing in
 * both worlds.
 *
 * Framing: one compact JSON object per line, no embedded newlines. The agent
 * writes an [AgentCommand] and reads exactly one [AgentResult] per command, in
 * order — on stdout in headless mode, over the same socket connection in
 * device mode. All responses are JSON on the data channel; stderr/logs are
 * reserved for diagnostics.
 */
object AgentProtocol {
    /** Loopback port the debug app binds (host reaches it via `adb reverse`). */
    const val PORT = 42837

    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
}

/** Semantic operation names (the `op` field of an [AgentCommand]). */
object AgentOps {
    const val SEARCH = "search"
    const val RUN_SEARCH = "runSearch"
    const val OPEN = "open"
    const val OPEN_URI = "openUri"
    const val NEXT_PAGE = "nextPage"
    const val BACK = "back"
    const val OPEN_CARDS = "openCards"
    const val CREATE_CARD = "createCard"
    const val PREVIEW_CARD = "previewCard"
    const val AUDIO = "audio"
    const val SET_DICT = "setDict"
    const val SET_LANG = "setLang"
    const val SWAP_LANG = "swapLang"
    const val STATE = "state"
    const val QUIT = "quit"
}

/**
 * One agent command. `op` selects the operation; the remaining fields are the
 * arguments for it (see [AgentOps]). Unknown/extra fields are ignored.
 */
data class AgentCommand(
    val op: String,
    val query: String? = null,
    val uri: String? = null,
    val title: String? = null,
    // A pronunciation URL to play with `audio` (overrides the loaded word's
    // own audio list, e.g. for the search-first dictionaries whose headword
    // page — not the word — carries the speaker links).
    val url: String? = null,
    val tag: String? = null,
    val lang: String? = null,
    // A multi-dictionary selection for `setDict` (ordered). When present it
    // wins over `tag`; a comma-separated `tag` ("DLE,EST") also works.
    val tags: List<String>? = null,
    // The card proposal to create/preview with `createCard`/`previewCard`
    // (a zero-based index into `Cards.proposals`: definitions first, then
    // idioms, in page order).
    val index: Int? = null
) {
    /** Returns the named argument or throws a clear protocol error. */
    fun require(name: String, value: String?): String =
        value ?: throw IllegalArgumentException("command '$op' requires an argument '$name'")

    /** The ordered dictionary selection a `setDict` names. */
    fun selectionTags(): List<String> =
        when {
            tags != null && tags.isNotEmpty() -> tags
            tag != null -> tag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
}

/**
 * Exactly one of these is sent back per [AgentCommand]. `ok=false` commands
 * carry an [error] message; `ok=true` commands carry the operation's payload
 * (search [results], a loaded [word]) plus a [state] snapshot.
 */
data class AgentResult(
    val ok: Boolean,
    val op: String? = null,
    val error: String? = null,
    val message: String? = null,
    val state: AgentState? = null,
    val results: List<WordJson.SearchResultData>? = null,
    val word: WordResult? = null,
    val preview: CardPreviewData? = null
) {
    companion object {
        fun error(op: String?, error: String): AgentResult =
            AgentResult(ok = false, op = op, error = error)
    }
}

/**
 * The front/back preview of one card proposal, as rendered by the card
 * screen's Preview button ([Cards.preview]): the Front fragment and the exact
 * Anki `Back` note field, so card layout can be inspected without opening
 * Anki. Carries no images/audio payloads (those are base64 data URLs on the
 * card screen), only the rendered HTML.
 */
data class CardPreviewData(
    val frontHtml: String,
    val backField: String
)

/** A structured snapshot of what the agent is looking at. */
data class AgentState(
    val activity: String = "",
    val lang: String = "",
    val dict: String = "",
    // The active multi-dictionary selection (empty for single-dict). When set,
    // `dict` is its comma-joined rendering.
    val dicts: List<String>? = null,
    val query: String? = null,
    val word: WordResult? = null,
    // The word bar's pronunciation control: true when the loaded word carries
    // playable audio (the play button is enabled), false when it has none (the
    // button is disabled), and null when no word is loaded.
    val sound: Boolean? = null
)

/** One entry of a multi-entry (homograph) page, in page order. */
data class HomonymData(
    val mTitle: String,
    val ref: String,
    val dictionary: String = ""
)

/** A loaded word: the golden word JSON plus the homonym navigation row. */
data class WordResult(
    val word: WordJson.WordData,
    val homonyms: List<HomonymData>,
    val selected: Int
)

/**
 * The homonym (page-entry) list for a loaded word, in page order. JSON-rendered
 * dictionaries (EST/DLE/COLSPAN) snapshot every entry in `mHomonymEntries`;
 * legacy dictionaries report just the word itself.
 */
fun homonymListOf(word: Word): List<HomonymData> =
    if (word.mHomonymEntries.isNotEmpty()) {
        word.mHomonymEntries.map { HomonymData(it.mTitle, it.ref, it.dictionary) }
    } else {
        listOf(HomonymData(word.mTitle, word.xrefs.firstOrNull() ?: "", word.dictionary))
    }

/**
 * A [WordResult] describing [word] as shown among a whole page of entries
 * (the homograph set). `selected` is the index of [word] within the homonym
 * list; when [word] is not among them it falls back to index 0.
 */
fun wordResultOf(word: Word, pageWords: List<Word> = emptyList()): WordResult {
    val homonyms: List<HomonymData> =
        if (pageWords.isNotEmpty()) {
            pageWords.map { HomonymData(it.mTitle, it.xrefs.firstOrNull() ?: "", it.dictionary) }
        } else {
            homonymListOf(word)
        }
    val ref = word.xrefs.firstOrNull() ?: ""
    val selected = homonyms.indexOfFirst { it.ref == ref && it.mTitle == word.mTitle }
    return WordResult(word.toWordData(), homonyms, if (selected >= 0) selected else 0)
}