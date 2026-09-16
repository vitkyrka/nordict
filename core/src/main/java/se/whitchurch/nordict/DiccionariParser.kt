package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Parser for the Enciclopedia Catalana "diccionari.cat" platform. Three
 * dictionaries share one page shape on this site and are handled here:
 * the Gran Diccionari de la Llengua Catalana (GDLC, monolingual) and the
 * bilingual catala-castella (CA-ES) and catala-angles (CA-EN) releases of
 * the same data. The DIDAC student dictionary shares the site but has its
 * own parser (DidacParser).
 *
 * Two page shapes flow through [parse], distinguished by Drupal's view mode:
 *  - a single entry URL (`/GDLC/cap1`, `/catala-castella/cap1`; what the
 *    app's `get(uri)` fetches) is the "full" view: one
 *    `<article class="node--type-*">` whose headword sits in the page's
 *    single `<h1>` (with `<sup class="homograph">` markers);
 *  - the search view (`/cerca/<dict>?search_api_fulltext_cust=<word>`, what
 *    `fullSearch` fetches) embeds every matching entry inline, each carrying
 *    its own `about="/GDLC/cap1"` URL and an `h2.node__title` whose text
 *    encodes `<title type="display">cap</title><lbl type="homograph">1</lbl>`
 *    marker markup.
 * Both are handled identically: iterate the articles, resolve each word's
 * canonical page URL from its `about` attribute, and pick the per-article
 * title from whichever heading shape is present.
 *
 * Definitions live inside `<ol class="dict">`. A `<span class="grammar">`
 * labels the `<li>` items that follow it, either as a direct child of the
 * `<ol>` (ca-es/ca-en switch groups) or inside a wrapper `<li>` (GDLC sense
 * groups, which nest a second `<ol class="dict">`). Within an entry:
 *  - a `<li>` whose content opens with a bolded `<b>` fragment is a locution
 *    (`Word.Idiom`); the rest of the `<li>` is its gloss;
 *  - otherwise it is a `Word.Definition` whose gloss is the running text;
 *  - `.dom` becomes `domain`, `.register` becomes `register`, and `.hint`
 *    stays inline in the copied markup;
 *  - monolingual GDLC extracts sentence-length `<i>` blocks as usage
 *    examples; the bilingual CA-ES/CA-EN dictionaries pair an `<i>` usage
 *    example with the target-language translation that follows it, while
 *    short `<i>` markers (`m`, `f`, `sing`, `o`) stay in the running copy.
 */
class DiccionariParser {
    companion object {

        // The autocomplete label carries the entry title either in a
        // `.field--name-field-display-title` div (ca-es, ca-en) or, for GDLC,
        // in the `.field--name-field-lemma-` field (`<orth>`).
        //
        // Two item shapes flow through the payload:
        //  - indexed entries carry their `url` (e.g. "/catala-castella/taula")
        //    and a title extracted from the `label` article;
        //  - prefix-completion suggestions (what turns "rebutja" into the
        //    "rebutjar" completion) carry no `url`; the completed word is the
        //    item `value`. Its destination is this dictionary's entry URL
        //    space (`/<entryPath>/<slugified value>`), which `uriOf` resolves.
        //    Items that merely echo the query back (no suffix span) are skipped.
        fun parseSearch(
            body: String,
            entryPath: String,
            uriOf: (path: String) -> HttpUrl
        ): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val array = JsonParser.parseString(body)
                if (!array.isJsonArray) return results
                array.asJsonArray.forEach { element ->
                    if (!element.isJsonObject) return@forEach
                    val obj = element.asJsonObject
                    val label = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: return@forEach
                    val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                    if (url.isNotEmpty()) {
                        val title = titleFromLabel(label)
                        if (title.isEmpty()) return@forEach
                        results.add(SearchResult(title, uriOf(url)))
                    } else {
                        val value = normalize(obj.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: "")
                        if (value.isEmpty()) return@forEach
                        // A suggestion with no suffix span is just the query
                        // echoed back (e.g. the DIDAC "cap" trailer), not a
                        // completion; it has no reliable destination.
                        if (Jsoup.parse(label).selectFirst(".autocomplete-suggestion-suggestion-suffix") == null) {
                            return@forEach
                        }
                        if (entryPath.isEmpty()) return@forEach
                        results.add(SearchResult(value, uriOf("/$entryPath/${slug(value)}")))
                    }
                }
            } catch (_: Exception) {
            }
            return results
        }

        private fun titleFromLabel(label: String): String {
            val article = Jsoup.parse(label)
            return cleanTitle(article.selectFirst(".field--name-field-display-title"))
                .ifEmpty { cleanTitle(article.selectFirst(".field--name-field-lemma-")) }
        }

        // diccionari.cat entry slugs drop punctuation and diacritics ("Cap
        // Verd" becomes "cap-verd", "taülalla" becomes "taulalla"), the same
        // normalization the app applies when resolving a word URI.
        private fun slug(s: String): String {
            val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            return nfd.replace(Regex("""[\p{Mn}]"""), "").lowercase()
                .replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        }

        /**
         * @param nodeClass the Drupal node type, e.g. "diccionari-gdlc"
         *   (`article.node--type-diccionari-gdlc`) for GDLC and
         *   "diccionari-ca-es"/"diccionari-ca-en" for the bilingual pair.
         * @param bilingual CA-ES/CA-EN pair `<i>` examples with the
         *   target-language translation that follows them.
         */
        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String,
            nodeClass: String,
            bilingual: Boolean,
            baseUrl: String = "https://www.diccionari.cat"
        ): List<Word> {
            val doc = Jsoup.parse(page)
            val articles = doc.select("article.node--type-$nodeClass")
            if (articles.isEmpty()) return emptyList()

            // Full-view pages carry the one headword in the page's single h1;
            // search-view pages have no page-level heading to consult.
            val h1Heading = doc.selectFirst("h1")

            val words = ArrayList<Word>()
            articles.forEachIndexed { _, article ->
                val about = article.attr("about")
                val wordUri = if (about.isNotEmpty()) {
                    uri.resolve(about) ?: uri
                } else {
                    uri
                }

                val title = articleTitle(article, h1Heading)
                if (title.isEmpty()) return@forEachIndexed

                val headword = Word(
                    tag, title, title, title, wordUri
                )
                headword.rawHeadword = title
                headword.xrefs.add((words.size + 1).toString())

                headword.pronunciation = pronunciationOf(article)

                val body = article.selectFirst(".field--name-body .div1")
                if (body != null) {
                    val ol = body.selectFirst("ol.dict")
                    if (ol != null) {
                        parseOl(ol, headword, bilingual, "", "", "")
                    } else {
                        parseFlat(body, headword, bilingual)
                    }
                }

                words.add(headword)
            }

            attachHomographs(words)

            return words
        }

        private fun attachHomographs(words: List<Word>) {
            if (words.size < 2) return
            val entries = Word.homonymEntries(words)
            for (word in words) {
                word.mHomonymEntries.addAll(entries)
            }
        }

        // ---- title extraction ----

        private val TITLE_TAG_RE = Regex("""<title[^>]*>([^<]*)</title>""")

        // Title of one article: the `h2.node__title` in the search view
        // encodes `<title type="display">cap</title><lbl type="homograph">1</lbl>`
        // as literal text; full-view pages expose the headword as the page's
        // single `<h1>` (with `<sup class="homograph">` markers).
        private fun articleTitle(article: Element, h1: Element?): String {
            val h2 = article.selectFirst("h2.node__title")
            if (h2 != null) {
                val text = h2.text()
                val m = TITLE_TAG_RE.find(text)
                if (m != null) {
                    val title = normalize(m.groupValues[1])
                    if (title.isNotEmpty()) return title
                }
                // Some entries carry the plain headword with no marker markup.
                val plain = normalize(text)
                if (plain.isNotEmpty() && !plain.startsWith("<")) return plain
            }
            if (h1 != null) {
                val clone = h1.clone()
                clone.select("sup.homograph").remove()
                clone.select(".ptr").remove()
                val text = normalize(clone.text())
                if (text.isNotEmpty()) return text
            }
            return ""
        }

        // The article-level accessory field carries the pronunciation on the
        // bilingual CA-EN pages (`<span class="accessory_heading">Pronúncia:
        // </span>káp`). Other releases use the accessory field for Homòfon /
        // Etimologia / Partició sil·làbica markers, never a pronunciation.
        private fun pronunciationOf(article: Element): String {
            val accessory = article.selectFirst(".field--name-field-accessory") ?: return ""
            val heading = accessory.select(".accessory_heading").firstOrNull {
                it.text().trim().startsWith("Pronúncia")
            } ?: return ""
            val parent = heading.parent() ?: return ""
            val clone = parent.clone()
            clone.select(".accessory_heading").forEach { it.remove() }
            return normalize(clone.text())
        }

        // ---- <ol class="dict"> walker ----

        private fun parseOl(
            ol: Element,
            headword: Word,
            bilingual: Boolean,
            inheritedGrammar: String,
            inheritedRegister: String,
            inheritedDomain: String,
            sensePrefix: String = ""
        ) {
            var pendingGrammar = inheritedGrammar
            var pendingRegister = inheritedRegister
            var pendingDomain = inheritedDomain
            var itemNumber = 0
            for (child in ol.children()) {
                when {
                    child.hasClass("grammar") -> pendingGrammar = normalize(child.text())
                    child.hasClass("register") -> pendingRegister = normalize(child.text())
                    child.hasClass("dom") -> pendingDomain = normalize(child.text())
                    child.tagName() == "li" -> {
                        itemNumber++
                        parseLi(
                            child, headword, bilingual, pendingGrammar, pendingRegister, pendingDomain,
                            sensePrefix, itemNumber
                        )
                    }
                }
            }
        }

        private fun parseLi(
            li: Element,
            headword: Word,
            bilingual: Boolean,
            olGrammar: String,
            olRegister: String,
            olDomain: String,
            sensePrefix: String = "",
            itemNumber: Int = 0
        ) {
            val senseNumber = if (sensePrefix.isEmpty()) "$itemNumber" else "$sensePrefix.$itemNumber"
            val clone = li.clone()
            stripAccessory(clone)

            val ownGrammar = clone.children().firstOrNull { it.hasClass("grammar") }
            val grammar = if (ownGrammar != null) normalize(ownGrammar.text()) else olGrammar
            val register = markersText(clone, "register").ifEmpty { olRegister }
            val domain = markersText(clone, "dom").ifEmpty { olDomain }

            val nested = clone.selectFirst("ol.dict")
            if (nested != null) {
                if (opensWithBold(clone)) {
                    parseLocutionWrapper(clone, headword, bilingual, grammar, register, domain, senseNumber)
                } else {
                    parseOl(nested, headword, bilingual, grammar, register, domain, senseNumber)
                    nested.remove()
                    leftoverDefinition(clone, grammar, register, domain, headword, senseNumber)
                }
                return
            }

            val markers = stripMarkers(clone)

            if (opensWithBold(clone)) {
                val bold = clone.children().firstOrNull { it.tagName() == "b" }!!
                val idiomName = normalize(bold.text())
                bold.remove()
                removeConnectors(clone, markers)

                val (text, examples) = glossText(clone, bilingual)
                val glossText = text.removePrefix(": ").trim()
                val idiom = Word.Idiom(idiomName, glossText)
                idiom.grammar = grammar
                idiom.gender = genderOf(grammar)
                idiom.register = register
                idiom.domain = domain
                idiom.senseNumber = senseNumber
                idiom.glosses.add(Word.Gloss().apply {
                    this.definition = glossText
                    this.grammar = grammar
                    this.gender = idiom.gender
                    this.examples.addAll(examples)
                })
                headword.idioms.add(idiom)
            } else {
                removeConnectors(clone, markers)
                val (text, examples) = glossText(clone, bilingual)
                val glossText = text.removePrefix(": ").trim()
                val definition = Word.Definition(glossText, li.clone())
                definition.grammar = grammar
                definition.gender = genderOf(grammar)
                definition.register = register
                definition.domain = domain
                definition.senseNumber = senseNumber
                definition.glosses.add(Word.Gloss().apply {
                    this.definition = glossText
                    this.grammar = grammar
                    this.gender = definition.gender
                    this.examples.addAll(examples)
                })
                headword.definitions.add(definition)
            }
        }

        // A locution <li> whose senses live in a nested <ol>:
