package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class SdoParser {
    companion object {
        /**
         * `ws.dsl.dk/<short>/livesearch` responses: the SDO livesearch endpoint
         * returns the same JSON array of plain headword strings as DDO's, so the
         * decoder is shared.
         */
        fun parseSearch(body: String, uriOf: (word: String) -> HttpUrl): List<SearchResult> =
            DdoParser.parseSearch(body, uriOf)

        /**
         * Parses an SDO page. ordnet.dk's SDO layout is span-based (unlike
         * DDO's div layout): `<span class="artikel">` (one per homograph) with
         * `.iddel .match` headword (accessed via `ownText()` so the optional
         * `.homnr` homograph number is dropped), `.lemklas` POS label (e.g.
         * "sb.", "vb."), a `.bøjdel` block for conjugation, `.semdel` blocks for
         * numbered senses, and `.sulesem` blocks for fixed expressions.
         * Returns JSON-renderable `Word`s.
         */
        fun parse(
            page: String,
            uri: HttpUrl,
            tag: String = "foo",
            baseUrl: String = "https://ordnet.dk/sdo/"
        ): List<Word> {
            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val doc = Jsoup.parse(page, finalBaseUrl)
            val words: ArrayList<Word> = ArrayList()

            doc.select(".artikel").forEachIndexed { index, artikel ->
                val wordEl = artikel.selectFirst(".iddel .match") ?: return@forEachIndexed
                val word = wordEl.ownText().trim()
                if (word.isEmpty()) return@forEachIndexed

                // RAE-style homograph uris; SDO pages may carry several articles.
                val newUri = if (index == 0) uri else uri.withQueryParam("__ref", (index + 1).toString())

                val headword = Word(
                    tag, word, word, word, newUri,
                    xrefs = arrayListOf((index + 1).toString())
                )

                parseMeta(headword, artikel, word)

                val grammar = artikel.selectFirst(".lemklas")?.text()?.trim() ?: ""

                val defs = ArrayList<Word.Definition>()
                val idioms = ArrayList<Word.Idiom>()

                artikel.select(".semdel").forEach { container ->
                    collectSenses(container, grammar, uri, defs, idioms)
                }
                artikel.select(".sulesem").forEach { container ->
                    collectIdioms(container, grammar, uri, idioms)
                }

                headword.definitions.addAll(defs)
                headword.idioms.addAll(idioms)

                words.add(headword)
            }

            if (words.size > 1) {
                val entries = Word.homonymEntries(words)

                for (word in words) {
                    word.mHomonymEntries.addAll(entries)
                }
            }

            return words
        }

        private fun parseMeta(headword: Word, artikel: Element, word: String) {
            artikel.selectFirst(".lemklas")?.let {
                headword.pos = normalizePos(it.text().trim())
            }

            // Each `.fondel .fon` is a pronunciation variant (e.g. "gå"/"gås").
            artikel.select(".fondel .fon").forEach {
                val text = it.text().trim()
                if (text.isEmpty()) return@forEach
                headword.pronunciation =
                    if (headword.pronunciation.isEmpty()) text else "${headword.pronunciation} / $text"
            }

            val forms = artikel.select(".bøjning .txt").map { it.text().trim() }
            if (forms.isNotEmpty()) {
                // "-" and "=" stand for the headword stem.
                headword.conjugation = forms.joinToString(", ") {
                    it.replace("-", word).replace("=", word)
                }
            }
        }

        /**
         * Walks one `.semdel` container: `.semem` numbered senses (`.betnr`)
         * followed by their `.subsem` sub-senses (`.subbetnr`, usually flat
         * siblings or nested inside the parent). Each sub-sense is numbered
         * "<parent>.<letter>".
         */
        private fun collectSenses(
            container: Element,
            grammar: String,
            pageUri: HttpUrl,
            defs: ArrayList<Word.Definition>,
            idioms: ArrayList<Word.Idiom>
        ) {
            var parentNumber = ""
            var letter = 'a'
            for (child in container.children()) {
                if (child.hasClass("semem")) {
                    parentNumber = senseNumber(child, ".betnr")
                    letter = 'a'
                    parseDefinition(child, parentNumber, grammar, pageUri)?.let(defs::add)
                    collectSubSenses(child, parentNumber, grammar, pageUri, defs)
                } else if (child.hasClass("subsem")) {
                    val number = if (parentNumber.isEmpty()) "$letter" else "$parentNumber.$letter"
                    parseDefinition(child, number, grammar, pageUri)?.let(defs::add)
                    letter += 1
                }
            }
        }

        private fun collectSubSenses(
            parent: Element,
            parentNumber: String,
            grammar: String,
            pageUri: HttpUrl,
            defs: ArrayList<Word.Definition>
        ) {
            var letter = 'a'
            for (sub in parent.children()) {
                if (!sub.hasClass("subsem")) continue
                val number = subNumber(parentNumber, letter)
                parseDefinition(sub, number, grammar, pageUri)?.let(defs::add)
                letter += 1
            }
        }

        private fun collectIdioms(
            container: Element,
            grammar: String,
            pageUri: HttpUrl,
            idioms: ArrayList<Word.Idiom>
        ) {
            val titleEl = container.selectFirst(".txt2") ?: return
            val title = glossText(titleEl).trim() // strips `.tryk` stress mark
            if (title.isEmpty()) return
            container.select(".sulesemdel").forEach { del ->
                var parentNumber = ""
                var letter = 'a'
                for (child in del.children()) {
                    if (child.hasClass("semem")) {
                        parentNumber = senseNumber(child, ".betnr")
                        letter = 'a'
                        parseIdiom(title, child, parentNumber, grammar, pageUri)?.let(idioms::add)
                        for (sub in child.children()) {
                            if (sub.hasClass("subsem")) {
                                val number = if (parentNumber.isEmpty()) "$letter" else "$parentNumber.$letter"
                                parseIdiom(title, sub, number, grammar, pageUri)?.let(idioms::add)
                                letter += 1
                            }
                        }
                    } else if (child.hasClass("subsem")) {
                        val number = if (parentNumber.isEmpty()) "$letter" else "$parentNumber.$letter"
                        parseIdiom(title, child, number, grammar, pageUri)?.let(idioms::add)
                        letter += 1
                    }
                }
            }
        }

        /** The `.subbetnr` bullet in SDO is always "•"; sub-senses enumerate a, b, c. */
        private fun subNumber(parentNumber: String, letter: Char): String =
            if (parentNumber.isEmpty()) "$letter" else "$parentNumber.$letter"

        private fun senseNumber(body: Element, sel: String): String {
            val n = body.selectFirst(sel)?.text()?.trim() ?: ""
            return if (n == "•") "" else n
        }

        private fun parseDefinition(
            body: Element,
            number: String,
            grammar: String,
            pageUri: HttpUrl
        ): Word.Definition? {
            val denbet = body.selectFirst(".denbet") ?: return null
            val defText = glossText(denbet)
            if (defText.isEmpty()) return null

            val definition = Word.Definition(defText, body.clone())
            definition.senseNumber = number
            definition.pos = grammar
            definition.grammar = grammar
            val (domain, register, geo) = marks(body.selectFirst(".spec"))
            definition.domain = domain
            definition.register = register
            definition.geo = geo

            val gloss = Word.Gloss()
            gloss.definition = defText
            gloss.grammar = grammar
            gloss.examples.addAll(collectExamples(body))
            definition.glosses.add(gloss)
            definition.examples.addAll(gloss.examples)

            body.select(".onym").forEach { synonymBox ->
                synonymBox.select(".syn .txt1, .ordfelt .txt1").forEach {
                    val text = it.text().trim()
                    if (text.isEmpty()) return@forEach
                    val href = it.selectFirst("a[href]")?.attr("href") ?: ""
                    definition.synonyms.add(Word.Synonym(text, resolveHref(href, pageUri)))
                }
            }

            return definition
        }

        private fun parseIdiom(
            title: String,
            body: Element,
            number: String,
            grammar: String,
            pageUri: HttpUrl
        ): Word.Idiom? {
            val denbet = body.selectFirst(".denbet") ?: return null
            val defText = glossText(denbet)
            if (defText.isEmpty()) return null

            val idiom = Word.Idiom(title, defText)
            idiom.senseNumber = number
            val (domain, register, geo) = marks(body.selectFirst(".spec"))
            idiom.domain = domain
            idiom.register = register
            idiom.geo = geo

            val gloss = Word.Gloss()
            gloss.definition = defText
            gloss.grammar = grammar
            gloss.examples.addAll(collectExamples(body))
            idiom.glosses.add(gloss)
            idiom.examples.addAll(gloss.examples)

            return idiom
        }

        /** `fag` → domain, `valør`/`kron`/`semspec` → register, `geo` → geo. */
        private fun marks(spec: Element?): Triple<String, String, String> {
            if (spec == null) return Triple("", "", "")
            val domain = spec.selectFirst(".fag")?.text()?.trim() ?: ""
            val register = spec.select(".valør, .kron, .semspec")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
            val geo = spec.selectFirst(".geo")?.text()?.trim() ?: ""
            return Triple(domain, register, geo)
        }

        /** The gloss text, with the `.spec` marker strip and the `.tryk` stress mark dropped. */
        private fun glossText(denbet: Element): String {
            val clone = denbet.clone()
            clone.select(".spec, .tryk").remove()
            return clone.text().trim()
        }

        /** The sense's direct `.rel` children: the Swedish example plus the optional Danish rendering. */
        private fun collectExamples(body: Element): ArrayList<String> {
            val examples = ArrayList<String>()
            for (rel in body.children()) {
                if (!rel.hasClass("rel")) continue
                val text = rel.selectFirst(".txt1")?.text()?.trim() ?: continue
                if (text.isEmpty()) continue
                val danish = rel.children().firstOrNull { it.hasClass("txt") }?.text()?.trim() ?: ""
                examples.add(if (danish.isEmpty()) text else "$text — $danish")
            }
            return examples
        }

        /** Relative hrefs (`query?q=...`, `?entry_id=...`) resolve against the page uri. */
        private fun resolveHref(href: String, pageUri: HttpUrl): String {
            if (href.isEmpty()) return ""
            if (href.startsWith("http")) return href
            return pageUri.resolve(href).toString()
        }

        private fun normalizePos(pos: String): Pos = when (pos.trim().lowercase()) {
            "sb." -> Pos.NOUN
            "vb." -> Pos.VERB
            "adj." -> Pos.ADJECTIVE
            "adv." -> Pos.ADVERB
            "konj." -> Pos.CONJUNCTION
            "præp.", "prep." -> Pos.PREPOSITION
            "pron." -> Pos.PRONOUN
            "interj." -> Pos.INTERJECTION
            else -> Pos.UNKNOWN
        }
    }
}