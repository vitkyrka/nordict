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
 * Didac, SO, LeRobert, Linguee, Infopedia, Wiktionary, DDO/SDO, and combined
 * multi-dictionary words) get their Back rendered from the definition
 * fragments directly — there is no page skeleton to inject into (combined
 * words carry an empty `element`), and Collins' definitions are not reached
 * by any skeleton selector.
 */
object Cards {
    private val gson = Gson()

    /** The cards a word's card screen offers: one per definition, then one
     * per idiom, in page order. Collins POS-group definitions carry no
     * top-level text and keep each sense in `glosses`, so those split into
     * one card per gloss (e.g. frente masculine noun offers its 6 senses,
     * not one combined card), like the other dictionaries. Combined
     * multi-dictionary words stack every selected dictionary's entry on one
     * page (`mHomonymEntries` carries the whole set while the word's own
     * `definitions`/`idioms` only hold the first entry), so their proposals
     * flatten every entry, mirroring the page; plain words propose the
     * loaded word's own definitions and idioms. */
    fun proposals(word: Word): List<CardProposal> {
        val entries = word.mHomonymEntries.takeIf {
            it.any { entry -> MultiDict.isCombinedRef(entry.ref) }
        }
        if (entries != null) return combinedProposals(word, entries)

        val out = ArrayList<CardProposal>()
        var d = 0
        for (definition in word.definitions) {
            for (split in splitDefinition(definition)) {
                out.add(CardProposal.Definition("d${d++}", split.title ?: word.mTitle, split))
            }
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
                for (split in splitDefinition(definition)) {
                    out.add(CardProposal.Definition("d${d++}", split.title ?: entry.mTitle, split))
                }
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
     * Splits a Collins POS-group definition (empty top-level text, one gloss
     * per sense) into one single-gloss definition per card. Each copy keeps
     * the group's `pos`/markers and renders its own sense fragment, so its
     * Back, examples and preview cover just that sense. Any other definition
     * (including RAE secondary `.defP` glosses like "También prnl.") stays
     * whole.
     */
    private fun splitDefinition(definition: Word.Definition): List<Word.Definition> {
        if (definition.definition.isNotEmpty() || definition.glosses.size <= 1) {
            return listOf(definition)
        }
        return definition.glosses.map { gloss ->
            val element = gloss.element?.clone() ?: definition.element
            val single = Word.Definition(definition.definition, element, definition.title)
            single.pos = definition.pos
            single.grammar = definition.grammar
            single.gender = definition.gender
            single.domain = definition.domain
            single.geo = definition.geo
            single.plev = definition.plev
            single.register = definition.register
            single.senseNumber = definition.senseNumber
            single.synonyms.addAll(definition.synonyms)
            single.antonyms.addAll(definition.antonyms)
            single.idioms.addAll(definition.idioms)
            single.phrases.addAll(definition.phrases)
            single.examples.addAll(definition.examples)
            single.glosses.add(gloss)
            single
        }
    }

    /**
     * The Back field HTML for a definition card: the chosen definitions'
     * fragments stacked directly, wrapped in the extracted page CSS.
     */
    fun definitionBack(word: Word, defs: List<Word.Definition>, css: String?): String {
        val style = if (css.isNullOrBlank()) "" else "<style>$css</style>"
        return style + defs.joinToString(separator = "") { it.element.outerHtml() }
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
     * the RAE dictionaries keep examples there — else the definition-level
     * list), plus any clipboard extras. Falls back to the
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