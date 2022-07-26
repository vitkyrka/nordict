package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.nodes.Element
import java.util.*

class Word(
    val dict: String, val mTitle: String, val mSlug: String, val summary: String,
    val mText: String, internal val uri: Uri,
    internal val baseUrl: String,
    val element: Element,
    val header: String, val lemma: Element? = null,
    val xrefs: ArrayList<String> = ArrayList<String>()
) {
    var pos: Pos = Pos.UNKNOWN
    var gender: String = ""
    val mHomographs: ArrayList<SearchResult>
    val mHasAudio: Boolean
    val idioms: ArrayList<Idiom> = ArrayList()
    val definitions: ArrayList<Definition> = ArrayList()
    val audio: ArrayList<String> = ArrayList()
    val images: ArrayList<String> = ArrayList()

    class Idiom(val idiom: String, val definition: String) {
        val examples: ArrayList<String> = ArrayList()
    }

    class Definition(val definition: String, val element: Element) {
        val examples: ArrayList<String> = ArrayList()
    }

    fun getPage(chosenDefs: List<Definition>? = null, css: String? = null): String {
        val doc = element.clone()

        lemma?.let {
            if (doc.selectFirst(".mw-parser-output") != null) {
                doc.selectFirst(".mw-parser-output")?.appendChild(it.clone())
            } else if (doc.selectFirst("section.def") != null) {
                doc.selectFirst("section.def")?.appendChild(it.clone())
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
                parent.appendChild(el)
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