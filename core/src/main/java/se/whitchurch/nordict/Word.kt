package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.nodes.Element
import java.util.*

class Word(
    val dict: String, val mTitle: String, val mSlug: String, val summary: String,
    val mText: String, val uri: HttpUrl,
    val baseUrl: String,
    @Transient val element: Element,
    val header: String, @Transient val lemma: Element? = null,
    val xrefs: ArrayList<String> = ArrayList<String>(),
    val renderAsJson: Boolean = false
) {
    var dictionary: String = ""
    var pos: Pos = Pos.UNKNOWN
    var gender: String = ""
    var conjugation: String = ""
    var participle: String = ""
    var etymology: String = ""

    // The searchable headword, which may differ from the displayed `mTitle`
    // (e.g. DLE/EST show "otro, tra" but other dictionaries need "otro";
    // Collins Easy Learning shows "la frente" but DLE/EST need "frente").
    // Populated by the JSON dictionaries (DLE, EST, COLSPAN); empty elsewhere.
    var rawHeadword: String = ""

    // What to pass to the search box / cross-dictionary lookup for this entry.
    val searchHeadword: String
        get() = rawHeadword.takeIf { it.isNotBlank() } ?: mTitle

    val mHomographs: ArrayList<SearchResult>

    // Full renderable content of every entry (homograph, sub-entry, Collins
    // POS-group) on the page that produced this word, in page order and
    // including this word itself. Only the JSON-rendered dictionaries (EST,
    // DLE, COLSPAN) fill this; the renderer draws all entries as a single
    // page with in-page anchor navigation between them. Empty elsewhere, so
    // legacy dictionaries are unaffected.
    val mHomonymEntries: ArrayList<HomonymEntry> = ArrayList()
    val mHasAudio: Boolean
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

        // Flattened view of the primary gloss, kept for legacy in-memory
        // consumers (CardActivity, non-JSON dictionaries). Excluded from
        // the Word JSON; the renderer reads `glosses`.
        @Transient val examples: ArrayList<String> = ArrayList()
        @Transient var grammar: String = ""
        @Transient var gender: String = ""
        var domain: String = ""
        var geo: String = ""
        var plev: String = ""
        var register: String = ""
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
        val definitions: ArrayList<Definition> = ArrayList(),
        val idioms: ArrayList<Idiom> = ArrayList(),
        val audio: ArrayList<String> = ArrayList()
    )

    fun getPage(chosenDefs: List<Definition>? = null, css: String? = null): String {
        val doc = element.clone()

        lemma?.let {
            if (doc.selectFirst(".mw-parser-output") != null) {
                doc.selectFirst(".mw-parser-output")?.appendChild(it.clone())
            } else if (doc.selectFirst("section.def") != null) {
                doc.selectFirst("section.def")?.appendChild(it.clone())
            } else if (doc.selectFirst("div.dol-col-60") != null) {
                // Infopedia
                doc.selectFirst("div.dol-col-60")?.appendChild(it.clone())
            } else if (doc.selectFirst("#resultados") != null) {
                // DLE
                doc.selectFirst("#resultados")?.appendChild(it.clone())
            } else {
                doc.select(".artikel").first().appendChild(it.clone())
            }
        }

        var last = doc.selectFirst(".uttalblock")
        if (last == null) {
            last = doc.selectFirst(".bojning")
        }

        val defs = chosenDefs ?: definitions
        for (def in defs) {
            val el = def.element.clone()

            if (last != null) {
                last.after(el)
                last = el
            } else {
                var parent = doc.selectFirst("#content-betydninger")
                if (parent == null) parent = doc.selectFirst("div.d_ptma")
                if (parent == null) parent = doc.selectFirst(".artikel")
                if (parent == null) parent = doc.selectFirst("ol")
                // Linguee
                if (parent == null) parent = doc.selectFirst("div.exact")
                // Infopedia
                if (parent == null) parent = doc.selectFirst("div.dolCatgramAceps")
                // DLE
                if (parent == null) parent = doc.selectFirst("ol.c-definitions")
                if (parent == null) parent = doc.selectFirst("article")
                parent?.appendChild(el)
                last = el
            }
        }

        if (css != null) {
            return "<style>$css</style>${doc.outerHtml()}"
        } else {
            return header + doc.outerHtml()
        }
    }

    init {
        mHomographs = ArrayList()

        // Audio is marked by an object data with an asset number we apparently can't
        // do anything with. To actually get the audio, we need to screen scrape the
        // full site.
        mHasAudio = false
    }// Try to get the canonical URL to prevent duplicates in history

    fun addHomograph(homograph: SearchResult) {
        mHomographs.add(homograph)
    }

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
    }
}
