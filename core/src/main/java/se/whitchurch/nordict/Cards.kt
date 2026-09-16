package se.whitchurch.nordict

import com.google.gson.Gson
import org.jsoup.Jsoup

/**
 * One card the user can create for a [Word]: a definition (which may be
 * merged with neighbouring definitions into a single card) or an idiom.
 *
 * `id` is stable within a [Cards.proposals] result so the UI can key
 * per-card images/examples by it. Collins definitions carry no definition
 * text (their content lives in `glosses`), so text content cannot key cards;
 * the index-based ids always can.
 */
sealed interface CardProposal {
    val id: String
    val title: String

    data class Definition(
        override val id: String,
        override val title: String,
        val definition: Word.Definition
    ) : CardProposal

    data class Idiom(
        override val id: String,
        override val title: String,
        val idiom: Word.Idiom
    ) : CardProposal
}

/**
 * Pure card-pipeline logic: enumerating the cards a [Word] proposes, building
 * each card's Back HTML, collecting the front examples, and assembling the
 * note fields. JSON-rendered dictionaries (DLE, EST, Collins, diccionari.cat,
 * Didac, and combined multi-dictionary words) get their Back rendered from the
 * definition fragments directly — there is no page skeleton to inject into
 * (combined words carry an empty `element`), and Collins' definitions are not
 * reached by any of [Word.getPage]'s container selectors. Legacy dictionaries
 * with a real page skeleton (DDO, SO) keep using [Word.getPage].
 */
object Cards {
    private val gson = Gson()

    /** The cards a word's card screen offers: one per definition, then one
     * per idiom, in page order. Combined multi-dictionary words stack every
     * selected dictionary's entry on one page (`mHomonymEntries` carries the
     * whole set while the word's own `definitions`/`idioms` only hold the
     * first entry), so their proposals flatten every entry, mirroring the
     * page; plain words propose the loaded word's own definitions and
     * idioms. */
    fun proposals(word: Word): List<CardProposal> {
        val entries = word.mHomonymEntries.takeIf {
            it.any { entry -> MultiDict.isCombinedRef(entry.ref) }
        }
        if (entries != null) return combinedProposals(word, entries)

        val out = ArrayList<CardProposal>()
        word.definitions.forEachIndexed { i, definition ->
            out.add(CardProposal.Definition("d$i", definition.title ?: word.mTitle, definition))
        }
        word.idioms.forEachIndexed { i, idiom ->
            out.add(CardProposal.Idiom("i$i", word.mTitle, idiom))
        }
        return out
    }

    private fun combinedProposals(word: Word, entries: List<Word.HomonymEntry>): List<CardProposal> {
        val out = ArrayList<CardProposal>()
        var d = 0
        for (entry in entries) {
            for (definition in entry.definitions) {
                out.add(CardProposal.Definition("d${d++}", definition.title ?: entry.mTitle, definition))
            }
        }
        var i = 0
        for (entry in entries) {
            for (idiom in entry.idioms) {
                out.add(CardProposal.Idiom("i${i++}", entry.mTitle, idiom))
            }
        }
        return out
    }

    /**
     * The Back field HTML for a definition card. For JSON-rendered words
     * ([Word.renderAsJson], including combined multi-dictionary words) the
     * chosen definitions' fragments are stacked directly, wrapped in the
     * extracted page CSS; legacy words keep the page-skeleton injection in
     * [Word.getPage] (headword header + pronunciation + definitions).
     */
    fun definitionBack(word: Word, defs: List<Word.Definition>, css: String?): String {
        if (word.renderAsJson) {
            val style = if (css.isNullOrBlank()) "" else "<style>$css</style>"
            return style + defs.joinToString(separator = "") { it.element.outerHtml() }
        }
        return word.getPage(defs, css)
    }

    /** The Back field HTML for an idiom card. */
    fun idiomBack(idiom: Word.Idiom): String {
        return "<strong>${idiom.idiom}</strong><p>${idiom.definition}"
    }

    /** The example sentences for an idiom card front: the idiom's examples,
     * or the idiom phrase itself so the front is never empty. */
    fun idiomExamples(idiom: Word.Idiom): List<String> {
        return if (idiom.examples.isEmpty()) listOf(idiom.idiom) else idiom.examples.toList()
    }

    /**
     * The example sentences shown on the card front: every chosen
     * definition's examples (from its glosses when it has them — Collins and
     * the RAE dictionaries keep examples there — else the legacy
     * definition-level list), plus any clipboard extras. Falls back to the
     * word's title so the front is never empty.
     */
    fun examples(word: Word, defs: List<Word.Definition>, extras: List<String>): List<String> {
        val out = ArrayList<String>()
        for (def in defs) {
            if (def.glosses.isNotEmpty()) {
                for (gloss in def.glosses) out.addAll(gloss.examples)
            } else {
                out.addAll(def.examples)
            }
        }
        out.addAll(extras)
        if (out.isEmpty()) {
            out.add(defs.mapNotNull { it.title }.firstOrNull() ?: word.mTitle)
        }
        return out
    }

    /**
     * The four note fields, in the Anki model's order: `Images` (a JSON array
     * of data URLs), `Sentences` (a JSON array of HTML), `Audio` (a single URL
     * or data URL) and `Back` (the page HTML, left-aligned like the in-app
     * word view).
     */
    fun fields(back: String, examples: List<String>, images: List<String>, audio: String): Array<String> {
        return arrayOf(
            gson.toJson(images),
            gson.toJson(examples),
            audio,
            "<div style=\"text-align: left\">$back</div>"
        )
    }

    /**
     * Plain text for the card preview: strips HTML (Collins gloss definitions
     * and examples are rich HTML) while leaving plain text untouched.
     */
    fun plainText(html: String): String = Jsoup.parseBodyFragment(html).text()

    /**
     * The definition line for the card preview. Most definitions carry their
     * text directly; Collins definitions carry none and keep the content in
     * their first gloss, so fall back to it.
     */
    fun definitionText(definition: Word.Definition): String {
        val raw = definition.definition.ifEmpty {
            definition.glosses.firstOrNull()?.definition.orEmpty()
        }
        return plainText(raw)
    }
}