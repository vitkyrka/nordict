package se.whitchurch.nordict

import android.net.Uri
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class EstParser {
    companion object {
        fun parse(page: String, uri: Uri, tag: String, baseUrl: String = "https://www.rae.es/diccionario-estudiante/"): List<Word> {
            val words: ArrayList<Word> = ArrayList()
            var doc = Jsoup.parse(page)

            val finalBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

            val element = doc.selectFirst("#resultados") ?: return emptyList()
            element.selectFirst(".verDLE")?.remove()

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
                    uri.buildUpon().appendQueryParameter("__ref", ref.toString()).build()
                }

                first = false

                val taglemma = lemma.selectFirst("header span.entrada") ?: return@forEach
                val word = taglemma.text().trim('"')
                val summary = StringBuilder(word)

                val headword = Word(
                    tag, word, word, summary.toString(), page, newUri,
                    finalBaseUrl,
                    doc,
                    "",
                    lemma,
                    renderAsJson = true
                )

                // Capture conjugation and participle info from div.par
                val parDiv = lemma.selectFirst("div.paracep div.par")
                if (parDiv != null) {
                    val verboModelo = parDiv.selectFirst("span.verboModelo")
                    if (verboModelo != null) {
                        headword.conjugation = verboModelo.text()
                    }
                    val participio = parDiv.selectFirst("span[class*=participio]")
                    if (participio != null) {
                        headword.participle = participio.text()
                    }
                }

                headword.xrefs.add(ref.toString())

                // Definitions
                lemma.select("div.acep").forEach { meaning ->
                    // Skip idioms-section acep elements (they live inside .locs)
                    if (meaning.parents().any { it.hasClass("locs") }) return@forEach

                    val primaryDef = meaning.selectFirst(".def")?.text() ?: meaning.text()
                    val definition = Word.Definition(primaryDef, meaning.clone())
                    val acep = parseAcep(meaning)
                    definition.domain = meaning.selectFirst(".domain")?.attr("title") ?: ""
                    fillTarget(definition, acep)
                    applyHeadwords(definition.glosses, word)

                    meaning.select(".refS a.synon").forEach { synEl ->
                        definition.synonyms.add(Word.Synonym(synEl.text()))
                    }
                    headword.definitions.add(definition)
                    meaning.remove()
                }

                // Idioms
                lemma.select(".locs .fc").forEach { fc ->
                    val idiomName = fc.selectFirst(".headword-fc")?.text() ?: ""
                    fc.select(".acep").forEach { meaning ->
                    val primaryDef = meaning.selectFirst(".def")?.text() ?: meaning.text()
                    val idiom = Word.Idiom(idiomName, primaryDef)
                    fillTarget(idiom, parseAcep(meaning))
                    applyHeadwords(idiom.glosses, word)
                    headword.idioms.add(idiom)
                    }
                }

                lemma.remove()
                words.add(headword)
            }

            if (words.size > 1) {
                val homographs = words.map { SearchResult(it.mTitle, it.summary, it.uri) }

                for (word in words) {
                    word.mHomographs.addAll(homographs)
                }
            }

            return words
        }

        private fun fillTarget(definition: Word.Definition, acep: Acep) {
            definition.glosses.addAll(acep.glosses)
            definition.geo = acep.geo
            definition.plev = acep.plev
            definition.register = acep.register
            val primary = acep.glosses.firstOrNull()
            if (primary != null) {
                definition.examples.addAll(primary.examples)
                definition.grammar = primary.grammar
                definition.gender = primary.gender
            }
        }

        private fun fillTarget(idiom: Word.Idiom, acep: Acep) {
            idiom.glosses.addAll(acep.glosses)
            idiom.geo = acep.geo
            idiom.plev = acep.plev
            idiom.register = acep.register
            val primary = acep.glosses.firstOrNull()
            if (primary != null) {
                idiom.examples.addAll(primary.examples)
                idiom.grammar = primary.grammar
                idiom.gender = primary.gender
            }
        }

        // Split the acep children into glosses at .def /.defP boundaries.
        // The primary .def (with the acep-level .gram) starts gloss 1; each
        // .defP starts a following gloss; examples belong to the most recent
        // gloss. Acep-level markers (geo/plev/register) are child attributes.
        private data class Acep(
            val glosses: ArrayList<Word.Gloss>,
            val geo: String,
            val plev: String,
            val register: String
        )

        private fun parseAcep(meaning: Element): Acep {
            val glosses = ArrayList<Word.Gloss>()
            val gramEl = meaning.selectFirst(".gram")
            var gloss: Word.Gloss? = null
            for (child in meaning.children()) {
                when {
                    child.hasClass("def") -> {
                        gloss = Word.Gloss()
                        gloss.definition = child.text()
                        gloss.grammar = gramEl?.text() ?: ""
                        gloss.gender = genderOf(gramEl?.attr("title"))
                        glosses.add(gloss)
                    }
                    child.hasClass("defP") -> {
                        gloss = Word.Gloss()
                        gloss.definition = child.text()
                        gloss.grammar = defPGrammar(child)
                        gloss.gender = genderOf(gloss.grammar)
                        glosses.add(gloss)
                    }
                    child.hasClass("ejemplo") -> gloss?.examples?.add(child.text())
                }
            }
            val geo = meaning.selectFirst(".geo")?.attr("title") ?: ""
            val plev = meaning.selectFirst(".plev")?.attr("title") ?: ""
            val register = meaning.selectFirst(".register")?.attr("title") ?: ""
            return Acep(glosses, geo, plev, register)
        }

        // For pronominal-verb glosses, set the gloss's dictionary headword to
        // the pronominal form of the verb (e.g. "cagar" -> "cagarse"). The
        // headword is left empty when the infinitive already ends in "se".
        private fun applyHeadwords(glosses: ArrayList<Word.Gloss>, headword: String) {
            for (gloss in glosses) {
                if (gloss.grammar.contains("pronominal") && !headword.endsWith("se")) {
                    gloss.headword = "$headword" + "se"
                }
            }
        }

        // A .defP markable carries a grammatical qualifier in one of its
        // (expanded) <abbr> titles, e.g. <abbr title="nombre masculino">m.</abbr>
        // in "Tb. m.". Distinguish those from discourse markers like
        // "También"/"Frecuentemente"/"especialmente".
        private fun defPGrammar(defP: Element): String {
            // Only extract grammar from defPs whose first child is an element
            // (e.g. "Tb. nombre masculino").  Usage notes like "Se usa
            // precedido de artículo" have leading text nodes and should not
            // contribute grammar — the <abbr> there refers to a different
            // word, not a label for this gloss.
            if (defP.childNode(0) !is Element) return ""

            for (el in defP.select("abbr")) {
                val title = el.attr("title")
                if (title.isNotEmpty() && title in GRAMMAR_TITLES) {
                    return title
                }
            }
            return ""
        }

        private val GRAMMAR_TITLES = setOf(
            Genders.GRAMMAR_MASCULINE, Genders.GRAMMAR_MASCULINE_PLURAL,
            Genders.GRAMMAR_FEMININE, Genders.GRAMMAR_FEMININE_PLURAL,
            "adjetivo", "adjetivo invariable", "adverbio", "artículo",
            "conjunción", "expresión", "interjección", "numeral", "participio",
            "preposición", "pronombre", "pronombre átono", "pronombre personal",
            "verbo intransitivo", "verbo intransitivo pronominal", "verbo transitivo",
            "verbo transitivo pronominal", "verbo pronominal", "verbo impersonal",
            "verbo copulativo", "verbo auxiliar",
            "locución", "locución adjetiva", "locución adverbial", "locución conjuntiva",
            "locución interjectiva", "locución nominal", "locución preposicional",
            "locución prepositiva", "locución pronominal", "locución verbal"
        )

        private fun genderOf(gramTitle: String?): String {
            return when (gramTitle) {
                Genders.GRAMMAR_FEMININE, Genders.GRAMMAR_FEMININE_PLURAL -> Genders.FEMININE
                Genders.GRAMMAR_MASCULINE, Genders.GRAMMAR_MASCULINE_PLURAL -> Genders.MASCULINE
                else -> ""
            }
        }
    }
}
