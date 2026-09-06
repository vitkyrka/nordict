package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.nodes.Element
import java.util.*

class Word(
    val dict: String, val mTitle: String, val mSlug: String, val summary: String,
    val mText: String, internal val uri: Uri,
    internal val baseUrl: String,
    @Transient val element: Element,
    val header: String, @Transient val lemma: Element? = null,
    val xrefs: ArrayList<String> = ArrayList<String>(),
    val renderAsJson: Boolean = false
) {
    var pos: Pos = Pos.UNKNOWN
    var gender: String = ""
    var conjugation: String = ""
    var participle: String = ""
    val mHomographs: ArrayList<SearchResult>
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
    }

    class Idiom(val idiom: String, @Transient val definition: String) {
        val glosses: ArrayList<Gloss> = ArrayList()

        // Flattened view of the primary gloss, kept for legacy in-memory
        // consumers (CardActivity, non-JSON dictionaries). Excluded from
        // the Word JSON; the renderer reads `glosses`.
        @Transient val examples: ArrayList<String> = ArrayList()
        @Transient var grammar: String = ""
        @Transient var gender: String = ""
        var geo: String = ""
        var plev: String = ""
        var register: String = ""
    }

    class Definition(@Transient val definition: String, @Transient val element: Element, val title: String? = null) {
        val glosses: ArrayList<Gloss> = ArrayList()

        @Transient val examples: ArrayList<String> = ArrayList()
        @Transient var grammar: String = ""
        @Transient var gender: String = ""
        var domain: String = ""
        var geo: String = ""
        var plev: String = ""
        var register: String = ""
        val synonyms: ArrayList<String> = ArrayList()
    }

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
}