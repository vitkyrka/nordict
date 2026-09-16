package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class InfopediaParser {
    companion object {

        /**
         * Infopedia's autocomplete (`sugestao-pesquisa/<query>`) returns a JSON
         * object whose `html` field is an HTML fragment of `<li title="...">`
         * suggestions, each `title` being the headword. The result's word-page
         * URL is the dict path + the title (`uriOf(title)`).
         */
        fun parseSearch(body: String, uriOf: (title: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            val doc = Jsoup.parse(body)
            doc.select("li").forEach {
                val title = it.attr("title")
                if (title.isEmpty()) return@forEach
                results.add(SearchResult(title, uriOf(title)))
            }
            return results
        }

        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String,
            baseUrl: String = "https://www.infopedia.pt/"
        ): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)

            doc.selectFirst("nav")?.remove()
            doc.selectFirst(".nav-container")?.remove()
            doc.selectFirst(".favorites")?.remove()
            doc.selectFirst("#footer-body")?.remove()
            doc.selectFirst("#footer-header-arrow")?.remove()
            doc.selectFirst(".partilharReferenciarContainer")?.remove()
            doc.select("script").forEach { it.remove() }
            doc.select(".google-pub-container").forEach { it.remove() }
            doc.select(".col-widgets").forEach { it.remove() }
            doc.select("iframe").forEach { it.remove() }
            doc.select("ins").forEach { it.remove() }
            doc.selectFirst("#comments-and-suggestions-modal")?.remove()
            doc.selectFirst("#commentsContainer")?.remove()
            doc.selectFirst(".dolGestualBaseContainer")?.remove()
            doc.selectFirst("#support-articles-info")?.remove()

            // The word-level accessory sections (etymology, and the synonym /
            // antonym boxes) live after the article, outside `.dolEntradaVverbete`;
            // a page carries one entry so they attach to the first word.
            val etymology = etymologyOf(doc)
            val synonyms = synonymsOf(doc, finalBaseUrl)
            val antonyms = antonymsOf(doc, finalBaseUrl)

            var first = true
            var ref = 0

            doc.select(".dolEntradaVverbete").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.withQueryParam("__ref", ref.toString())
                }

                first = false

                val word = lemma.selectFirst(".dolEntrinfoEntrada")?.text()?.trim() ?: return@forEach

                val headword = Word(
                    tag, word, word, word, newUri,
                    xrefs = arrayListOf(ref.toString())
                )

                headword.pronunciation = pronunciationOf(lemma)
                headword.etymology = etymology

                // The word's TTS clip: `<audio class="audio-player-word-tts">`.
                lemma.select("audio.audio-player-word-tts").forEach { audio ->
                    val src = audio.attr("src")
                    if (src.isEmpty()) return@forEach
                    val url = if (src.startsWith("http")) src else finalBaseUrl.trimEnd('/') + src
                    headword.audio.add(url)
                }

                var entryGrammar = ""

                // Each POS group (`dolDivisaoCatgram`) carries its own `.dolCatgramTbcat`
                // label; its senses live in `.dolCatgramAceps .dolAcepsRow`.
                lemma.select(".dolDivisaoCatgram").forEach { catgram ->
                    val pos = catgram.selectFirst(".dolCatgramTbcat")?.text()?.trim() ?: ""
                    if (entryGrammar.isEmpty() && pos.isNotEmpty()) {
                        entryGrammar = pos
                    }
                    val gender = genderOf(pos)

                    catgram.select(".dolAcepsRow").forEach { row ->
                        val definition = parseDefinition(row, pos, gender)
                        headword.definitions.add(definition)
                    }
                }

                // Word-level synonym/antonym lists attach to the first definition.
                if (headword.definitions.isNotEmpty()) {
                    headword.definitions[0].synonyms.addAll(synonyms)
                    headword.definitions[0].antonyms.addAll(antonyms)
                }

                // Locuções: each `.dolLexegerExeger` heads one expression whose
                // senses sit in the sibling `.dolTable` that follows it
                // (`.dolLexegerExeger` and `.dolTable` alternate inside
                // `.dolVverbeteLexeger`). They share the entry's grammar.
                val idiomGender = genderOf(entryGrammar)
                lemma.select(".dolVverbeteLexeger").forEach { lexeger ->
                    var current: Word.Idiom? = null
                    for (child in lexeger.children()) {
                        if (child.hasClass("dolLexegerExeger")) {
                            parseIdiomHead(child, entryGrammar, idiomGender)?.let {
                                headword.idioms.add(it)
                                current = it
                            }
                        } else if (child.hasClass("dolTable")) {
                            val idiom = current
                            if (idiom != null) {
                                child.select(".dolRow").forEach { row ->
                                    val gloss = Word.Gloss()
                                    gloss.definition = glossText(row)
                                    gloss.grammar = entryGrammar
                                    gloss.gender = idiomGender
                                    if (gloss.definition.isNotEmpty()) {
                                        idiom.glosses.add(gloss)
                                    }
                                }
                            }
                        }
                    }
                }

                lemma.remove()
                words.add(headword)
            }

            if (words.size > 1) {
                val entries = Word.homonymEntries(words)

                for (w in words) {
                    w.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        // Syllabification (me.sa), phonetic transcription (ˈmezɐ), and the
        // orthoepy note (/ê/) become the header pronunciation line.
        private fun pronunciationOf(lemma: Element): String {
            val parts = ArrayList<String>()
            lemma.selectFirst(".dolSilab")?.text()?.trim()?.let { if (it.isNotEmpty()) parts.add(it) }
            lemma.selectFirst(".dolRegfonFonet")?.text()?.trim()?.let { if (it.isNotEmpty()) parts.add(it) }
            lemma.selectFirst(".dolEntrinfoOrtoep")?.text()?.trim()?.let { if (it.isNotEmpty()) parts.add(it) }
            return parts.joinToString(" ")
        }

        private fun etymologyOf(doc: org.jsoup.nodes.Document): String {
            val text = doc.selectFirst(".dolVverbeteEtim")?.text()?.trim() ?: return ""
            return text.removePrefix("Etimologia:").trim()
        }

        private fun parseDefinition(row: Element, pos: String, gender: String): Word.Definition {
            val glossText = glossText(row)
            val definition = Word.Definition(glossText, row.clone())
            definition.pos = pos
            definition.grammar = pos
            definition.gender = gender
            definition.domain = row.selectFirst(".dolSubacepTbdom")?.text()?.trim() ?: ""
            definition.register = row.selectFirst(".dolSubacepTbreg")?.text()?.trim() ?: ""
            definition.geo = row.selectFirst(".dolSubacepTbvar")?.text()?.trim() ?: ""

            val num = row.selectFirst(".dolAcepsNum")?.text()?.trim()?.removeSuffix(".") ?: ""
            definition.senseNumber = num

            val gloss = Word.Gloss()
            gloss.definition = glossText
            gloss.grammar = pos
            gloss.gender = gender
            definition.glosses.add(gloss)

            return definition
        }

        // The gloss is the sense's translation text (`.dolSubacepTraduz`
        // > `.dolTraduzTrad`), with any `.dolAcepsExplica` note (e.g.
        // "[com maiúscula]") and locução `.dolSubacepContex` (e.g.
        // "(ginástica)") folded in front.
        private fun glossText(sense: Element): String {
            val sb = StringBuilder()
            sense.selectFirst(".dolAcepsExplica")?.text()?.trim()?.let { expl ->
                if (expl.isNotEmpty()) sb.append(expl).append(" ")
            }
            sense.selectFirst(".dolSubacepContex")?.text()?.trim()?.let { ctx ->
                if (ctx.isNotEmpty()) sb.append(ctx).append(" ")
            }
            var first = true
            sense.select(".dolSubacepTraduz .dolTraduzTrad").forEach { trad ->
                val text = trad.text().trim()
                if (text.isEmpty()) return@forEach
                if (!first) sb.append(", ")
                sb.append(text)
                first = false
            }
            return sb.toString().trim()
        }

        private fun parseIdiomHead(loc: Element, grammar: String, gender: String): Word.Idiom? {
            val name = loc.selectFirst(".dolExegerLexpress")?.text()?.trim() ?: ""
            if (name.isEmpty()) return null

            val idiom = Word.Idiom(name, "")
            idiom.domain = loc.selectFirst(".dolExegerTbdom")?.text()?.trim() ?: ""
            idiom.register = loc.selectFirst(".dolExegerTbreg")?.text()?.trim() ?: ""
            idiom.grammar = grammar
            idiom.gender = gender

            return idiom
        }

        private fun synonymsOf(doc: org.jsoup.nodes.Document, finalBaseUrl: String): ArrayList<Word.Synonym> {
            val container = synonymsContainer(doc) ?: return ArrayList()
            val synonyms = ArrayList<Word.Synonym>()
            container.select(".dolRelacoesAssociacao a").forEach { a ->
                val text = a.text().trim()
                // The "see more" link renders a literal ellipsis ("...").
                if (text.isEmpty() || text.startsWith("...")) return@forEach
                synonyms.add(Word.Synonym(text, resolveHref(a.attr("href"), finalBaseUrl)))
            }
            return synonyms
        }

        // Antonyms are extracted from the "ANTÓNIMOS" box, plain words attached
        // (not linked synonyms) per the shared schema.
        private fun antonymsOf(doc: org.jsoup.nodes.Document, finalBaseUrl: String): ArrayList<String> {
            val container = containerMatching(doc, "antónim") ?: return ArrayList()
            val antonyms = ArrayList<String>()
            container.select(".dolRelacoesAssociacao a, .dolRelacoesAssociacao span").forEach { a ->
                val text = a.text().trim()
                if (text.isEmpty() || text.contains(",")) return@forEach
                antonyms.add(text)
            }
            return antonyms
        }

        // Current pages mark the synonym box with an explicit id
        // (`relacoesSinonimosContainer`); older ones render a `.title`
        // heading like "SINÓNIMOS" / "sinónimos de <word>".
        private fun synonymsContainer(doc: org.jsoup.nodes.Document): Element? =
            doc.selectFirst("#relacoesSinonimosContainer")
                ?: containerMatching(doc, "sinónim")

        private fun containerMatching(doc: org.jsoup.nodes.Document, marker: String): Element? {
            for (block in doc.select(".dolRelacoes")) {
                val title = block.selectFirst(".title")?.text() ?: continue
                if (title.contains(marker, ignoreCase = true)) {
                    return block.selectFirst(".dolRelacoesAssociacao") ?: block
                }
            }
            return null
        }

        private fun resolveHref(href: String, finalBaseUrl: String): String =
            if (href.startsWith("http")) href else finalBaseUrl.trimEnd('/') + href

        private fun genderOf(pos: String): String {
            val lower = pos.lowercase()
            return when {
                lower.contains("feminino") -> Genders.FEMININE
                lower.contains("masculino") -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}