package se.whitchurch.nordict

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DleParser {
    companion object {

        /** RAE `/srv/keys` search responses for DLE (see [KeyItemSearchResults]). */
        fun parseSearch(body: String, uriOf: (item: String) -> HttpUrl): List<SearchResult> =
            KeyItemSearchResults.parse(body, uriOf)

        fun parse(page: String, uri: HttpUrl, tag: String, baseUrl: String = "https://dle.rae.es/"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            var doc = Jsoup.parse(page)

            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val element = doc.selectFirst("#resultados") ?: return emptyList()

            element.selectFirst(".o-container")?.remove()
            element.selectFirst("#conjugacionfYMCdHV")?.remove()
            element.selectFirst("#sinonimosDgIqVCc")?.remove()

            val content = element.outerHtml()
            val cleanPage = doc.head().html() + "<body>" + content
            doc = Jsoup.parse(cleanPage)

            doc.select("abbr").forEach { el ->
                val title = el.attr("title")
                if (title.isNotEmpty()) {
                    el.text(title)
                }
            }

            var first = true
            var ref = 0

            doc.select("article").forEach { lemma ->
                ref += 1

                val newUri = if (first) {
                    uri
                } else {
                    uri.newBuilder().addQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val taglemma = lemma.selectFirst("header h1") ?: return@forEach
                val word = taglemma.text().trim('"')
                val summary = StringBuilder(word)

                val headword = Word(
                    tag, word, word, summary.toString(), newUri
                )
                headword.rawHeadword = Word.raeSearchKey(word)

                // Etymology: div.n2.c-text-intro (e.g. "Del lat. cacāre.")
                val etymEl = lemma.selectFirst("div.n2.c-text-intro")
                if (etymEl != null) {
                    headword.etymology = etymEl.text()
                }

                // Conjugation/participle: div.n5.c-text-intro
                val morphEl = lemma.selectFirst("div.n5.c-text-intro")
                if (morphEl != null) {
                    val morphText = morphEl.text()
                    val conjMatch = Regex("(Conjug\\. c\\.|Conjugación como)\\s+(.+?)(?:;|$)").find(morphText)
                    if (conjMatch != null) {
                        headword.conjugation = conjMatch.groupValues[2].trim().trimEnd(';', '.', ' ')
                    }
                    val partMatch = Regex("part\\. irreg\\. (.+?)\\.?$").find(morphText)
                    if (partMatch == null) {
                        val partMatch2 = Regex("participio irregular\\s+(.+?)\\.?$").find(morphText)
                        if (partMatch2 != null) {
                            headword.participle = partMatch2.groupValues[1].trim().trimEnd('.', ' ')
                        }
                    } else {
                        headword.participle = partMatch.groupValues[1].trim().trimEnd('.', ' ')
                    }
                }

                headword.xrefs.add(ref.toString())

                // Idioms: one Idiom per h3 header (matching the original page,
                // which groups N numbered li senses under one headword). Each
                // li's gloss carries its .n_acep number; idiom-level markers
                // come from the first li. h3.l2 cross-references (e.g. "V.
                // enfrente.") head no senses and are skipped.
                var currentIdiom: String? = null
                val pendingIdiomItems = ArrayList<Element>()

                fun flushIdiomGroup() {
                    if (currentIdiom != null && pendingIdiomItems.isNotEmpty()) {
                        headword.idioms.add(parseIdiomGroup(currentIdiom!!, pendingIdiomItems))
                        pendingIdiomItems.clear()
                    }
                }

                lemma.select("h3, ol.c-definitions > li").forEach { child ->
                    if (child.tagName() == "h3") {
                        flushIdiomGroup()
                        currentIdiom = if (child.hasClass("l2")) null else child.text()
                        return@forEach
                    }

                    val meaning = child
                    val defItem = meaning.selectFirst(".c-definitions__item") ?: meaning

                    if (currentIdiom == null) {
                        headword.definitions.add(parseDefinition(defItem, meaning, finalBaseUrl))
                        meaning.remove()
                    } else {
                        pendingIdiomItems.add(defItem)
                    }
                }
                flushIdiomGroup()

                lemma.remove()
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

        // The original page number of a definition/idiom sense (DLE
        // `.n_acep`, e.g. "1. "). Stored without the trailing dot.
        private fun senseNumberOf(defItem: Element): String =
            defItem.selectFirst(".n_acep")?.text()?.trim()?.trimEnd('.')?.trim() ?: ""

        // Parse a definition <li> element into a Word.Definition.
        private fun parseDefinition(defItem: Element, meaning: Element, baseUrl: String): Word.Definition {
            val definition = Word.Definition(defItem.text(), meaning.clone())
            definition.senseNumber = senseNumberOf(defItem)

            val mainDiv = defItem.children().firstOrNull { it.tagName() == "div" && !it.hasClass("c-definitions__item-footer") }
                ?: defItem

            val (grammar, register, domain, geo, defText) = parseMainDiv(mainDiv)

            definition.grammar = grammar
            definition.register = register
            definition.domain = domain
            definition.geo = geo
            definition.gender = genderOf(grammar)

            if (defText.isNotEmpty()) {
                val gloss = Word.Gloss()
                gloss.definition = defText
                gloss.grammar = grammar
                gloss.gender = definition.gender
                definition.glosses.add(gloss)
            }

            // Examples are <span class="h"> inside the main div.
            mainDiv.select("span.h").forEach { example ->
                definition.glosses.firstOrNull()?.examples?.add(example.text())
            }

            // Synonyms and antonyms from the footer.
            defItem.selectFirst(".c-definitions__item-footer")?.select(".c-word-list")?.forEach { wordList ->
                val label = wordList.selectFirst(".c-word-list__label")?.text() ?: ""
                val isAntonym = label.contains("Antón")
                val target = wordList.select(".c-word-list__items .sin")
                for (synEl in target) {
                    if (isAntonym) {
                        definition.antonyms.add(synEl.text())
                    } else {
                        definition.synonyms.add(parseSynonym(synEl, baseUrl))
                    }
                }
            }

            return definition
        }

        private fun parseSynonym(synEl: Element, baseUrl: String): Word.Synonym {
            val wrapper = synEl.parent()
            // The malsonante marker lives on <abbr class="sin_alert" title="..."/>,
            // not on the wrapper span's title attribute.
            val plev = wrapper?.selectFirst("abbr.sin_alert")?.attr("title") ?: ""
            val dataId = synEl.attr("data-id")
            val href = if (dataId.isNotEmpty()) "${baseUrl}?id=$dataId" else ""
            return Word.Synonym(synEl.text(), href, plev)
        }

        // Parse one h3 idiom group (all li senses under the header) into a
        // single Word.Idiom. Each sense becomes one gloss carrying its
        // .n_acep number; idiom-level markers come from the first sense.
        private fun parseIdiomGroup(idiomName: String, items: List<Element>): Word.Idiom {
            val first = items.first()
            val firstMain = first.children().firstOrNull { it.tagName() == "div" && !it.hasClass("c-definitions__item-footer") }
                ?: first

            val (grammar, register, domain, geo, _) = parseMainDiv(firstMain)

            val idiom = Word.Idiom(idiomName, first.text())
            idiom.grammar = grammar
            idiom.register = register
            idiom.domain = domain
            idiom.geo = geo
            idiom.gender = genderOf(grammar)

            items.forEach { defItem ->
                val mainDiv = defItem.children().firstOrNull { it.tagName() == "div" && !it.hasClass("c-definitions__item-footer") }
                    ?: defItem

                val (g, _, _, _, defText) = parseMainDiv(mainDiv)
                if (defText.isEmpty()) return@forEach

                val gloss = Word.Gloss()
                gloss.definition = defText
                gloss.grammar = g.ifEmpty { grammar }
                gloss.gender = genderOf(gloss.grammar)
                gloss.senseNumber = senseNumberOf(defItem)
                mainDiv.select("span.h").forEach { example ->
                    gloss.examples.add(example.text())
                }
                idiom.glosses.add(gloss)
            }

            idiom.glosses.firstOrNull()?.let {
                idiom.examples.addAll(it.examples)
            }

            return idiom
        }

        private fun parseMainDiv(mainDiv: Element): ParsedMainDiv {
            val grammarParts = mutableListOf<String>()
            val registerParts = mutableListOf<String>()
            var domain = ""
            var geo = ""

            // Classify the abbr markers (grammar / register / domain / geo).
            for (abbr in mainDiv.select("abbr")) {
                when (val marker = classifyMarker(abbr)) {
                    is Marker.Grammar -> grammarParts.add(marker.text)
                    is Marker.Register -> registerParts.add(marker.text)
                    is Marker.GrammarAndRegister -> {
                        grammarParts.add(marker.grammar)
                        registerParts.add(marker.register)
                    }
                    is Marker.Domain -> domain = marker.text
                    is Marker.Geo -> geo = marker.text
                    null -> {}
                }
            }

            // Build the definition text by cloning the div, removing the number,
            // example spans, and the classified markers, then taking the remaining text.
            val textDiv = mainDiv.clone()
            textDiv.select(".n_acep").remove()
            textDiv.select("span.h").remove()
            textDiv.select("abbr").forEach { abbr ->
                if (classifyMarker(abbr) != null) {
                    abbr.remove()
                }
            }
            // Remove footer inside clone if present
            textDiv.select(".c-definitions__item-footer").remove()
            val defText = textDiv.text().trim()

            val grammar = grammarParts.joinToString(" ")
            val register = registerParts.joinToString(" ")
            return ParsedMainDiv(grammar, register, domain, geo, defText)
        }

        private data class ParsedMainDiv(
            val grammar: String,
            val register: String,
            val domain: String,
            val geo: String,
            val defText: String
        )

        private sealed class Marker {
            class Grammar(val text: String) : Marker()
            class Register(val text: String) : Marker()
            class GrammarAndRegister(val grammar: String, val register: String) : Marker()
            class Domain(val text: String) : Marker()
            class Geo(val text: String) : Marker()
        }

        private fun classifyMarker(abbr: Element): Marker? {
            val t = abbr.text()
            if (isRegister(t)) return Marker.Register(t)
            if (isGrammar(t)) return Marker.Grammar(t)

            // Combined markers like "locución verbal coloquial" or
            // "verbo transitivo poco usado".
            val g = grammarPart(t)
            if (g != null) {
                val reg = registerPart(t)
                if (reg != null) return Marker.GrammarAndRegister(g, reg)
            }

            if (isDomain(t)) return Marker.Domain(t)

            // Class "c" marks domain/geo markers in the DLE markup; anything
            // that is not a known domain is a geographic marker (e.g. "España",
            // "El Salvador y México", "Cuba y Venezuela").
            if (abbr.hasClass("c") || isGeo(t)) return Marker.Geo(t)

            return null
        }

        private fun isRegister(text: String): Boolean {
            return text in REGISTER_TERMS
        }

        private fun isGrammar(text: String): Boolean {
            return text in GRAMMAR_TITLES
        }

        private fun isDomain(text: String): Boolean {
            return text in DOMAIN_TERMS
        }

        // A combined marker like "locución verbal coloquial" pairs a grammar
        // phrase with a trailing register term. Split the grammar prefix.
        private fun grammarPart(text: String): String? {
            for (g in GRAMMAR_TITLES) {
                if (text == g) return null
                if (text.startsWith("$g ")) {
                    val rest = text.removePrefix("$g ").trim()
                    if (rest in REGISTER_TERMS) return g
                }
            }
            return null
        }

        private fun registerPart(text: String): String? {
            for (g in GRAMMAR_TITLES) {
                if (text == g) return null
                if (text.startsWith("$g ")) {
                    val rest = text.removePrefix("$g ").trim()
                    if (rest in REGISTER_TERMS) return rest
                }
            }
            return null
        }

        private fun isGeo(text: String): Boolean {
            // Geographic markers are two-plus-word abbr titles like "El Salvador y
            // México", "Cuba y Venezuela", "Colombia" or "Argentina".
            return text.length < 50 && !isDomain(text) && !isRegister(text) && !isGrammar(text) &&
                (text.contains(" y ") && !text.startsWith("Usado"))
        }

        private val REGISTER_TERMS = setOf(
            "coloquial", "coloquiales", "malsonante", "malsonantes", "vulgar",
            "familiar", "formal", "literal", "figurado", "ironía",
            "desusado", "desusada", "desusados", "desusadas",
            "antiguamente", "desueco",
            "poco usado", "poco usada", "poco usados", "poco usadas",
            "uso coloquial"
        )

        private val GRAMMAR_TITLES = setOf(
            Genders.GRAMMAR_MASCULINE, Genders.GRAMMAR_MASCULINE_PLURAL,
            Genders.GRAMMAR_FEMININE, Genders.GRAMMAR_FEMININE_PLURAL,
            "nombre masculino o femenino", "nombre femenino o masculino",
            "adjetivo", "adjetivo invariable",
            "adverbio", "artículo",
            "conjunción", "expresión", "expresiones", "interjección", "numeral", "participio",
            "preposición", "pronombre", "pronombre átono", "pronombre personal",
            "verbo intransitivo", "verbo intransitivo pronominal", "verbo transitivo",
            "verbo transitivo pronominal", "verbo pronominal", "verbo impersonal",
            "verbo copulativo", "verbo auxiliar", "verbo",
            "locución", "locución adjetiva", "locución adverbial", "locución conjuntiva",
            "locución interjectiva", "locución nominal", "locución preposicional",
            "locución prepositiva", "locución pronominal", "locución verbal",
            "locuciones adverbiales", "locuciones verbales"
        )

        private val DOMAIN_TERMS = setOf(
            "Meteorología", "Milicia", "Física", "Química", "Medicina",
            "Derecho", "Economía", "Informática", "Matemáticas", "Biología",
            "Geología", "Astronomía", "Música", "Historia", "Filosofía",
            "Literatura", "Arte", "Religión", "Deporte",
            "Mitología", "Óptica", "Mecánica", "Genética", "Militar"
        )

        private fun genderOf(gramTitle: String?): String {
            return when {
                gramTitle == null -> ""
                gramTitle.contains("femenino") -> if (gramTitle.contains("masculino")) "" else Genders.FEMININE
                gramTitle.contains("masculino") -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}
