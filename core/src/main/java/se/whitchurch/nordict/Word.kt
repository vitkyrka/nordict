package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import java.util.*

class Word(
    val dict: String, val mTitle: String, val mSlug: String, val summary: String,
    val uri: HttpUrl,
    val xrefs: ArrayList<String> = ArrayList<String>()
) {
    var dictionary: String = ""
    var pos: Pos = Pos.UNKNOWN
    var gender: String = ""
    var conjugation: String = ""
    var participle: String = ""
    var etymology: String = ""
    var pronunciation: String = ""

    // The searchable headword, which may differ from the displayed `mTitle`
    // (e.g. DLE/EST show "otro, tra" but other dictionaries need "otro";
    // Collins Easy Learning shows "la frente" but DLE/EST need "frente").
    // Populated by the JSON dictionaries (DLE, EST, COLSPAN); empty elsewhere.
    var rawHeadword: String = ""

    // What to pass to the search box / cross-dictionary lookup for this entry.
    val searchHeadword: String
        get() = rawHeadword.takeIf { it.isNotBlank() } ?: mTitle

    // Full renderable content of every entry (homograph, sub-entry, Collins
    // POS-group) on the page that produced this word, in page order and
    // including this word itself. The JSON dictionaries fill this; the
    // renderer draws all entries as a single page with in-page anchor
    // navigation between them.
    val mHomonymEntries: ArrayList<HomonymEntry> = ArrayList()
    val idioms: ArrayList<Idiom> = ArrayList()
    val definitions: ArrayList<Definition> = ArrayList()
    val audio: ArrayList<String> = ArrayList()
    val images: ArrayList<String> = ArrayList()

    // One numbered gloss (definition or idiom acep). Most aceps carry
    // a single gloss; secondary glosses come from RAE `.defP` markers
    // (e.g. "También prnl."), each owning its own grammar/gender/examples.
    class Gloss {
        var definition: String = ""
        var headword: String = ""
        var grammar: String = ""
        var gender: String = ""
        val examples: ArrayList<String> = ArrayList()

        // Collins idioms and phrases nested inside this sense (gloss).
        // The renderer shows these inline under the specific definition
        // they belong to, instead of at the end of the POS group.
        val idioms: ArrayList<Phrase> = ArrayList()
        val phrases: ArrayList<Phrase> = ArrayList()
    }

    class Idiom(val idiom: String, @Transient val definition: String) {
        val glosses: ArrayList<Gloss> = ArrayList()

        // Flattened view of the primary gloss, kept for in-memory consumers
        // (CardActivity). Excluded from the Word JSON; the renderer reads
        // `glosses`.
        @Transient val examples: ArrayList<String> = ArrayList()
        @Transient var grammar: String = ""
        @Transient var gender: String = ""
        var domain: String = ""
        var geo: String = ""
        var plev: String = ""
        var register: String = ""
        var senseNumber: String = ""
    }

    // One synonym (or antonym) in a definition footer. `href` carries the
    // source's link target so the renderer can link to the original entry
    // instead of relying on word.js auto-linking; `plev` is the DLE
    // `abbr.sin_alert` marker (e.g. "malsonante").
    class Synonym(val text: String, val href: String = "", val plev: String = "")

    // A Collins idiom or phrase attached to a definition. `headword` and
    // `translation` are rich-HTML strings; `examples` holds HTML examples.
    class Phrase(val headword: String, val translation: String = "") {
        val examples: ArrayList<String> = ArrayList()
    }

    class Definition(@Transient val definition: String, @Transient val element: Element, val title: String? = null) {
        val glosses: ArrayList<Gloss> = ArrayList()

        // Collins part-of-speech group label (e.g. "feminine noun",
        // "transitive verb"). Serialized as `pos`.
        var pos: String = ""
        val idioms: ArrayList<Phrase> = ArrayList()
        val phrases: ArrayList<Phrase> = ArrayList()

        @Transient val examples: ArrayList<String> = ArrayList()
        @Transient var grammar: String = ""
        @Transient var gender: String = ""
        var domain: String = ""
        var geo: String = ""
        var plev: String = ""
        var register: String = ""
        val synonyms: ArrayList<Synonym> = ArrayList()
        val antonyms: ArrayList<String> = ArrayList()
        var senseNumber: String = ""
    }

    // A flattened, serializable snapshot of a Word for the combined homonym
    // page: everything renderer.js needs (`mTitle`, morphology, etymology,
    // `dictionary` label, definitions, idioms) without the transient jsoup
    // elements or the raw page HTML carried by Word itself. `ref` is the
    // entry's `__ref` id and is used by the renderer to pick the current
    // entry (anchor `hom-N`) and by word.js to keep nav anchors out of the
    // auto-linking regex.
    class HomonymEntry(
        val mTitle: String,
        val ref: String,
        val dictionary: String = "",
        val conjugation: String = "",
        val participle: String = "",
        val etymology: String = "",
        val pronunciation: String = "",
        val definitions: ArrayList<Definition> = ArrayList(),
        val idioms: ArrayList<Idiom> = ArrayList(),
        val audio: ArrayList<String> = ArrayList()
    )

    companion object {
        // RAE (DLE/EST) headword titles abbreviate gendered/apocopated forms as
        // "base, ending" (e.g. "otro, tra", "macabro, bra"). The part before the
        // comma is the searchable key the autocomplete API and other dictionaries
        // expect (e.g. Collins keys "otro", "macabro").
        fun raeSearchKey(title: String): String = title.substringBefore(',').trim()

        fun toHomonymEntry(word: Word): HomonymEntry {
            return HomonymEntry(
                mTitle = word.mTitle,
                ref = word.xrefs.firstOrNull() ?: "",
                dictionary = word.dictionary,
                conjugation = word.conjugation,
                participle = word.participle,
                etymology = word.etymology,
                pronunciation = word.pronunciation,
                definitions = ArrayList(word.definitions),
                idioms = ArrayList(word.idioms),
                audio = ArrayList(word.audio)
            )
        }

        // Flatten a page's words into serializable homonym entries, in page
        // order and including the caller's word, so the renderer can draw the
        // whole set on one page.
        fun homonymEntries(words: List<Word>): ArrayList<HomonymEntry> {
            return ArrayList(words.map { toHomonymEntry(it) })
        }

        /**
         * Builds a JSON-rendered word that has no single source page of its own:
         * a combined multi-dictionary entry set. The top-level fields come from
         * [base] (the first dictionary that resolved), so a combined word that
         * totals a single entry renders as a plain article; `mHomonymEntries`
         * carries every dictionary's entries in selection order.
         */
        fun combined(
            base: Word,
            dictionaryLabel: String,
            entries: List<HomonymEntry>,
            headword: String,
            ref: String?
        ): Word {
            val word = Word(
                dict = base.dict,
                mTitle = base.mTitle,
                mSlug = base.mSlug,
                summary = base.summary,
                uri = base.uri,
                xrefs = arrayListOf(ref ?: entries.firstOrNull()?.ref ?: "")
            )
            word.dictionary = dictionaryLabel
            word.rawHeadword = headword
            word.pos = base.pos
            word.gender = base.gender
            word.conjugation = base.conjugation
            word.participle = base.participle
            word.etymology = base.etymology
            word.pronunciation = base.pronunciation
            word.idioms.addAll(base.idioms)
            word.definitions.addAll(base.definitions)
            word.audio.addAll(base.audio)
            if (word.audio.isEmpty()) {
                word.audio.addAll(entries.flatMap { it.audio }.distinct())
            }
            word.mHomonymEntries.addAll(entries)
            return word
        }

        /**
         * A fresh word presenting [entry] as the current entry of the combined
         * page [page]: all slices come from the entry except the full combined
         * entry set, so an in-memory `nextPage` selection swap keeps the whole
         * page (and its nav) intact while the headline reflects the selection.
         */
        fun withEntry(page: Word, entry: HomonymEntry, headword: String): Word {
            val word = Word(
                dict = page.dict,
                mTitle = entry.mTitle,
                mSlug = page.mSlug,
                summary = page.summary,
                uri = page.uri,
                xrefs = arrayListOf(entry.ref)
            )
            word.dictionary = entry.dictionary
            word.rawHeadword = headword
            word.pos = page.pos
            word.gender = page.gender
            word.conjugation = entry.conjugation
            word.participle = entry.participle
            word.etymology = entry.etymology
            word.pronunciation = entry.pronunciation
            word.idioms.addAll(entry.idioms)
            word.definitions.addAll(entry.definitions)
            word.audio.addAll(entry.audio)
            word.mHomonymEntries.addAll(page.mHomonymEntries)
            return word
        }
    }
}