// `<b>cap de mort</b><ol class="dict"><li>...sense 1...</li>...</ol>`.
        private fun parseLocutionWrapper(
            clone: Element,
            headword: Word,
            bilingual: Boolean,
            grammar: String,
            register: String,
            domain: String,
            senseNumber: String
        ) {
            val bold = clone.children().firstOrNull { it.tagName() == "b" }!!
            val idiomName = normalize(bold.text())

            val nested = clone.selectFirst("ol.dict")!!
            var gGrammar = ""
            var gRegister = ""
            var gDomain = ""
            val glosses = ArrayList<Word.Gloss>()
            for (child in nested.children()) {
                when {
                    child.hasClass("grammar") -> gGrammar = normalize(child.text())
                    child.hasClass("register") -> gRegister = normalize(child.text())
                    child.hasClass("dom") -> gDomain = normalize(child.text())
                    child.tagName() == "li" -> {
                        val item = child.clone()
                        stripAccessory(item)
                        val itemGrammar = item.children().firstOrNull { it.hasClass("grammar") }
                            ?.let { normalize(it.text()) } ?: gGrammar
                        val itemRegister = markersText(item, "register").ifEmpty { gRegister }
                        val itemDomain = markersText(item, "dom").ifEmpty { gDomain }
                        val markers = stripMarkers(item)
                        removeConnectors(item, markers)
                        val (text, examples) = glossText(item, bilingual)
                        val glossText = text.removePrefix(": ").trim()
                        if (glossText.isBlank()) continue
                        glosses.add(Word.Gloss().apply {
                            this.definition = glossText
                            this.grammar = itemGrammar
                            this.gender = genderOf(itemGrammar)
                            this.examples.addAll(examples)
                        })
                    }
                }
            }

            val idiom = Word.Idiom(idiomName, glosses.firstOrNull()?.definition.orEmpty())
            idiom.grammar = grammar
            idiom.gender = genderOf(grammar)
            idiom.register = register
            idiom.domain = domain
            idiom.senseNumber = senseNumber
            idiom.glosses.addAll(glosses)
            headword.idioms.add(idiom)
        }

        // When a GDLC sense-group <li> carries both a nested <ol> (already
        // parsed and removed) and its own running text, the text is a
        // definition and the nested items follow.
        private fun leftoverDefinition(
            clone: Element,
            grammar: String,
            register: String,
            domain: String,
            headword: Word,
            senseNumber: String = ""
        ) {
            clone.children().filter { it.hasClass("grammar") }.forEach { it.remove() }
            clone.children().filter { it.hasClass("register") }.forEach { it.remove() }
            clone.children().filter { it.hasClass("dom") }.forEach { it.remove() }
            val text = markup(clone)
            if (text.isBlank()) return

            val definition = Word.Definition(text, clone)
            definition.grammar = grammar
            definition.gender = genderOf(grammar)
            definition.register = register
            definition.domain = domain
            definition.senseNumber = senseNumber
            definition.glosses.add(Word.Gloss().apply {
                this.definition = text
                this.grammar = grammar
                this.gender = definition.gender
            })
            headword.definitions.add(definition)
        }

        // ---- flat (non-<ol>) entries ----

        private fun parseFlat(body: Element, headword: Word, bilingual: Boolean) {
            val grammarSpans = body.children().filter { it.hasClass("grammar") }
            val grammar = flatGrammar(body)
            val register = markersText(body, "register")
            val domain = markersText(body, "dom")

            val textEl = body.clone()
            textEl.children().filter { it.hasClass("grammar") }.forEach { it.remove() }
            textEl.children().filter { it.hasClass("register") }.forEach { it.remove() }
            textEl.children().filter { it.hasClass("dom") }.forEach { it.remove() }
            textEl.select(".figure-didac").remove()
            textEl.select(".didac-derived").remove()
            textEl.select("br").remove()

            // Drop the trailing "Vegeu tambe:" cross-link block: its anchors
            // point at source-XML ids, not browsable entries.
            stripAccessory(textEl)

            val examples = ArrayList<String>()
            for (i in textEl.select("i")) {
                val isEx = if (bilingual) bilingualExample(i) else isExample(i)
                if (isEx) {
                    if (bilingual) {
                        val sb = StringBuilder(normalize(i.text()))
                        var sibling = i.nextSibling()
                        while (sibling is TextNode) {
                            val s = sibling.text()
                            sb.append(if (s.trimStart().startsWith(",")) s else " " + s)
                            sibling.remove()
                            sibling = i.nextSibling()
                        }
                        examples.add(normalize(sb.toString()))
                    } else {
                        examples.add(normalize(i.text()))
                    }
                    i.remove()
                }
            }

            var text = markup(textEl)
            // Chained grammar spans (e.g. "adjectiu i pronom indefinits")
            // leave a bare connector text node ("i") at the start of the
            // gloss once the spans are removed.
            if (grammarSpans.size > 1) {
                text = text.removePrefix("i ").removePrefix("o ").trim()
            }
            val definition = Word.Definition(text, body.clone())
            definition.grammar = grammar
            definition.gender = genderOf(grammar)
            definition.register = register
            definition.domain = domain
            definition.glosses.add(Word.Gloss().apply {
                this.definition = text
                this.grammar = grammar
                this.gender = definition.gender
                this.examples.addAll(examples)
            })
            headword.definitions.add(definition)
        }

        // Grammar certificate joining chained grammar <span>s with the
        // "i"/"o" connector text nodes between them
        // ("adjectiu i pronom invariable"). Blank <span id=.../> hooks that
        // open an entry are skipped.
        private fun flatGrammar(wrapper: Element): String {
            val atoms = ArrayList<String>()
            for (node in wrapper.childNodes()) {
                when (node) {
                    is TextNode -> {
                        val text = node.text()
                        if (text.isBlank()) continue
                        val trimmed = text.trim()
                        if (trimmed == "i" || trimmed == "o" || trimmed == "y" ||
                            trimmed.startsWith("i ") || trimmed.startsWith("o ") || trimmed.startsWith("y ")
                        ) {
                            atoms.add(trimmed)
                        } else {
                            break
                        }
                    }
                    is Element -> {
                        if (node.hasClass("grammar")) {
                            atoms.add(normalize(node.text()))
                        } else if (node.tagName() == "span" && node.text().isBlank()) {
                            continue
                        } else {
                            break
                        }
                    }
                    else -> break
                }
            }
            return atoms.joinToString(" ")
        }

        // ---- per-<li> helpers ----

        private data class GlossOut(val text: String, val examples: List<String>)

        private fun glossText(li: Element, bilingual: Boolean): GlossOut {
            return if (bilingual) bilingualText(li) else monolingualText(li)
        }

        // Monolingual GDLC: strip sentence-length <i> usage examples.
        private fun monolingualText(li: Element): GlossOut {
            val textEl = li.clone()
            val examples = ArrayList<String>()
            for (i in textEl.select("i")) {
                if (isExample(i)) {
                    examples.add(normalize(i.text()))
                    i.remove()
                }
            }
            return GlossOut(markup(textEl), examples)
        }

        // In the bilingual dictionaries an <i> usage example is followed by
        // its translation ("<i>La bona taula ...</i>,  la buena mesa ...").
        // Pair them into one example; short <i> markers (m, f, sing, o) are
        // left in the running copy. See [isExample].
        private fun bilingualText(li: Element): GlossOut {
            val textEl = li.clone()
            val examples = ArrayList<String>()
            for (i in textEl.select("i")) {
                if (bilingualExample(i)) {
                    val sb = StringBuilder(normalize(i.text()))
                    var sibling = i.nextSibling()
                    while (sibling is TextNode) {
                        val s = sibling.text()
                        sb.append(if (s.trimStart().startsWith(",")) s else " " + s)
                        sibling.remove()
                        sibling = i.nextSibling()
                    }
                    examples.add(normalize(sb.toString()))
                    i.remove()
                }
            }
            return GlossOut(markup(textEl), examples)
        }

        // In the bilingual dictionaries an <i> usage example is followed by
        // its translation ("<i>La bona taula ...</i>,  la buena mesa ...").
        // Short <i> markers (m, f, sing, o) and "(o variant)" connectors
        // stay in the running copy.
        private fun bilingualExample(i: Element): Boolean {
            val text = i.text()
            if (text.isBlank() || text.length <= 4) return false
            if (parenthesizedConnector(i)) return false
            if (atGlossEnd(i)) return true
            val next = i.nextSibling()
            if (next is TextNode && next.text().trimStart().startsWith(",")) return true
            return text.contains(". ") || text.contains("? ") ||
                text.contains("! ") || text.contains("; ")
        }

        // Whether the <i> is an "(o variant)" connector rather than a usage
        // example: alone right after an opening parenthesis, or following an
        // "o"/"i"/"y" connector inside parentheses.
        private fun parenthesizedConnector(i: Element): Boolean {
            var prev: org.jsoup.nodes.Node? = i.previousSibling()
            if (prev is TextNode && prev.text().trimEnd().endsWith("(")) return true
            if (prev is Element && prev.tagName() == "i" &&
                (prev.text().trim() == "o" || prev.text().trim() == "i" || prev.text().trim() == "y")
            ) {
                val parent = i.parent() ?: return true
                val sb = StringBuilder()
                for (n in parent.childNodes()) {
                    if (n === i) break
                    sb.append(if (n is TextNode) n.text() else n.nodeName().lowercase())
                }
                return sb.toString().trimEnd().endsWith("(")
            }
            return false
        }

        // Whether the <li> opens with a bold fragment (skipping an optional
        // blank hook <span>, e.g. `<span/>` or `<span id="EC-...">`).
        private fun opensWithBold(li: Element): Boolean {
            var first: Element? = li.children().firstOrNull()
            while (first != null && first.tagName() == "span" && first.text().isBlank()) {
                first = first.nextElementSibling()
            }
            return first?.tagName() == "b"
        }

        // One marker class joined across all its spans (".dom" and ".register"
        // can each occur multiple times, separated by "i" connectors).
        private fun markersText(container: Element, className: String): String =
            container.children().filter { it.hasClass(className) }
            .map { normalize(it.text()) }
            .distinct()
            .joinToString(" ")

        // Remove grammar/register/domain spans from a copied <li> and report
        // how many were present.
        private fun stripMarkers(clone: Element): Int {
            val markers = clone.children().filter { it.hasClass("register") || it.hasClass("dom") || it.hasClass("grammar") }
            markers.forEach { it.remove() }
            return markers.size
        }

        // After the marker spans are gone, bare connector text nodes ("i",
        // "o", "y") between where they stood drop out of the running copy.
        private fun removeConnectors(clone: Element, markers: Int) {
            if (markers < 2) return
            val toRemove = clone.childNodes().filter {
                it is TextNode && (it.text().trim() == "i" || it.text().trim() == "o" ||
                    it.text().trim() == "y" || it.text().trim() == ",")
            }
            toRemove.forEach { it.remove() }
        }

        // Whatever follows a "Vegeu tambe:" heading is a cross-link block
        // pointing at source-XML ids, never a definition.
        private fun stripAccessory(container: Element) {
            container.select(".accessory_heading").forEach { heading ->
                var node: Element? = heading
                while (node != null) {
                    val next = node.nextElementSibling()
                    node.remove()
                    node = next
                }
            }
        }

        // Whether the <i> is a usage example rather than inline emphasis or a
        // target-gender marker: sentence-length text, sentence punctuation, or
        // the last meaningful element of the <li>.
        private fun isExample(i: Element): Boolean {
            val text = i.text()
            if (text.isBlank()) return false
            val length = text.length
            if (length <= 4) return false
            return atGlossEnd(i) ||
                length >= 40 ||
                text.contains(". ") || text.contains("? ") ||
                text.contains("! ") || text.contains("; ")
        }

        private fun atGlossEnd(i: Element): Boolean {
            val parent = i.parent() ?: return true
            val siblings = parent.childNodes()
            for (j in siblings.indexOf(i) + 1 until siblings.size) {
                when (val node = siblings[j]) {
                    is TextNode -> if (!node.isBlank) return false
                    is Element -> if (node.tagName() != "br") return false
                    else -> return false
                }
            }
            return true
        }

        // Serialize an element as simple HTML: keep <b>/<i>/<sup> inline
        // markup, unwrap everything else to its text, collapse whitespace.
        private fun markup(el: Element): String {
            val out = StringBuilder()
            for (node in el.childNodes()) {
                when (node) {
                    is TextNode -> out.append(node.text())
                    is Element -> {
                        when (node.tagName()) {
                            "b", "i", "sup" -> {
                                out.append("<").append(node.tagName()).append(">")
                                out.append(node.html())
                                out.append("</").append(node.tagName()).append(">")
                            }
                            else -> out.append(markup(node))
                        }
                    }
                }
            }
            return out.toString().replace(Regex("""\s+"""), " ").replace(Regex("""\s+,"""), ",")
            .replace(Regex("""\s+\."""), ".").trim()
        }

        private fun genderOf(grammar: String): String {
            val g = grammar.lowercase()
            val f = "femení" in g
            val m = "masculí" in g
            return when {
                f && m -> ""
                f -> Genders.FEMININE
                m -> Genders.MASCULINE
                else -> ""
            }
        }

        private fun cleanTitle(container: Element?): String {
            if (container == null) return ""
            for (sel in listOf("orth", "def")) {
                val el = container.selectFirst(sel)
                if (el != null) {
                    val t = normalize(el.text())
                    if (t.isNotEmpty()) return t
                }
            }
            return normalize(container.text())
        }

        private fun normalize(s: String): String =
            s.replace(Regex("""\s+"""), " ").trim()
    }
}