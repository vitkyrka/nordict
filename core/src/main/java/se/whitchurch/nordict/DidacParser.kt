package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parser for the DIDAC dictionary (www.diccionari.cat/didac, "Diccionari
 * escolar" of the Enciclopèdia Catalana).
 *
 * Two page shapes flow through [parse]:
 *  - a single entry URL (`/didac/cap1`, what the app's `get(uri)` fetches)
 *    contains exactly one `<article class="node--type-didac">`;
 *  - the search view (`/cerca/didac?search_api_fulltext_cust=<word>`, what
 *    the CLI and the app's `fullSearch` fetch) embeds every matching entry
 *    inline, each with its own `about="/didac/<slug>[<homograph>]"` URL.
 * Both are handled identically: iterate the articles, resolve each word's
 * canonical URL from its `about` attribute.
 *
 * Definitions live inside `<ol class="dict">`: a `<span class="grammar">`
 * labels the `<li>` items that follow it. When the grammar is an idiom
 * marker (`frase feta`, `locució que fa d'...`), the `<li>`s become
 * `Word.Idiom`s whose name is the bolded `<b>` fragment. Entries without an
 * `ol` are a single flat definition whose running text is the headword's
 * gloss.
 */
class DidacParser {
    companion object {

        /** diccionari.cat DIDAC autocomplete search responses. */
        fun parseSearch(body: String, uriOf: (path: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val array = JsonParser.parseString(body)
                if (!array.isJsonArray) return results
                array.asJsonArray.forEach { element ->
                    if (!element.isJsonObject) return@forEach
                    val obj = element.asJsonObject
                    // The trailing autocomplete item is the raw user input; it
                    // carries no URL, so it never becomes a search result.
                    val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                    if (url.isEmpty()) return@forEach
                    val title = obj.get("label")?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.let { label ->
                            cleanTitle(Jsoup.parse(label).selectFirst(".field--name-field-display-title"))
                        }
                        ?: ""
                    if (title.isEmpty()) return@forEach
                    results.add(SearchResult(title, uriOf(url)))
                }
            } catch (_: Exception) {
            }
            return results
        }

        fun parse(page: String, uri: HttpUrl, tag: String, baseUrl: String = "https://www.diccionari.cat"): List<Word> {
            val doc = Jsoup.parse(page)
            val articles = doc.select("article.node--type-didac")
            if (articles.isEmpty()) return emptyList()

            val titles = doc.select("h1.didac-title")
            val titleByArticle = articles.size == titles.size

            val words = ArrayList<Word>()
            articles.forEachIndexed { index, article ->
                val about = article.attr("about")
                val wordUri = if (about.isNotEmpty()) {
                    uri.resolve(about) ?: uri
                } else {
                    uri
                }

                val title = if (titleByArticle) {
                    titles[index].children().firstOrNull { it.tagName() == "div" }?.let { cleanTitle(it) } ?: ""
                } else {
                    slugTitle(about, wordUri)
                }
                if (title.isEmpty()) return@forEachIndexed

                val headword = Word(
                    tag, title, title, title, page, wordUri,
                    baseUrl.takeIf { it.endsWith("/") } ?: "$baseUrl/",
                    doc, "", article, renderAsJson = true
                )
                headword.rawHeadword = title
                headword.xrefs.add((index + 1).toString())

                val body = article.selectFirst(".field--name-body .div1")
                if (body != null) {
                    if (body.selectFirst("ol.dict") != null) {
                        parseOl(body.selectFirst("ol.dict")!!, headword)
                    } else {
                        parseFlat(body, headword)
                    }
                }

                words.add(headword)
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }
                val entries = Word.homonymEntries(words)
                for (word in words) {
                    word.mHomographs.addAll(homographs)
                    word.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private fun parseOl(ol: Element, headword: Word) {
            var grammar = ""
            var idioms = false
            for (child in ol.children()) {
                when {
                    child.hasClass("grammar") -> {
                        grammar = child.text()
                        idioms = isIdiomGrammar(grammar)
                    }
                    child.tagName() == "li" -> {
                        val li = liText(child)
                        if (idioms) {
                            val name = (child.selectFirst("b")?.text() ?: li.text).trim()
                            val idiom = Word.Idiom(name, li.text)
                            idiom.grammar = grammar
                            idiom.gender = genderOf(grammar)
                            idiom.glosses.add(Word.Gloss().apply {
                                this.definition = li.textNoBold
                                this.grammar = grammar
                                this.gender = idiom.gender
                                examples.addAll(li.examples)
                            })
                            headword.idioms.add(idiom)
                        } else {
                            val definition = Word.Definition(li.text, child.clone())
                            definition.grammar = grammar
                            definition.gender = genderOf(grammar)
                            definition.glosses.add(Word.Gloss().apply {
                                this.definition = li.text
                                this.grammar = grammar
                                this.gender = definition.gender
                                examples.addAll(li.examples)
                            })
                            headword.definitions.add(definition)
                        }
                    }
                }
            }
        }

        private fun parseFlat(body: Element, headword: Word) {
            val grammarSpans = body.select("span.grammar")
            val grammar = grammarSpans.joinToString(" ") { it.text() }
            val textEl = body.clone()
            textEl.select(".grammar").remove()
            textEl.select(".figure-didac").remove()
            textEl.select(".didac-derived").remove()
            textEl.select("br").remove()

            // Drop the trailing "Vegeu també:" cross-link block (its anchors
            // point at source-XML ids, not browsable entries) and any sibling
            // content after it, keeping in-text cross-references unchanged.
            textEl.selectFirst(".accessory_heading")?.let { heading ->
                var node: Element? = heading
                while (node != null) {
                    val next = node.nextElementSibling()
                    node.remove()
                    node = next
                }
            }

            val exampleTexts = ArrayList<String>()
            for (i in textEl.select("i")) {
                // <i> inside the plural/usage note is emphasis, not an example.
                if (i.parents().any { it.hasClass("exclamation-container") }) continue
                if (isExample(i)) {
                    exampleTexts.add(normalize(i.text()))
                    i.remove()
                }
            }

            var text = normalize(textEl.text())
            // Chained grammar spans (e.g. "determinant <i>i</i> pronom
            // indefinits") leave a bare connector text node ("i") at the start
            // of the gloss once the spans are removed; drop it when there were
            // several spans.
            if (grammarSpans.size > 1) {
                text = text.removePrefix("i ").removePrefix("o ").trim()
            }
            val definition = Word.Definition(text, body.clone())
            definition.grammar = grammar
            definition.gender = genderOf(grammar)
            definition.glosses.add(Word.Gloss().apply {
                this.definition = text
                this.grammar = grammar
                this.gender = definition.gender
                examples.addAll(exampleTexts)
            })
            headword.definitions.add(definition)
        }

        // Text of an ol.dict <li> with the example <i> blocks (see [isExample])
// stripped out. `textNoBold` additionally removes the bolded idiom-fragment
// (<b>fa cap</b>) so the idiom gloss reads naturally next to its name.
        private data class LiText(val text: String, val textNoBold: String, val examples: List<String>)

        private fun liText(li: Element): LiText {
            val textEl = li.clone()
            val examples = ArrayList<String>()
            for (i in textEl.select("i")) {
                if (isExample(i)) {
                    examples.add(normalize(i.text()))
                    i.remove()
                }
            }
            val first = textEl.children().firstOrNull()
            if (first != null && first.tagName() == "span" && first.text().isBlank()) {
                first.remove()
            }
            var text = normalize(textEl.text())
            var textNoBold = text
            val bold = textEl.selectFirst("b")
            if (bold != null) {
                bold.remove()
                textNoBold = normalize(textEl.text())
            }
            return LiText(text, textNoBold, examples)
        }

        // DIDAC marks usage examples with <i>, but <i> is also inline emphasis
        // (e.g. a word being discussed). Examples are sentence-length, carry
        // sentence punctuation, or are the last element of the gloss; short
        // mid-sentence emphasis is left in the definition text.
        private fun isExample(i: Element): Boolean {
            val text = i.text()
            if (text.isBlank()) return false
            val atEnd = i.parent()?.children()?.lastOrNull() === i
            return atEnd ||
                text.length >= 40 ||
                text.contains(". ") || text.contains("? ") ||
                text.contains("! ") || text.contains("; ")
        }

        private fun isIdiomGrammar(grammar: String): Boolean =
            grammar.contains("frase feta") || grammar.contains("locució")

        private fun genderOf(grammar: String): String {
            val feminine = grammar.contains("femení")
            val masculine = grammar.contains("masculí")
            return when {
                feminine && masculine -> ""
                feminine -> Genders.FEMININE
                masculine -> Genders.MASCULINE
                else -> ""
            }
        }

        private fun cleanTitle(el: Element?): String {
            if (el == null) return ""
            val clone = el.clone()
            clone.select("sup.homograph").remove()
            return normalize(clone.text())
        }

        // Fallback title from an article URL such as /didac/cap1 or /didac/cap-roig.
        private fun slugTitle(about: String, wordUri: HttpUrl): String {
            val path = if (about.isNotEmpty()) about else wordUri.encodedPath
            return path.substringAfterLast('/')
        }

        private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
    }
}