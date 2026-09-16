package se.whitchurch.nordict

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DdoParser {
    companion object {
        private val mp3Regex = "([0-9_]+)".toRegex()

        /**
         * `ws.dsl.dk/<short>/livesearch` responses: a JSON array of plain
         * headword strings. `uriOf` maps each word onto the dictionary's
         * entry URL (the CLI and [DslDictionary.search] share this decoder).
         */
        fun parseSearch(body: String, uriOf: (word: String) -> HttpUrl): List<SearchResult> {
            val results = ArrayList<SearchResult>()
            try {
                val array = JsonParser.parseString(body)
                if (!array.isJsonArray) return results
                array.asJsonArray.forEach { el ->
                    if (!el.isJsonPrimitive) return@forEach
                    val word = el.asString.trim()
                    if (word.isEmpty()) return@forEach
                    results.add(SearchResult(word, uriOf(word)))
                }
            } catch (_: Exception) {
            }
            return results
        }

        /**
         * Parses a DDO/SDO word page (the ordnet.dk `<div class="artikel">`
         * entry layout shared by the DDO and SDO dictionaries) into JSON
         * renderable `Word`s. A page carries one article, so normally one word
         * is returned.
         *
         * Senses live in `#content-betydninger` (`div.definitionNumber` +
         * `div.definitionIndent` pairs, with sub-senses "1.a" as nested
         * number+indent shells); fixed expressions live in
         * `#content-faste-udtryk` (each headed by a `div.definitionBox` with a
         * `span.match` title). Stand-alone idiom pages with no numbered-sense
         * containers fall back to parsing the article itself.
         */
        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String = "foo",
            baseUrl: String = "https://ordnet.dk/ddo/"
        ): List<Word> {
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)
            val words: ArrayList<Word> = ArrayList()

            doc.select("div.artikel").forEachIndexed { index, artikel ->
                val match = artikel.selectFirst(".definitionBoxTop .match")
                    ?: return@forEachIndexed
                val word = match.ownText().trim()
                if (word.isEmpty()) return@forEachIndexed

                // RAE-style homograph uris; DDO pages carry one article.
                val newUri = if (index == 0) uri else uri.withQueryParam("__ref", (index + 1).toString())

                val headword = Word(
                    tag, word, word, word, newUri,
                    xrefs = arrayListOf((index + 1).toString())
                )

                parseMeta(headword, artikel, word)
                headword.audio.addAll(audioFrom(artikel))

                val grammar = artikel.selectFirst(".definitionBoxTop .tekstmedium")
                    ?.text()?.trim() ?: ""

                val defs = ArrayList<Word.Definition>()
                val idioms = ArrayList<Word.Idiom>()

                artikel.selectFirst("#content-betydninger")?.let { container ->
                    collectSenses(container, grammar, uri, finalBaseUrl, defs, idioms)
                }
                artikel.selectFirst("#content-faste-udtryk")?.let { container ->
                    collectSenses(container, grammar, uri, finalBaseUrl, defs, idioms)
                }

                // Separate page for an idiom, e.g. "klappe hesten": the article
                // itself is the numbered-senses container.
                if (defs.isEmpty() && idioms.isEmpty()) {
                    collectSenses(artikel, grammar, uri, finalBaseUrl, defs, idioms)
                }

                headword.definitions.addAll(defs)
                headword.idioms.addAll(idioms)

                words.add(headword)
            }

            return words
        }

        private fun parseMeta(headword: Word, artikel: Element, word: String) {
            val grammar = artikel.selectFirst(".definitionBoxTop .tekstmedium")
                ?.text()?.trim() ?: ""
            headword.pos = normalizePos(grammar.substringBefore(','))

            artikel.selectFirst("#id-udt span.lydskrift")?.let {
                headword.pronunciation = it.text().trim()
            }

            artikel.selectFirst("#id-boj .allow-glossing")?.let {
                headword.conjugation = it.text().replace("-", word).trim()
            }

            artikel.selectFirst("#id-ety .allow-glossing")?.let {
                headword.etymology = it.text().trim()
            }
        }

        private fun audioFrom(artikel: Element): List<String> {
            val audio = ArrayList<String>()
            artikel.select("img[src='speaker.gif']").forEach {
                val onClick = it.attr("onclick")
                val mp3id = mp3Regex.find(onClick)?.groupValues?.get(1) ?: return@forEach
                audio.add("https://static.ordnet.dk/mp3/${mp3id.substring(0, 5)}/$mp3id.mp3")
            }
            return audio
        }

        /**
         * Walks a numbered-senses container (`#content-betydninger` for
         * definitions, `#content-faste-udtryk` for idioms). A `div.definitionIndent`
         * that wraps a `div.definitionNumber` + nested `div.definitionIndent` is a
         * sub-sense shell ("1.a") whose inner indent is the sense body. In the
         * idioms section, the preceding `div.definitionBox .match` heads the
         * following indent(s).
         */
        private fun collectSenses(
            container: Element,
            grammar: String,
            pageUri: HttpUrl,
            baseUrl: String,
            defs: ArrayList<Word.Definition>,
            idioms: ArrayList<Word.Idiom>
        ) {
            var pendingNumber = ""
            var idiomTitle = ""

            for (child in container.children()) {
                when {
                    child.hasClass("definitionNumber") -> pendingNumber = child.text().trim()

                    child.hasClass("definitionBox") -> {
                        child.selectFirst(".match")?.let {
                            val title = it.ownText().trim()
                            if (title.isNotEmpty()) idiomTitle = title
                        }
                    }

                    child.hasClass("definitionIndent") -> {
                        val shell = subsenseShell(child)
                        val body = shell?.second ?: child
                        val number = shell?.first ?: pendingNumber

                        if (idiomTitle.isEmpty()) {
                            parseDefinition(body, number, grammar, pageUri, baseUrl)?.let(defs::add)
                        } else {
                            parseIdiom(idiomTitle, body, number, grammar, pageUri, baseUrl)?.let(idioms::add)
                        }
                    }
                }
            }
        }

        /** Returns (number, body) when [el] is a sub-sense shell. */
        private fun subsenseShell(el: Element): Pair<String, Element>? {
            var number: String? = null
            var body: Element? = null
            for (child in el.children()) {
                if (number == null && child.hasClass("definitionNumber")) {
                    number = child.text().trim()
                } else if (body == null && child.hasClass("definitionIndent")) {
                    body = child
                }
            }
            val n = number ?: return null
            val b = body ?: return null
            return Pair(n, b)
        }

        private fun parseDefinition(
            body: Element,
            number: String,
            grammar: String,
            pageUri: HttpUrl,
            baseUrl: String
        ): Word.Definition? {
            val box = body.children().firstOrNull { it.hasClass("definitionBox") }
                ?: return null
            val defText = box.selectFirst("span.definition")?.text()?.trim()
                ?: box.selectFirst("span.tekstmedium")?.text()?.trim()
                ?: box.text().trim()
            if (defText.isEmpty()) return null

            val definition = Word.Definition(defText, box.clone())
            definition.senseNumber = number
            definition.pos = grammar
            definition.grammar = grammar
            box.selectFirst("span.stempelNoBorder")?.let {
                definition.domain = it.text().trim()
            }

            val gloss = Word.Gloss()
            gloss.definition = defText
            gloss.grammar = grammar
            gloss.examples.addAll(collectExamples(body))
            definition.glosses.add(gloss)
            definition.examples.addAll(gloss.examples)

            body.select(".definitionBox.onym").forEach { synonymBox ->
                synonymBox.select(".inlineList a").forEach {
                    val text = it.text().trim()
                    if (text.isEmpty()) return@forEach
                    definition.synonyms.add(Word.Synonym(text, resolveHref(it.attr("href"), pageUri, baseUrl)))
                }
            }

            return definition
        }

        private fun parseIdiom(
            title: String,
            body: Element,
            number: String,
            grammar: String,
            pageUri: HttpUrl,
            baseUrl: String
        ): Word.Idiom? {
            val box = body.children().firstOrNull { it.hasClass("definitionBox") }
                ?: return null
            val defText = box.selectFirst("span.definition")?.text()?.trim()
                ?: box.selectFirst("span.tekstmedium")?.text()?.trim()
                ?: box.text().trim()
            if (defText.isEmpty()) return null

            val idiom = Word.Idiom(title, defText)
            idiom.senseNumber = number

            val gloss = Word.Gloss()
            gloss.definition = defText
            gloss.grammar = grammar
            gloss.examples.addAll(collectExamples(body))
            idiom.glosses.add(gloss)
            idiom.examples.addAll(gloss.examples)

            box.selectFirst("span.stempelNoBorder")?.let {
                idiom.domain = it.text().trim()
            }

            return idiom
        }

        /** The "Eksempler" detail boxes' list entries plus sentence quotes. */
        private fun collectExamples(body: Element): ArrayList<String> {
            val examples = ArrayList<String>()
            body.select(".definitionBox.details").forEach { details ->
                val label = details.selectFirst(".stempel")?.text() ?: return@forEach
                if (label != "Eksempler") return@forEach
                details.selectFirst(".inlineList")?.textNodes()?.forEach {
                    val example = it.toString().trim()
                    if (example.isNotEmpty()) examples.add(example)
                }
            }
            body.select(".citat").forEach {
                val quote = it.text().trim()
                if (quote.isNotEmpty()) examples.add(quote)
            }
            return examples
        }

        /** `?entry_id=...` relative hrefs resolve against the page uri. */
        private fun resolveHref(href: String, pageUri: HttpUrl, baseUrl: String): String =
            when {
                href.isEmpty() -> ""
                href.startsWith("http") -> href
                href.startsWith("?") ->
                    pageUri.newBuilder()!!.query(href.substring(1)).build().toString()
                else -> baseUrl.trimEnd('/') + "/" + href.trimStart('/')
            }

        private fun normalizePos(pos: String): Pos = when (pos.trim().lowercase()) {
            "adjektiv" -> Pos.ADJECTIVE
            "adverbium", "adverb" -> Pos.ADVERB
            "konjunktion" -> Pos.CONJUNCTION
            "interjektion" -> Pos.INTERJECTION
            "præposition" -> Pos.PREPOSITION
            "pronomen" -> Pos.PRONOUN
            "substantiv" -> Pos.NOUN
            "verbum", "verb" -> Pos.VERB
            else -> Pos.UNKNOWN
        }
    }
}